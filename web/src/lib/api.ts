import { normalizePipeline, type PipelineEvent } from "./pipeline";

const CHAT_ENDPOINT = "/api/agent/chat";
const AGENT_TREE_ENDPOINT = "/actuator/agents";
const TIMEOUT_MS = 90_000;

export interface AgentTreeNode {
	id: string;
	description: string;
	tools: string[];
	providers: string[];
	subAgents: string[];
	skills: string[];
}

/** Runtime agent registry (GET /actuator/agents) — id tree + tool lists. */
export async function fetchAgentTree(): Promise<{ root: string; agents: AgentTreeNode[] } | null> {
	try {
		const response = await fetch(AGENT_TREE_ENDPOINT);
		if (!response.ok) return null;
		const json = (await response.json()) as { root?: string; agents?: unknown[] };
		if (!Array.isArray(json.agents)) return null;
		const agents = json.agents
			.filter((agent): agent is Record<string, unknown> => typeof agent === "object" && agent !== null)
			.map((agent) => ({
				id: String(agent.id ?? ""),
				description: String(agent.description ?? ""),
				tools: Array.isArray(agent.tools) ? agent.tools.map(String) : [],
				providers: Array.isArray(agent.providers) ? agent.providers.map(String) : [],
				subAgents: Array.isArray(agent.subAgents) ? agent.subAgents.map(String) : [],
				skills: Array.isArray(agent.skills) ? agent.skills.map(String) : [],
			}))
			.filter((agent) => agent.id);
		return { root: typeof json.root === "string" ? json.root : agents[0]?.id ?? "", agents };
	} catch {
		return null;
	}
}

export class AgentError extends Error {
	constructor(
		message: string,
		readonly kind: "network" | "timeout" | "server" = "server",
		readonly pipeline: PipelineEvent[] = [],
		readonly traceId?: number
	) {
		super(message);
		this.name = "AgentError";
	}
}

/**
 * Failures that happen before the stream opens (rejected request, network, timeout) carry no
 * recorded steps at all — a blank workspace would hide both the task and where it died. Such a
 * failure still gets a minimal client-side trace: the user's task plus one error step, so the
 * dashboard renders the attempt like any failed run.
 */
function failureTrace(title: string, detail: string, task: string): PipelineEvent[] {
	const at = Date.now();
	return [
		{
			id: -2,
			traceId: 0,
			phase: "message",
			actor: "user",
			status: "ok",
			title: task.split("\n")[0].slice(0, 80),
			detail: JSON.stringify({ text: task }),
			startedAt: at,
			durationMs: 0,
			parentId: null,
			agentId: null,
			depth: 0,
			role: "user",
		},
		{
			id: -1,
			traceId: 0,
			phase: "error",
			actor: "client",
			status: "error",
			title,
			detail,
			startedAt: at,
			durationMs: 0,
			parentId: null,
			agentId: null,
			depth: 0,
			role: null,
		},
	];
}

/** The recorded steps when the server provided any; the client-side failure trace when it did not. */
function traceOfFailure(serverEvents: PipelineEvent[], title: string, detail: string, task: string): PipelineEvent[] {
	return serverEvents.length > 0 ? serverEvents : failureTrace(title, detail, task);
}

export interface ChatResult {
	reply: string;
	pipeline: PipelineEvent[];
	traceId: number;
	conversationId?: string;
	durationMs: number;
}

export interface StartInfo {
	traceId: number;
	conversationId?: string;
}

export interface AskOptions {
	/** Called as soon as the server opens the stream (before any pipeline step). */
	onStart?: (info: StartInfo) => void;
	/** Called for every pipeline step the server pushes, in realtime. */
	onPipeline?: (event: PipelineEvent) => void;
	/** Called for every incremental chunk of the assistant answer as it streams. */
	onToken?: (text: string) => void;
}

export interface AskArgs {
	message: string;
	conversationId?: string;
}

interface Frame {
	event: string;
	data: string;
}

function parseFrames(buffer: string): { frames: Frame[]; rest: string } {
	const frames: Frame[] = [];
	let index: number;
	let rest = buffer;

	while ((index = rest.indexOf("\n\n")) >= 0) {
		const raw = rest.slice(0, index);
		rest = rest.slice(index + 2);

		let event = "message";
		const data: string[] = [];
		for (const line of raw.split("\n")) {
			if (line.startsWith("event:")) event = line.slice(6).trim();
			else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
		}
		if (data.length > 0) frames.push({ event, data: data.join("\n") });
	}

	return { frames, rest };
}

