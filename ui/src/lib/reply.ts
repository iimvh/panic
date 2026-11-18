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
	/** When this call runs a sub-agent: what it writes and the tools it calls, as they happen. */
	agent?: SubAgent;
};

/** A sub-agent's work under the call that runs it (`agent_delta` and `tool` events with `parent`). */
export type SubAgent = { name: string; text: string; parts: Part[] };

/** Something that holds a timeline of parts: an answer, or a sub-agent inside it. */
type Timeline = { text: string; parts?: Part[] };

/**
 * One piece of an answer, in the order it happened: what the model thought, text it wrote, a tool call, a
 * reviewer's verdict, or an error that ended the stream. Text after a tool call starts a new text part, so a turn
 * with several model calls reads as "text, tool, tool, text" instead of one merged blob.
 */
export type Part =
	| Reasoning
	| { kind: 'text'; text: string }
	| { kind: 'tool'; step: ToolStep }
	| { kind: 'review'; attempt: number; pass: boolean; reason: string; error?: string }
	| { kind: 'error'; text: string };

/**
 * What the model thought before it wrote or acted (`reasoning` events), shown, never saved. `endedAt` is set once
 * something else happens; until then the model is still thinking. Times are this page's clock (ms).
 */
export type Reasoning = { kind: 'reasoning'; text: string; startedAt: number; endedAt?: number };

/** A tool call waiting for the user's approval, as the `approve` server-sent event describes it. */
export type Approval = {
	id: string;
	/** The tool call it is about (the id of its `tool` steps). */
	callId: string;
	name: string;
	arguments: string;
	/** read, write, exec or network */
	risk: string;
	/** The sub-agent that makes the call, if it is not the main agent. */
	agent?: string;
	/** The answer is being sent. */
	answering?: boolean;
};

/** The tool the agent calls to propose its plan in plan mode. */
export const EXIT_PLAN_TOOL = 'exit_plan_mode';

/** A plan the agent proposes in plan mode, as the `plan` server-sent event describes it. */
export type PlanProposal = {
	id: string;
	/** markdown */
	plan: string;
	items: string[];
	criteria: string[];
	/** The answer is being sent. */
	answering?: boolean;
};

/** Shows the proposed plan; the finished `exit_plan_mode` step (or the end of the stream) clears it. */
export function askPlan(m: Message, d: Record<string, unknown>) {
	const strings = (v: unknown) => (Array.isArray(v) ? v.map(String) : []);
	m.plan = { id: String(d.id ?? ''), plan: String(d.plan ?? ''), items: strings(d.items), criteria: strings(d.criteria) };
}

/** The tool the agent calls to ask the user. */
export const ASK_USER_TOOL = 'ask_user';

/** One question the agent asks: a few options, one or several to pick, and the user's own text always allowed. */
export type UserQuestion = {
	question: string;
	header: string;
	multiSelect: boolean;
	options: { label: string; description: string }[];
};

/** Questions waiting for the user, as the `question` server-sent event describes them. */
export type Questions = {
	id: string;
	questions: UserQuestion[];
	/** The answer is being sent. */
	answering?: boolean;
};

/** Shows the agent's questions; the finished `ask_user` step (or the end of the stream) clears them. */
export function askUser(m: Message, d: Record<string, unknown>) {
	const list = Array.isArray(d.questions) ? (d.questions as Record<string, unknown>[]) : [];
	m.question = {
		id: String(d.id ?? ''),
		questions: list.map((q) => ({
			question: String(q.question ?? ''),
			header: String(q.header ?? ''),
			multiSelect: q.multiSelect === true,
			options: (Array.isArray(q.options) ? (q.options as Record<string, unknown>[]) : []).map((o) => ({
				label: String(o.label ?? ''),
				description: String(o.description ?? '')
			}))
		}))
	};
}

/**
 * A shell command call (`{command, cwd?, background?}`, the `exec` tool) as the user reads it: `$ command`, with
 * where and how it runs; null for any other arguments.
 */
