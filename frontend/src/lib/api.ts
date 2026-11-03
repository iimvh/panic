import { normalizePipeline, type PipelineEvent } from "./pipeline";

const CHAT_ENDPOINT = "/api/agent/chat";
const TIMEOUT_MS = 90_000;

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

export interface ChatResult {
	reply: string;
	pipeline: PipelineEvent[];
	traceId: number;
	durationMs: number;
}

export interface AskOptions {
	/**
	 * The server streams the first frame (`start`) as soon as processing begins, with the traceId.
	 * The UI opens SSE using this traceId to watch each agent step in realtime.
	 */
	onStart?: (traceId: number) => void;
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
 * Calls the agent. The endpoint returns text/event-stream so the header + `start` frame reach the client
 * before the agent finishes; the final `result` frame holds the reply and the full pipeline.
 */
export async function askAgent(message: string, options: AskOptions = {}): Promise<ChatResult> {
	const controller = new AbortController();
	const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

	let response: Response;
	try {
		response = await fetch(CHAT_ENDPOINT, {
			method: "POST",
			headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
			body: JSON.stringify({ message }),
			signal: controller.signal,
		});
	} catch (error) {
		if (error instanceof DOMException && error.name === "AbortError") {
			throw new AgentError("The request took too long to process. Please try again.", "timeout");
		}
		throw new AgentError(
			"Could not connect to the server. Check that the application is running.",
			"network"
		);
	} finally {
		clearTimeout(timeout);
	}

	const startedAt = Date.now();
	let traceId: number | undefined;
	let result: Record<string, unknown> | undefined;

	const handleFrame = (frame: Frame) => {
		let parsed: unknown;
		try {
			parsed = JSON.parse(frame.data);
		} catch {
			return;
		}
		if (frame.event === "start") {
			const id = (parsed as { traceId?: unknown }).traceId;
			if (typeof id === "number") {
				traceId = id;
				options.onStart?.(id);
			}
			return;
		}
		if (frame.event === "result") {
			result = parsed as Record<string, unknown>;
		}
	};

	if (!response.ok || !response.body) {
		// The server failed before opening the stream: read the plain error JSON.
		let detail = `The server returned error ${response.status}.`;
		try {
			const data = (await response.json()) as { error?: string };
			if (typeof data.error === "string" && data.error) detail = data.error;
		} catch {
			/* keep the default */
		}
		throw new AgentError(withKeyHint(detail));
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
		throw new AgentError("No result received from the server. Please try again.", "server", [], traceId);
	}

	const reply = typeof finalResult.reply === "string" ? finalResult.reply.trim() : "";
	const error = typeof finalResult.error === "string" ? finalResult.error : null;
	const finalTrace = typeof finalResult.traceId === "number" ? finalResult.traceId : traceId;
	const pipeline = normalizePipeline(finalResult.pipeline);

	if (error) {
		throw new AgentError(withKeyHint(error), "server", pipeline, finalTrace);
	}

	return {
		reply: reply || "The assistant returned no content.",
		pipeline,
		traceId: finalTrace ?? 0,
		durationMs: Date.now() - startedAt,
	};
}

function withKeyHint(detail: string): string {
	return /OPENROUTER_API_KEY/i.test(detail)
		? `${detail}\n\nHint: set the OPENROUTER_API_KEY environment variable and restart the server.`
		: detail;
}
