import type { PipelineEvent } from "./pipeline";

/** Conversation roles rendered in the trajectory stream (badge = role uppercase). */
export type TrajectoryRole = "system" | "user" | "assistant" | "tool" | "context";

/** Rows of the top timeline. */
export type TrajectoryRow = "input" | "model" | "tools";

export const TRAJECTORY_ROLES: TrajectoryRole[] = ["system", "user", "assistant", "tool", "context"];

const ROLE_LABEL: Record<TrajectoryRole, string> = {
	system: "SYSTEM",
	user: "USER",
	assistant: "ASSISTANT",
	tool: "TOOL",
	context: "CONTEXT",
};

export function roleLabel(role: TrajectoryRole): string {
	return ROLE_LABEL[role];
}

const VALID_ROLES = new Set<string>(TRAJECTORY_ROLES);

/**
 * The explicit role of the live events; falls back to the phase for events recorded
 * by pipeline agnostic components (llm → assistant, tool/parse → tool, the rest → context).
 */
export function roleOf(event: PipelineEvent): TrajectoryRole {
	if (event.role && VALID_ROLES.has(event.role)) return event.role as TrajectoryRole;
	switch (event.phase) {
		case "llm":
			return "assistant";
		case "tool":
		case "parse":
			return "tool";
		default:
			return "context";
	}
}

/** Timeline row a role plots into. */
export function rowOf(role: TrajectoryRole): TrajectoryRow {
	if (role === "system" || role === "user") return "input";
	if (role === "assistant") return "model";
	return "tools";
}

export interface TimelineSegment {
	event: PipelineEvent;
	row: TrajectoryRow;
	/** Left offset of the bar in percent of the trace window. */
	left: number;
	/** Bar width in percent (never 0 so short entries stay visible). */
	width: number;
}

export interface TrajectoryWindow {
	startedAt: number;
	totalMs: number;
}

/** One bar per event; positions in %, a floor keeps zero-length entries clickable. */
export function timeWindow(events: PipelineEvent[], nowMs?: number): TrajectoryWindow {
	let min = Number.POSITIVE_INFINITY;
	let max = 0;
	let hasStart = false;
	for (const event of events) {
		if (event.startedAt <= 0) continue;
		hasStart = true;
		min = Math.min(min, event.startedAt);
		const end = event.startedAt + (event.durationMs ?? 0);
		max = Math.max(max, Number.isFinite(end) ? end : 0);
	}
	if (hasStart && typeof nowMs === "number") {
		max = Math.max(max, nowMs);
	}
	if (!hasStart || max <= min) {
		return { startedAt: hasStart ? min : 0, totalMs: 0 };
	}
	return { startedAt: min, totalMs: max - min };
}

/** One bar per event; positions in %, a floor keeps zero-length entries clickable. */
export function timeline(events: PipelineEvent[], window: TrajectoryWindow): TimelineSegment[] {
	if (window.totalMs <= 0) return [];
	const percent = (ms: number) => (ms / window.totalMs) * 100;
	return events
		.filter((event) => event.startedAt > 0)
		.sort((a, b) => a.startedAt - b.startedAt)
		.map((event) => {
			const durationMs = event.durationMs ?? 0;
			const left = percent(event.startedAt - window.startedAt);
			const width = durationMs === 0 ? 1.2 : percent(durationMs);
			return { event, row: rowOf(roleOf(event)), left, width: Math.max(width, 1.2) };
		});
}

/** Documents used by the chips above the timeline. */
export interface TrajectoryStats {
	/** Total trace duration in ms (0 while the scale is not known). */
	durationMs: number;
	/** Number of assistant turns (model steps). */
	turns: number;
	/** Number of tool/parse steps. */
	calls: number;
}

export function stats(events: PipelineEvent[], window: TrajectoryWindow): TrajectoryStats {
	let turns = 0;
	let calls = 0;
	for (const event of events) {
		if (roleOf(event) === "assistant") turns += 1;
		if (event.phase === "tool" || event.phase === "parse") calls += 1;
	}
	return { durationMs: window.totalMs, turns, calls };
}

/** Stream rows filtered by a plain text search over role/title/detail. */
export function matchesSearch(event: PipelineEvent, query: string): boolean {
	const needle = query.trim().toLowerCase();
	if (!needle) return true;
	return (
		event.title.toLowerCase().includes(needle) ||
		event.actor.toLowerCase().includes(needle) ||
		(event.detail ?? "").toLowerCase().includes(needle)
	);
}

/** Formats ms like the screenshot: decimals over 1s, otherwise ms. */
export function formatDuration(value: number | null | undefined): string {
	if (value == null) return "—";
	return value >= 1000 ? `${(value / 1000).toFixed(2)}s` : `${Math.round(value)}ms`;
}