export function shellCommand(args: string): string | null {
	try {
		const a = JSON.parse(args) as Record<string, unknown>;
		if (typeof a?.command !== 'string') return null;
		const where = typeof a.cwd === 'string' && a.cwd ? `   (in ${a.cwd})` : '';
		return `$ ${a.command}${where}${a.background === true ? '   [background]' : ''}`;
	} catch {
		return null;
	}
}

/** Appends streamed text: to the last part when it is text, else as a new text part. */
export function appendText(m: Timeline, delta: string) {
	if (!delta) return;
	m.text += delta;
	m.parts ??= [];
	const last = m.parts.at(-1);
	if (last?.kind === 'text') last.text += delta;
	else m.parts.push({ kind: 'text', text: delta });
}

/** Appends streamed reasoning: to the last part while it is unfinished reasoning, else as a new reasoning part. */
export function appendReasoning(t: Timeline, delta: string, now: number) {
	if (!delta) return;
	t.parts ??= [];
	const last = t.parts.at(-1);
	if (last?.kind === 'reasoning' && last.endedAt === undefined) last.text += delta;
	else t.parts.push({ kind: 'reasoning', text: delta, startedAt: now });
}

/** The model stopped thinking: it writes, calls a tool, or the stream ended. */
export function endReasoning(t: Timeline, now: number) {
	for (const p of t.parts ?? []) if (p.kind === 'reasoning' && p.endedAt === undefined) p.endedAt = now;
}

/** "Thinking · 4s" while it lasts, "Thought for 4s" after; no time when it took under a second (or was replayed). */
export function reasoningLabel(p: Reasoning, now: number): string {
	const ms = (p.endedAt ?? now) - p.startedAt;
	if (p.endedAt === undefined) return ms >= 1000 ? `Thinking · ${duration(ms)}` : 'Thinking';
	return ms >= 1000 ? `Thought for ${duration(ms)}` : 'Thought';
}

/**
 * Shows the question; the call's finished `tool` step (or the end of the stream) clears it. Calls running in
 * parallel may ask at the same time: one question is shown, the others wait in `waiting`, in arrival order.
 */
export function askApproval(m: Message, d: Record<string, unknown>) {
	const a: Approval = {
		id: String(d.id ?? ''),
		callId: String(d.callId ?? ''),
		name: String(d.name ?? ''),
		arguments: String(d.arguments ?? ''),
		risk: String(d.risk ?? '')
	};
	if (d.agent) a.agent = String(d.agent);
	if (!m.approval || m.approval.id === a.id) m.approval = a;
	else if (!m.waiting?.some((w) => w.id === a.id)) (m.waiting ??= []).push(a);
}

