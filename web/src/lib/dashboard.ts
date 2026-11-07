import type { PipelineEvent } from "./pipeline";

export type WorkStatus = "idle" | "running" | "ok" | "error";
export type Tint = "amber" | "blue" | "purple" | "green" | "cyan" | "red" | "dim";

export interface RoleCard {
	key: string;
	label: string;
	title: string;
	subtitle: string;
	tint: Tint;
	status: WorkStatus;
}

export interface ToolCard {
	key: string;
	title: string;
	subtitle: string;
	status: WorkStatus;
	/** Segment-bar blocks: `done` of `blocks` filled. */
	blocks: number;
	done: number;
}

export interface Checkpoint {
	key: string;
	label: string;
	status: "ready" | "running" | "pending" | "failed";
}

export interface SessionLine {
	id: number;
	marker: "task" | "llm" | "tool" | "error";
	text: string;
	hint: string;
	status: WorkStatus;
}

export interface StreamRow {
	id: number;
	stamp: string;
	actor: string;
	tint: Tint;
	text: string;
	status: WorkStatus;
}

/** One inline chat activity row: an LLM round, a tool call, or a failure. */
export interface ActivityStep {
	id: number;
	label: string;
	/** Right-aligned timing: "running" while live, formatted duration once settled. */
	hint: string;
	phase: "llm" | "tool" | "error";
	status: WorkStatus;
	/** Absolute epoch ms the step started; feeds the live elapsed ticker. */
	startedAt: number;
	durationMs: number | null;
}

export interface DashboardModel {
	/** Root trace event (request received) — carries total duration when closed. */
	trace: { status: WorkStatus; durationMs: number | null } | null;
	/** The user task behind this turn. */
	task: string;
	/** Model name of the last LLM round. */
	model: string | null;
	/** Agent ids invoked as sub-agent tool calls. */
	workers: string[];
	roles: RoleCard[];
	tools: ToolCard[];
	/** LLM rounds numbered 01..n for the right rail checklist. */
	llmRounds: { index: string; text: string; status: WorkStatus; durationMs: number | null }[];
	checkpoints: Checkpoint[];
	session: SessionLine[];
	stream: StreamRow[];
	/** Numbered evidence lines (tool calls), most recent last. */
	evidence: { key: string; label: string; detail: string; timing: string }[];
	/** Last finished tool output for the audit panel. */
	audit: string | null;
	/** Progress over recorded events: finished of total. */
	progress: { done: number; total: number };
}

const DOT_FILL: Record<WorkStatus, string> = {
	idle: "bg-edge-strong",
	running: "bg-green",
	ok: "bg-green",
	error: "bg-red",
};

/** Dot colour for a work status; callers add the pulse animation for "running". */
export function dotClass(status: WorkStatus): string {
	return DOT_FILL[status];
}

function worstStatus(statuses: WorkStatus[]): WorkStatus {
	if (statuses.includes("error")) return "error";
	if (statuses.includes("running")) return "running";
	if (statuses.includes("ok")) return "ok";
	return "idle";
}

