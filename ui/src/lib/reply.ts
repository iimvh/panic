import type { HistoryMessage } from './api';
import type { SseEvent } from './sse';
import type { Message } from './chat.svelte';

/** One tool call of the bot, as the `tool` server-sent events describe it. */
export type ToolStep = {
	id: string;
	name: string;
	arguments: string;
	/** drafting: the model is still writing the call (`tool_delta` events), it has not run yet. */
	status: 'drafting' | 'running' | 'ok' | 'error';
	content?: string;
	/** While drafting: the call's position in the model's reply, which pieces are matched by. */
	draft?: number;
};

/**
 * One piece of an answer, in the order it happened: text the model wrote, a tool call, a reviewer's verdict,
 * or an error that ended the stream. Text after a tool call starts a new text part, so a turn with several
 * model calls reads as "text, tool, tool, text" instead of one merged blob.
 */
export type Part =
	| { kind: 'text'; text: string }
	| { kind: 'tool'; step: ToolStep }
	| { kind: 'review'; attempt: number; pass: boolean; reason: string }
	| { kind: 'error'; text: string };

/** A tool call waiting for the user's approval, as the `approve` server-sent event describes it. */
export type Approval = {
	id: string;
	/** The tool call it is about (the id of its `tool` steps). */
	callId: string;
	name: string;
	arguments: string;
	/** read, write, exec or network */
	risk: string;
	/** The answer is being sent. */
	answering?: boolean;
};

/** Appends streamed text: to the last part when it is text, else as a new text part. */
export function appendText(m: Message, delta: string) {
	if (!delta) return;
	m.text += delta;
	m.parts ??= [];
	const last = m.parts.at(-1);
	if (last?.kind === 'text') last.text += delta;
	else m.parts.push({ kind: 'text', text: delta });
}

/** Shows the question; the call's finished `tool` step (or the end of the stream) clears it. */
export function askApproval(m: Message, d: Record<string, unknown>) {
	m.approval = {
		id: String(d.id ?? ''),
		callId: String(d.callId ?? ''),
		name: String(d.name ?? ''),
		arguments: String(d.arguments ?? ''),
		risk: String(d.risk ?? '')
	};
}

/** Adds a piece of a tool call the model is still writing: a new drafting row, or more of its arguments. */
export function toolDelta(m: Message, d: Record<string, unknown>) {
	m.parts ??= [];
	const index = Number(d.index ?? 0);
	const part = m.parts.find((p) => p.kind === 'tool' && p.step.status === 'drafting' && p.step.draft === index);
	const piece = String(d.arguments ?? '');
	if (part?.kind === 'tool') {
		if (d.id) part.step.id = String(d.id);
		if (d.name) part.step.name = String(d.name);
		part.step.arguments += piece;
	} else {
		const step: ToolStep = { id: String(d.id ?? ''), name: String(d.name ?? ''), arguments: piece, status: 'drafting', draft: index };
		m.parts.push({ kind: 'tool', step });
	}
}

/**
 * Adds a tool call when it starts, and updates it in place when it finishes. A call that starts takes the
 * place of its draft (same id, else the first draft left), so it stays where the model wrote it.
 */
export function toolStep(m: Message, d: Record<string, unknown>) {
	const step: ToolStep = {
		id: String(d.id ?? ''),
		name: String(d.name ?? ''),
		arguments: String(d.arguments ?? ''),
		status: d.status === 'ok' || d.status === 'error' ? d.status : 'running',
		content: d.content === undefined ? undefined : String(d.content)
	};
	m.parts ??= [];
	const find = (match: (s: ToolStep) => boolean) => m.parts!.findIndex((p) => p.kind === 'tool' && match(p.step));
	let i: number;
	if (step.status === 'running') {
		i = find((s) => s.status === 'drafting' && s.id === step.id);
		if (i < 0) i = find((s) => s.status === 'drafting');
	} else {
		i = find((s) => s.id === step.id && s.status === 'running');
	}
	if (i >= 0) m.parts[i] = { kind: 'tool', step };
	else m.parts.push({ kind: 'tool', step });
	if (step.status !== 'running' && m.approval?.callId === step.id) m.approval = undefined;
}

/** At the end of a stream: calls still drafting never ran (the turn stopped or failed). */
export function endDrafts(m: Message) {
	for (const p of m.parts ?? []) {
		if (p.kind === 'tool' && p.step.status === 'drafting') {
			p.step.status = 'error';
			p.step.content = 'not run';
			delete p.step.draft;
		}
	}
}

/** "812 B", "3.2 KB". */
export function size(text: string): string {
	const n = new TextEncoder().encode(text).length;
	return n < 1024 ? `${n} B` : `${(n / 1024).toFixed(1)} KB`;
}

/** A reviewer's verdict on the answer so far; a rejection is followed by another attempt. */
export function review(m: Message, d: Record<string, unknown>) {
	m.parts ??= [];
	m.parts.push({ kind: 'review', attempt: Number(d.attempt ?? 0), pass: d.pass === true, reason: String(d.reason ?? '') });
}

/**
 * Folds stored messages into what the page shows: each question, then one answer holding the text, tool
 * calls, their results and review rejections of that turn, in order. `id` numbers the messages.
 */
