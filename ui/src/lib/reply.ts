import type { Message } from './chat.svelte';

/** One tool call of the bot, as the `tool` server-sent events describe it. */
export type ToolStep = {
	id: string;
	name: string;
	arguments: string;
	status: 'running' | 'ok' | 'error';
	content?: string;
};

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

/** Adds a tool call when it starts, and updates it in place when it finishes. */
export function toolStep(m: Message, d: Record<string, unknown>) {
	const step: ToolStep = {
		id: String(d.id ?? ''),
		name: String(d.name ?? ''),
		arguments: String(d.arguments ?? ''),
		status: d.status === 'ok' || d.status === 'error' ? d.status : 'running',
		content: d.content === undefined ? undefined : String(d.content)
	};
	m.tools ??= [];
	const i = m.tools.findIndex((t) => t.id === step.id && t.status === 'running');
	if (step.status !== 'running' && i >= 0) m.tools[i] = step;
	else m.tools.push(step);
	if (step.status !== 'running' && m.approval?.callId === step.id) m.approval = undefined;
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
