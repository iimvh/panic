import type { TraceSpan } from './api';

/** One row of the waterfall: a span placed relative to the whole trace. */
export type TraceRow = {
	id: string;
	depth: number;
	/** "llm · step 2", "tool · read_file". */
	label: string;
	/** Where the bar starts and how wide it is, in % of the root's duration (0–100). */
	offset: number;
	width: number;
	/** ms from the root's start, and the span's own duration in ms. */
	startMs: number;
	durationMs: number;
	/** Prompt + completion tokens, 0 when none were reported. */
	tokens: number;
	status: TraceSpan['status'];
	error?: string;
	/** The short facts shown next to the label (tokens, first token, approval wait...). */
	detail: string;
	attrs: Record<string, unknown>;
};

/**
 * Lays a trace out as a waterfall: each span under its parent, children in the order they started, bars scaled
 * to the root. Spans whose parent is missing hang under the root, so nothing is lost.
 */
export function layout(spans: TraceSpan[]): TraceRow[] {
	if (spans.length === 0) return [];
	const root = spans.find((s) => !s.parentId) ?? spans[0];
	const ids = new Set(spans.map((s) => s.id));
	const children = new Map<string, TraceSpan[]>();
	for (const s of spans) {
		if (s === root) continue;
		const parent = s.parentId && ids.has(s.parentId) ? s.parentId : root.id;
		children.set(parent, [...(children.get(parent) ?? []), s]);
	}
	for (const list of children.values()) list.sort((a, b) => a.startMicros - b.startMicros);

	const total = Math.max(root.durationMicros, 1);
	const rows: TraceRow[] = [];
	const visit = (s: TraceSpan, depth: number) => {
		const start = Math.max(0, s.startMicros - root.startMicros);
		const offset = clamp((start / total) * 100);
		rows.push({
			id: s.id,
			depth,
			label: label(s),
			offset,
			width: Math.min(100 - offset, Math.max(0, (s.durationMicros / total) * 100)),
			startMs: start / 1000,
			durationMs: s.durationMicros / 1000,
			tokens: num(s.attrs.promptTokens) + num(s.attrs.completionTokens),
			status: s.status,
			error: s.error,
			detail: detail(s),
			attrs: s.attrs
		});
		for (const c of children.get(s.id) ?? []) visit(c, depth + 1);
	};
	visit(root, 0);
	return rows;
}

export function label(s: TraceSpan): string {
	if (s.name === 'llm') return `llm · step ${num(s.attrs.step)}`;
	if (s.name === 'tool') return `tool · ${String(s.attrs.tool ?? '?')}`;
	if (s.name === 'review') return `review · ${num(s.attrs.attempt)}`;
	if (s.name === 'turn' && s.attrs.agent) return `agent · ${String(s.attrs.agent)}`; // a sub-agent's turn
	return s.name;
}

function detail(s: TraceSpan): string {
	const a = s.attrs;
	const parts: string[] = [];
	const tokens = num(a.promptTokens) + num(a.completionTokens);
	if (s.name === 'turn') {
		if (a.stop && a.stop !== 'done') parts.push(`stopped: ${String(a.stop).replace('_', ' ')}`);
		if (a.steps !== undefined) parts.push(num(a.steps) === 1 ? '1 step' : `${num(a.steps)} steps`);
	}
	if (tokens > 0) parts.push(`${num(a.promptTokens)}+${num(a.completionTokens)} tok`);
	if (s.name === 'llm' && a.firstTokenMicros !== undefined) parts.push(`first token ${ms(num(a.firstTokenMicros) / 1000)}`);
	if (s.name === 'tool') {
		if (num(a.approvalMicros) >= 1000) parts.push(`approval ${ms(num(a.approvalMicros) / 1000)}`);
		if (a.outputChars !== undefined) parts.push(`${num(a.outputChars)} chars${a.truncated ? ' (cut)' : ''}`);
		if (a.refused) parts.push('refused');
	}
	if (s.name === 'review' && a.verdict) parts.push(String(a.verdict));
	if (s.name === 'compaction') parts.push(`${num(a.messages)} messages`);
	return parts.join(' · ');
}

/** "850µs", "12ms", "1.25s", "2m 05s". */
export function ms(value: number): string {
	if (value < 1) return `${Math.round(value * 1000)}µs`;
	if (value < 1000) return `${Math.round(value)}ms`;
	if (value < 60_000) return `${(value / 1000).toFixed(2)}s`;
	const s = Math.floor(value / 1000);
	return `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, '0')}s`;
}

function num(v: unknown): number {
	return typeof v === 'number' && Number.isFinite(v) ? v : 0;
}

function clamp(v: number): number {
	return Math.min(100, Math.max(0, v));
}