export function fromHistory(history: HistoryMessage[], id: () => number): Message[] {
	const out: Message[] = [];
	let answer: Message | null = null;
	const reply = () => {
		if (!answer) {
			answer = { id: id(), role: 'assistant', text: '', parts: [] };
			if (traceId) answer.traceId = traceId;
			out.push(answer);
		}
		return answer;
	};
	let attempt = 0;
	let traceId: string | undefined;
	for (const m of history) {
		if (m.role === 'user') {
			out.push({ id: id(), role: 'user', text: m.content });
			answer = null;
			attempt = 0;
			traceId = m.traceId;
		} else if (m.role === 'assistant') {
			const a = reply();
			if (m.content) appendText(a, m.content);
			for (const c of m.toolCalls ?? []) toolStep(a, { ...c, status: 'running' });
		} else if (m.role === 'tool') {
			const a = reply();
			const call = (a.parts ?? []).find((p) => p.kind === 'tool' && p.step.id === m.toolCallId && p.step.status === 'running');
			const name = call?.kind === 'tool' ? call.step.name : '';
			const args = call?.kind === 'tool' ? call.step.arguments : '';
			toolStep(a, { id: m.toolCallId, name, arguments: args, status: m.status, content: m.content });
		} else if (m.role === 'review') {
			review(reply(), { attempt: ++attempt, pass: false, reason: m.reason });
		}
	}
	return out;
}

/** Events after which an answer stream has nothing more to say. */
const LAST = new Set(['done', 'cancelled', 'error', 'unavailable']);

/**
 * Builds `reply` from the events of one answer, from `POST /api/chat` or from `GET /api/stream` when the page
 * resumes it (then `question` gets the text of the `start` event). `seq()` is the last SSE id seen, to resume
 * after; `ended()` says whether the last event came. Text is batched with `schedule` (see createBatcher), and
 * `finish()` flushes it and closes drafts once the stream is over.
 */
export function createAnswer(reply: Message, schedule: (run: () => void) => void, question?: Message) {
	const text$ = createBatcher((t) => appendText(reply, t), schedule);
	let seq = 0;
	let ended = false;
	return {
		onEvent({ event, data, id }: SseEvent) {
			if (id !== undefined && Number(id) > seq) seq = Number(id);
			const d = (data ?? {}) as Record<string, unknown>;
			if (event === 'delta') return text$.push(String(d.text ?? ''));
			text$.flush(); // anything else comes after the text before it
			if (LAST.has(event)) ended = true;
			if (typeof d.traceId === 'string' && (event === 'start' || event === 'done')) reply.traceId = d.traceId;
			if (event === 'start') {
				if (question && !question.text) question.text = String(d.question ?? '');
				// a resumed answer started before this page: take the server's time
				if (question && typeof d.startedAt === 'number') reply.startedAt = d.startedAt;
			} else if (event === 'tool_delta') toolDelta(reply, d);
			else if (event === 'tool') toolStep(reply, d);
			else if (event === 'review') review(reply, d);
			else if (event === 'approve') askApproval(reply, d);
			else if (event === 'done') reply.meta = meta(d);
			else if (event === 'unavailable') failAnswer(reply, 'No LLM is connected. Open Settings to pick a provider with an API key.');
			else if (event === 'error') failAnswer(reply, String(d.message));
			else if (event === 'cancelled') reply.meta = 'stopped';
		},
		seq: () => seq,
		ended: () => ended,
		finish() {
			text$.flush();
			endDrafts(reply);
		}
	};
}

/** An answer shows the error where it happened, after what was streamed. */
export function failAnswer(m: Message, text: string) {
	m.error = true;
	m.parts ??= [];
	m.parts.push({ kind: 'error', text });
}

/** What a streaming answer is doing right now, for the status line. */
export function activity(m: Message): string {
	if (m.approval) return 'Waiting for approval';
	const last = m.parts?.at(-1);
	if (last?.kind === 'tool' && last.step.status === 'running') return `Running ${last.step.name}…`;
	if (last?.kind === 'tool' && last.step.status === 'drafting') return `Writing ${last.step.name || 'a tool'} call…`;
	if (last?.kind === 'text') return 'Writing…';
	if (last?.kind === 'review' && !last.pass) return 'Trying again…';
	return 'Thinking…';
}

/** "4s", "1m 05s". */
export function duration(ms: number): string {
	const s = Math.max(0, Math.floor(ms / 1000));
	if (s < 60) return `${s}s`;
	return `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, '0')}s`;
}

/**
 * Collects streamed text and hands it to `apply` at most once per `schedule` tick (an animation frame in
 * the page), so a fast stream re-renders once per frame, not once per token. Call `flush()` before handling
 * any other event and at the end of the stream, so the order of text and tool calls is kept.
 */
export function createBatcher(apply: (text: string) => void, schedule: (run: () => void) => void) {
	let pending = '';
	let scheduled = false;
	function flush() {
		if (!pending) return;
		const text = pending;
		pending = '';
		apply(text);
	}
	return {
		push(text: string) {
			pending += text;
			if (scheduled) return;
			scheduled = true;
			schedule(() => {
				scheduled = false;
				flush();
			});
		},
		flush
	};
}

export function meta(d: Record<string, unknown>): string {
	const parts = [String(d.model ?? '')];
	const u = d.usage as { promptTokens?: number; completionTokens?: number } | undefined;
	const tokens = (u?.promptTokens ?? 0) + (u?.completionTokens ?? 0);
	if (tokens > 0) parts.push(`${tokens} tokens`);
	const calls = Number(d.toolCalls ?? 0);
	if (calls > 0) parts.push(calls === 1 ? '1 tool' : `${calls} tools`);
	if (d.stop && d.stop !== 'done') {
		const why = d.stop === 'rejected' && d.review ? ` (${String(d.review)})` : '';
		parts.push(`stopped: ${String(d.stop).replace('_', ' ')}${why}`);
	}
	else if (d.finishReason && d.finishReason !== 'stop') parts.push(String(d.finishReason));
	return parts.filter(Boolean).join(' · ');
}