/** Removes the questions `gone` matches; when the one shown goes, the next waiting one is shown. */
export function clearApproval(m: Message, gone: (a: Approval) => boolean) {
	if (m.waiting) m.waiting = m.waiting.filter((a) => !gone(a));
	if (m.approval && gone(m.approval)) m.approval = m.waiting?.shift();
	if (m.waiting?.length === 0) m.waiting = undefined;
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
 * place of its draft (same id, else the first draft left), so it stays where the model wrote it. A sub-agent's
 * call (`parent` set) goes into the timeline of the call that runs that sub-agent.
 */
export function toolStep(m: Message, d: Record<string, unknown>) {
	const step: ToolStep = {
		id: String(d.id ?? ''),
		name: String(d.name ?? ''),
		arguments: String(d.arguments ?? ''),
		status: d.status === 'ok' || d.status === 'error' ? d.status : 'running',
		content: d.content === undefined ? undefined : String(d.content)
	};
	const into = d.parent ? subAgent(m, String(d.parent), d.agent) : m;
	if (into) upsertStep(into, step);
	if (step.status !== 'running') clearApproval(m, (a) => a.callId === step.id);
	if (step.status !== 'running' && step.name === EXIT_PLAN_TOOL && !d.parent) m.plan = undefined;
	if (step.status !== 'running' && step.name === ASK_USER_TOOL && !d.parent) m.question = undefined;
}

function upsertStep(t: Timeline, step: ToolStep) {
	t.parts ??= [];
	const find = (match: (s: ToolStep) => boolean) => t.parts!.findIndex((p) => p.kind === 'tool' && match(p.step));
	let i: number;
	if (step.status === 'running') {
		i = find((s) => s.status === 'drafting' && s.id === step.id);
		if (i < 0) i = find((s) => s.status === 'drafting');
	} else {
		i = find((s) => s.id === step.id && s.status === 'running');
	}
	const old = i >= 0 ? t.parts[i] : undefined;
	if (old?.kind === 'tool' && old.step.agent) step.agent = old.step.agent; // the sub-agent's work stays
	if (i >= 0) t.parts[i] = { kind: 'tool', step };
	else t.parts.push({ kind: 'tool', step });
}

/** The sub-agent run by call `parent` of `m` (created on its first event); undefined when there is no such call. */
function subAgent(m: Message, parent: string, name: unknown): SubAgent | undefined {
	for (let i = (m.parts?.length ?? 0) - 1; i >= 0; i--) {
		const p = m.parts![i];
		if (p.kind === 'tool' && p.step.id === parent) {
			p.step.agent ??= { name: String(name ?? ''), text: '', parts: [] };
			return p.step.agent;
		}
	}
	return undefined;
}

/**
 * Adds text a sub-agent wrote (`agent_delta`) to the timeline of the call that runs it: what it thinks when
 * `reasoning` is set, else its text, which ends its thinking.
 */
export function agentText(m: Message, d: Record<string, unknown>, now = Date.now()) {
	const agent = subAgent(m, String(d.parent ?? ''), d.agent);
	if (!agent) return;
	if (d.reasoning === true) return appendReasoning(agent, String(d.text ?? ''), now);
	endReasoning(agent, now);
	appendText(agent, String(d.text ?? ''));
}

/** The tool call `id` of answer `messageId`, also among its sub-agents' calls; the latest one when ids repeat. */
export function findStep(messages: Message[], messageId: number, id: string): ToolStep | undefined {
	const search = (parts: Part[] | undefined): ToolStep | undefined => {
		for (let i = (parts?.length ?? 0) - 1; i >= 0; i--) {
			const p = parts![i];
			if (p.kind !== 'tool') continue;
			if (p.step.id === id) return p.step;
			const inner = search(p.step.agent?.parts);
			if (inner) return inner;
		}
		return undefined;
	};
	return search(messages.find((m) => m.id === messageId)?.parts);
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

/**
 * A reviewer's verdict on the answer so far; a rejection is followed by another attempt. A reviewer that failed
 * sends `error` instead: the answer stands, unchecked, and the part says so.
 */
export function review(m: Message, d: Record<string, unknown>) {
	m.parts ??= [];
	const attempt = Number(d.attempt ?? 0);
	if (typeof d.error === 'string') {
		m.parts.push({ kind: 'review', attempt, pass: true, reason: '', error: d.error });
		return;
	}
	m.parts.push({ kind: 'review', attempt, pass: d.pass === true, reason: String(d.reason ?? '') });
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
export function createAnswer(
	reply: Message,
	schedule: (run: () => void) => void,
	question?: Message,
	clock: () => number = Date.now
) {
	const text$ = createBatcher((t) => appendText(reply, t), schedule);
	const thought$ = createBatcher((t) => appendReasoning(reply, t, clock()), schedule);
	/** Anything but more reasoning: the reasoning so far is shown and ends. */
	const thoughtDone = () => {
		thought$.flush();
		endReasoning(reply, clock());
	};
	// one batcher per sub-agent, so parallel sub-agents writing at once still render once per frame
	const agents = new Map<string, ReturnType<typeof createBatcher>>();
	const flushAgents = () => agents.forEach((b) => b.flush());
	let seq = 0;
	let ended = false;
	return {
		onEvent({ event, data, id }: SseEvent) {
			if (id !== undefined && Number(id) > seq) seq = Number(id);
			const d = (data ?? {}) as Record<string, unknown>;
			if (event === 'reasoning') {
				text$.flush();
				return thought$.push(String(d.text ?? ''));
			}
			if (event === 'delta') {
				thoughtDone();
				return text$.push(String(d.text ?? ''));
			}
			if (event === 'agent_delta') {
				// one batcher per sub-agent and kind: its thinking and its text never merge
				const key = `${String(d.parent ?? '')}${d.reasoning === true ? ':reasoning' : ''}`;
				let b = agents.get(key);
				if (!b) {
					b = createBatcher((t) => agentText(reply, { ...d, text: t }, clock()), schedule);
					agents.set(key, b);
				}
				if (d.reasoning !== true) agents.get(`${String(d.parent ?? '')}:reasoning`)?.flush();
				return b.push(String(d.text ?? ''));
			}
			thoughtDone();
			text$.flush(); // anything else comes after the text before it
			flushAgents();
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
			else if (event === 'plan') askPlan(reply, d);
			else if (event === 'question') askUser(reply, d);
			else if (event === 'done') {
				reply.meta = meta(d);
				if (typeof d.stop === 'string') reply.stop = d.stop;
			}
			else if (event === 'unavailable') failAnswer(reply, 'No LLM is connected. Open Settings to pick a connected provider.');
			else if (event === 'error') failAnswer(reply, String(d.message));
			else if (event === 'cancelled') reply.meta = 'stopped';
		},
		seq: () => seq,
		ended: () => ended,
		finish() {
			thoughtDone();
			text$.flush();
			flushAgents();
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
	if (m.plan) return 'Waiting for plan approval';
	if (m.question) return 'Waiting for your answer';
	const last = m.parts?.at(-1);
	const running = (m.parts ?? []).filter((p) => p.kind === 'tool' && p.step.status === 'running');
	if (running.length > 1) return `Running ${running.length} tools…`;
	if (last?.kind === 'tool' && last.step.status === 'running' && last.step.agent) {
		const inner = last.step.agent.parts.at(-1);
		const what = inner?.kind === 'tool' && inner.step.status === 'running' ? `running ${inner.step.name}` : 'working';
		return `${last.step.agent.name}: ${what}…`;
	}
	if (last?.kind === 'tool' && last.step.status === 'running') return `Running ${last.step.name}…`;
	if (last?.kind === 'tool' && last.step.status === 'drafting') return `Writing ${last.step.name || 'a tool'} call…`;
	if (last?.kind === 'text') return 'Writing…';
	if (last?.kind === 'reasoning' && last.endedAt === undefined) return 'Thinking…';
	if (last?.kind === 'review' && !last.pass) return 'Trying again…';
	// nothing from the model yet: it may be thinking without showing it, loading, or the provider is slow
	return 'Waiting for the model…';
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

/** The message the Continue button sends: the history holds the work, so a plain ask picks it up. */
export const CONTINUE_TEXT = 'Continue where you stopped.';

/**
 * Whether an answer ended on a hard limit (steps or tokens) and can be continued, as Cursor offers "Continue" after
 * its tool call limit and the Claude Agent SDK resumes a session after max_turns.
 */
export function canContinue(m: Message): boolean {
	return m.role === 'assistant' && !m.streaming && (m.stop === 'max_steps' || m.stop === 'budget');
}

export function meta(d: Record<string, unknown>): string {
	const parts = [String(d.model ?? '')];
	const u = d.usage as { promptTokens?: number; completionTokens?: number; reasoningTokens?: number } | undefined;
	const tokens = (u?.promptTokens ?? 0) + (u?.completionTokens ?? 0);
	const thinking = u?.reasoningTokens ?? 0;
	if (tokens > 0) parts.push(thinking > 0 ? `${tokens} tokens (${thinking} thinking)` : `${tokens} tokens`);
	const calls = Number(d.toolCalls ?? 0);
	if (calls > 0) parts.push(calls === 1 ? '1 tool' : `${calls} tools`);
	if (d.stop && d.stop !== 'done') {
		const why = d.stop === 'rejected' && d.review ? ` (${String(d.review)})` : '';
		parts.push(`stopped: ${String(d.stop).replace('_', ' ')}${why}`);
	}
	else if (d.finishReason && d.finishReason !== 'stop') parts.push(String(d.finishReason));
	return parts.filter(Boolean).join(' · ');
}