export function formatMs(value: number | null | undefined): string {
	if (value == null) return "";
	return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${Math.round(value)}ms`;
}

function compact(text: string, max: number): string {
	const flat = text.replace(/\s+/g, " ").trim();
	return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat;
}

function taskTextOf(events: PipelineEvent[]): string {
	const userEvent = events.find((event) => event.phase === "message" && event.role === "user");
	if (!userEvent) return "";
	try {
		const parsed = JSON.parse(userEvent.detail ?? "{}") as { text?: unknown };
		if (typeof parsed.text === "string" && parsed.text.trim()) return parsed.text;
	} catch {
		/* not JSON: fall through */
	}
	return userEvent.title;
}

function shortAction(detail: string | null): string {
	if (!detail) return "";
	try {
		const parsed = JSON.parse(detail) as Record<string, unknown>;
		for (const key of ["query", "skill", "error", "output", "text", "prompt"]) {
			const value = parsed[key];
			if (typeof value === "string" && value.trim()) return compact(value.trim(), 90);
		}
		return "";
	} catch {
		return compact(detail, 90);
	}
}

interface ToolSourceDetail {
	provider?: string;
	tools?: Array<string | number>;
}

function parseToolDetail(detail: string | null): ToolSourceDetail | null {
	try {
		const parsed = JSON.parse(detail ?? "null") as ToolSourceDetail | null;
		return parsed && typeof parsed === "object" ? parsed : null;
	} catch {
		return null;
	}
}

function stampOf(startedAt: number, t0: number): string {
	const seconds = Math.max(0, (startedAt - t0) / 1000);
	return seconds.toFixed(1).padStart(4, "0");
}

function eventTitleLabel(event: PipelineEvent): string {
	const sub = event.title.match(/^Sub-agent (.+)$/);
	if (sub) return sub[1].trim();
	const source = event.title.match(/^Tool source (.+?) \((.+)\)$/);
	if (source) return source[1].trim().replace(/.*__/, "");
	return compact(event.title, 40);
}

function summaryText(event: PipelineEvent): string {
	if (event.phase === "llm") {
		const arrow = event.title.indexOf("→");
		if (arrow >= 0) {
			const summary = event.title.slice(arrow + 1).trim();
			return summary.length > 0 ? summary : event.title;
		}
	}
	return event.title;
}

function streamActor(event: PipelineEvent): string {
	if (event.role === "user") return "USER";
	switch (event.phase) {
		case "trace": return "COORDINATOR";
		case "llm": {
			const model = event.title.match(/LLM call · (.+?) →/)?.[1] ?? event.actor;
			return (model ?? "LLM").toUpperCase();
		}
		case "tool": return (event.title.match(/^Sub-agent (.+)$/)?.[1]?.trim() ?? "TOOL").toUpperCase();
		case "parse": return "PARSE";
		case "message": return (event.role ?? "msg").toUpperCase();
		case "error": return "ERROR";
		default: return (event.actor || "SYSTEM").toUpperCase();
	}
}

function streamTint(event: PipelineEvent): Tint {
	if (event.status === "error") return "red";
	switch (event.phase) {
		case "llm": return "purple";
		case "tool": return "blue";
		case "parse": return "cyan";
		case "message": return event.role === "user" ? "amber" : "green";
		case "error": return "red";
		default: return "dim";
	}
}

function activityLabel(event: PipelineEvent): string {
	if (event.phase === "llm") {
		const model = event.title.match(/LLM call · (.+?) →/)?.[1] ?? event.actor;
		const arrow = event.title.indexOf("→");
		const route = arrow >= 0 ? event.title.slice(arrow + 1).trim() : "";
		return compact([model, route].filter(Boolean).join(" → "), 60);
	}
	if (event.phase === "error") {
		return compact(event.detail ?? event.title, 72);
	}
	const sub = event.title.match(/^Sub-agent (.+)$/);
	if (sub) return compact(`sub-agent ${sub[1].trim()}`, 60);
	return compact(eventTitleLabel(event), 60);
}

/** Chronological inline steps: LLM routing rounds, tool calls, failures. */
function buildActivitySteps(events: PipelineEvent[]): ActivityStep[] {
	const byId = new Map<number, ActivityStep>();
	const steps: ActivityStep[] = [];
	const ordered = events
		.slice()
		.sort((a, b) => a.startedAt - b.startedAt || a.id - b.id);
	for (const event of ordered) {
		const isErrorFrame = event.phase === "error";
		if (!isErrorFrame && event.phase !== "llm" && event.phase !== "tool") continue;
		const existing = byId.get(event.id);
		if (existing) {
			// First sighting writes the pending row; the closing frame settles it in place,
			// anything after that is superseded chatter.
			if (existing.status !== "running") continue;
			steps.splice(steps.indexOf(existing), 1);
		}
		const step: ActivityStep = {
			id: event.id,
			label: activityLabel(event),
			hint: event.status === "running" ? "running" : formatMs(event.durationMs),
			phase: isErrorFrame ? "error" : (event.phase as "llm" | "tool"),
			status: event.status as WorkStatus,
			startedAt: event.startedAt,
			durationMs: event.durationMs,
		};
		byId.set(event.id, step);
		steps.push(step);
	}
	return steps;
}

export interface ActivityFeed {
	/** Steps that happened before the streamed answer's first token. */
	pre: ActivityStep[];
	/** Steps recorded while the answer is already typing — render after the text. */
	post: ActivityStep[];
}

/** When the first answer token reached the UI one turn (fallback: the recorded assistant message). */
function tokenBoundary(events: PipelineEvent[], tokenStartedAt: number | null): number {
	if (tokenStartedAt != null) return tokenStartedAt;
	const assistantMessage = events.find(
		(event) => event.phase === "message" && event.role === "assistant"
	);
	return assistantMessage?.startedAt ?? Number.POSITIVE_INFINITY;
}

/**
 * The interleaved chat timeline: steps split around the moment the answer text
 * starts typing, so action rows can sandwich the finding like a chat transcript.
 * A null `tokenStartedAt` (replayed turns) falls back to the assistant message event.
 */
export function splitActivity(events: PipelineEvent[], tokenStartedAt: number | null): ActivityFeed {
	const steps = buildActivitySteps(events);
	const boundary = tokenBoundary(events, tokenStartedAt);
	return {
		pre: steps.filter((step) => step.startedAt < boundary),
		post: steps.filter((step) => step.startedAt >= boundary),
	};
}

/** Maps a recorded trace into everything the workspace panels render. */
export function buildDashboard(events: PipelineEvent[], rootAgentId: string | null): DashboardModel {
	const total = events.length;
	const done = events.filter((event) => event.status !== "running").length;

	const traceEvent = events.find((event) => event.phase === "trace") ?? null;
	const t0 = traceEvent?.startedAt || events[0]?.startedAt || 0;

	const ordered = events
		.slice()
		.sort((a, b) => a.startedAt - b.startedAt || a.id - b.id);
	const llmEvents = ordered.filter((event) => event.phase === "llm");
	const toolEvents = ordered.filter((event) => event.phase === "tool");
	const lastLlm = llmEvents[llmEvents.length - 1] ?? null;
	const firstLlm = llmEvents[0] ?? null;

	const mainAgent = lastLlm?.agentId ?? firstLlm?.agentId ?? rootAgentId;
	const model = lastLlm?.title.match(/LLM call · (.+?) →/)?.[1] ?? null;

	const workerIds = new Set<string>();
	for (const event of toolEvents) {
		const match = event.title.match(/^Sub-agent (.+)$/);
		if (match) workerIds.add(match[1].trim());
	}

	// Tool rail: sub-agent cards + tool source cards, single merge pass keeps "running".
	const cards = new Map<string, ToolCard>();
	for (const event of toolEvents) {
		const sub = event.title.match(/^Sub-agent (.+)$/);
		const key = sub ? sub[1].trim() : event.title;
		let card = cards.get(key);
		if (!card) {
			card = { key, title: sub ? key : eventTitleLabel(event), subtitle: "", status: "idle", blocks: 0, done: 0 };
			cards.set(key, card);
		}
		card.blocks += 1;
		if (event.status === "ok") card.done += 1;
		if (event.status === "running") card.status = "running";
		else if (event.status === "error") card.status = "error";
		else if (card.status !== "running") card.status = "ok";
		if (sub) {
			const detail = shortAction(event.detail);
			if (detail) card.subtitle = compact(detail, 60);
		} else {
			const detail = parseToolDetail(event.detail);
			if (detail?.provider) {
				card.subtitle = `${detail.provider.toLowerCase()} · ${detail.tools?.length ?? 0} tools`;
			}
		}
	}

	const llmRounds = llmEvents.map((event, index) => {
		const arrow = event.title.indexOf("→");
		const route = arrow >= 0 ? event.title.slice(arrow + 1).trim() : event.title;
		return {
			index: String(index + 1).padStart(2, "0"),
			text: compact(route, 48),
			status: event.status as WorkStatus,
			durationMs: event.durationMs,
		};
	});

	const hasCheck = (phase: PipelineEvent["phase"], role?: string) =>
		events.some((event) => event.phase === phase && (!role || event.role === role));
	const checkpoints: Checkpoint[] = [
		{
			key: "request",
			label: "request",
			status: !traceEvent
				? "pending"
				: traceEvent.status === "error"
					? "failed"
					: hasCheck("llm")
						? "ready"
						: "running",
		},
		{
			key: "routing",
			label: "routing",
			status: !firstLlm
				? "pending"
				: llmEvents.some((event) => event.status === "ok")
					? "ready"
					: "running",
		},
		{
			key: "tools",
			label: "tools",
			status: toolEvents.some((event) => event.status === "ok")
				? "ready"
				: toolEvents.some((event) => event.status === "running")
					? "running"
					: "pending",
		},
		{
			key: "answer",
			label: "answer",
			status:
				hasCheck("message", "assistant")
					? "ready"
					: traceEvent?.status === "ok"
						? "ready"
						: traceEvent?.status === "running"
							? "running"
							: "pending",
		},
	];

	// Session lines: task first, then one numbered line per recorded step.
	const session: SessionLine[] = [];
	const task = taskTextOf(events);
	if (task) {
		session.push({ id: 0, marker: "task", text: compact(task, 96), hint: "task", status: "ok" });
	}
	for (const event of ordered) {
		if (!["llm", "tool", "parse", "error"].includes(event.phase)) continue;
		if (event.status === "error" && event.phase === "error") {
			session.push({
				id: event.id,
				marker: "error",
				text: compact(event.detail ?? event.title, 96),
				hint: formatMs(event.durationMs),
				status: "error",
			});
			continue;
		}
		if (event.phase === "parse" && !event.title.startsWith("load") && !event.title.startsWith("Loaded")) {
			// JSON parse chatter stays in the stream; the session shows work lines only.
			if (event.status === "ok") continue;
		}
		session.push({
			id: event.id,
			marker: event.phase === "llm" ? "llm" : "tool",
			text: compact(summaryText(event), 110),
			hint: event.status === "running" ? "running" : formatMs(event.durationMs),
			status: event.status as WorkStatus,
		});
	}

	const stream: StreamRow[] = ordered
		.map((event) => ({
			id: event.id,
			stamp: stampOf(event.startedAt, t0),
			actor: streamActor(event),
			tint: streamTint(event),
			text: compact(event.phase === "trace" ? `${event.title} · ${formatMs(event.durationMs)}` : summaryText(event), 96),
			status: event.status as WorkStatus,
		}));

	const evidence = toolEvents
		.filter((event) => event.status === "ok" || event.status === "running")
		.slice(-4)
		.map((event) => ({
			key: String(event.id),
			label: eventTitleLabel(event),
			detail: shortAction(event.detail),
			timing: event.status === "running" ? "running" : formatMs(event.durationMs) || "—",
		}));

	const auditEvent = [...toolEvents]
		.reverse()
		.find((event) => event.status === "ok" && (event.detail?.includes("output") || event.detail?.includes("tools")));
	let audit: string | null = null;
	if (auditEvent) {
		const detail = parseToolDetail(auditEvent.detail);
		audit = compact(
			typeof detail?.provider === "string" && Array.isArray(detail.tools)
				? `${detail.provider} · ${detail.tools.join(", ")}`
				: typeof (detail as Record<string, unknown> | null)?.output === "string"
					? String((detail as Record<string, unknown>).output)
					: auditEvent.title,
			200
		);
	}

	return {
		trace: traceEvent ? { status: traceEvent.status as WorkStatus, durationMs: traceEvent.durationMs } : null,
		task,
		model,
		workers: [...workerIds],
		roles: buildRoles({ mainAgent, model, workerIds, llmEvents, toolEvents, traceEvent }),
		tools: [...cards.values()],
		llmRounds,
		checkpoints,
		session,
		stream,
		evidence,
		audit,
		progress: { done, total },
	};
}

function toolsDoneCount(toolEvents: PipelineEvent[]): number {
	return toolEvents.filter((event) => event.status === "ok").length;
}

function buildRoles(args: {
	mainAgent: string | null;
	model: string | null;
	workerIds: Set<string>;
	llmEvents: PipelineEvent[];
	toolEvents: PipelineEvent[];
	traceEvent: PipelineEvent | null;
}): RoleCard[] {
	const { mainAgent, model, workerIds, llmEvents, toolEvents, traceEvent } = args;
	const llmStatus = llmEvents.length === 0 ? "idle" : worstStatus(llmEvents.map((event) => event.status as WorkStatus));
	const toolStatus = toolEvents.length === 0 ? "idle" : worstStatus(toolEvents.map((event) => event.status as WorkStatus));

	const sourceEvents = toolEvents.filter((event) => event.title.startsWith("Tool source "));
	const toolNames: string[] = [];
	for (const event of sourceEvents) {
		const detail = parseToolDetail(event.detail);
		for (const tool of detail?.tools ?? []) {
			const short = String(tool).split("__").slice(1).join("__");
			if (short) toolNames.push(short);
		}
	}

	const lastLlm = llmEvents[llmEvents.length - 1] ?? null;
	const routeText = lastLlm
		? (() => {
				const arrow = lastLlm.title.indexOf("→");
				return arrow >= 0 ? lastLlm.title.slice(arrow + 1).trim() : lastLlm.title;
			})()
		: "no route yet";

	const workerList = [...workerIds];
	const traceStatus = (traceEvent?.status as WorkStatus) ?? "idle";

	return [
		{
			key: "main",
			label: "MAIN",
			title: mainAgent ?? "agent",
			subtitle: model ?? "no model",
			tint: "amber",
			status: llmStatus,
		},
		{
			key: "workers",
			label: "WORKERS",
			title: workerList[0] ?? "none",
			subtitle:
				workerList.length > 1
					? `+${workerList.length - 1} more`
					: `${toolsDoneCount(toolEvents)} calls`,
			tint: "blue",
			status: toolStatus,
		},
		{
			key: "tools",
			label: "TOOLS",
			title: toolNames[0] ?? "tool / path",
			subtitle: toolNames.length > 1 ? `${sourceEvents.length} sources · ${toolNames.length} tools` : "standby",
			tint: "purple",
			status: toolStatus,
		},
		{
			key: "routing",
			label: "ROUTING",
			title: compact(routeText, 26),
			subtitle: lastLlm ? formatMs(lastLlm.durationMs) : "standby",
			tint: "green",
			status: traceStatus,
		},
	];
}