/**
 * Calls the agent. The endpoint returns text/event-stream: `start` (traceId + conversationId),
 * then a `pipeline` frame for every step as it happens, then the final `result` frame.
 */
export async function askAgent(args: AskArgs, options: AskOptions = {}): Promise<ChatResult> {
	const controller = new AbortController();
	const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

	let response: Response;
	try {
		response = await fetch(CHAT_ENDPOINT, {
			method: "POST",
			headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
			body: JSON.stringify({ message: args.message, conversationId: args.conversationId }),
			signal: controller.signal,
		});
	} catch (error) {
		if (error instanceof DOMException && error.name === "AbortError") {
			const detail = "The request took too long to process. Please try again.";
			throw new AgentError(detail, "timeout", traceOfFailure([], "request timed out", detail, args.message));
		}
		const detail = "Could not connect to the server. Check that the application is running.";
		throw new AgentError(detail, "network", traceOfFailure([], "connection failed", detail, args.message));
	} finally {
		clearTimeout(timeout);
	}

	const startedAt = Date.now();
	let traceId: number | undefined;
	let conversationId: string | undefined;
	let result: Record<string, unknown> | undefined;

	const handleFrame = (frame: Frame) => {
		let parsed: unknown;
		try {
			parsed = JSON.parse(frame.data);
		} catch {
			return;
		}
		if (frame.event === "start") {
			const payload = parsed as { traceId?: unknown; conversationId?: unknown };
			if (typeof payload.traceId === "number") {
				traceId = payload.traceId;
				if (typeof payload.conversationId === "string") conversationId = payload.conversationId;
				options.onStart?.({ traceId, conversationId });
			}
			return;
		}
		if (frame.event === "pipeline") {
			options.onPipeline?.(parsed as PipelineEvent);
			return;
		}
		if (frame.event === "token") {
			const payload = parsed as { text?: unknown };
			if (typeof payload.text === "string" && payload.text) options.onToken?.(payload.text);
			return;
		}
		if (frame.event === "result") {
			result = parsed as Record<string, unknown>;
		}
	};

	if (!response.ok || !response.body) {
		let detail = `The server returned error ${response.status}.`;
		try {
			const data = (await response.json()) as { error?: string };
			if (typeof data.error === "string" && data.error) detail = data.error;
		} catch {
			/* keep the default */
		}
		// Rejected before the stream opened (guard, validation, or an unhandled server error):
		// the dashboard still shows the task + a red failure row instead of a blank workspace.
		throw new AgentError(withKeyHint(detail), "server",
			traceOfFailure([], "request rejected", withKeyHint(detail), args.message));
	}

	const reader = response.body.getReader();
	const decoder = new TextDecoder();
	let buffer = "";
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		buffer += decoder.decode(value, { stream: true });
		const parsed = parseFrames(buffer);
		buffer = parsed.rest;
		parsed.frames.forEach(handleFrame);
	}
	buffer += decoder.decode();
	parseFrames(buffer + "\n\n").frames.forEach(handleFrame);

	const finalResult = result;
	if (!finalResult) {
		const detail = "No result received from the server. Please try again.";
		throw new AgentError(detail, "server",
			traceOfFailure([], "stream ended without a result", detail, args.message), traceId);
	}

	const reply = typeof finalResult.reply === "string" ? finalResult.reply.trim() : "";
	const error = typeof finalResult.error === "string" ? finalResult.error : null;
	const finalTrace = typeof finalResult.traceId === "number" ? finalResult.traceId : traceId;
	const finalConversation =
		typeof finalResult.conversationId === "string" ? finalResult.conversationId : conversationId;
	const pipeline = normalizePipeline(finalResult.pipeline);

	if (error) {
		throw new AgentError(withKeyHint(error), "server",
			traceOfFailure(pipeline, "request failed", withKeyHint(error), args.message), finalTrace);
	}

	return {
		reply: reply || "The assistant returned no content.",
		pipeline,
		traceId: finalTrace ?? 0,
		conversationId: finalConversation,
		durationMs: Date.now() - startedAt,
	};
}

function withKeyHint(detail: string): string {
	return /OPENROUTER_API_KEY|provider connected/i.test(detail)
		? `${detail}\n\nHint: open the Settings panel in the sidebar and connect a provider.`
		: detail;
}
