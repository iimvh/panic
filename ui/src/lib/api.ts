import type { ConversationInfo, Workspaces } from './conversations';
import { createSseParser, type SseEvent } from './sse';

export type ProviderStatus = { id: string; connected: boolean };
export type CommandInfo = { name: string; usage: string; description: string };
export type State = {
	provider: string | null;
	model: string | null;
	/** null when off. */
	reasoningEffort: string | null;
	connected: boolean;
	/** Whether the model takes an effort, as its provider lists it; null when unknown. */
	effortSupported: boolean | null;
	providers: ProviderStatus[];
	commands: CommandInfo[];
};
/** `maxOutput`: the most tokens a reply may have, 0 when unknown; `reasoning`: takes an effort, null when unknown. */
export type Model = {
	id: string;
	name: string;
	contextLength: number;
	maxOutput: number;
	free: boolean | null;
	reasoning: boolean | null;
};
/** One stored message: questions, answers with their tool calls, tool results and reviewer rejections. */
export type HistoryMessage =
	| { role: 'user'; content: string; traceId?: string }
	| { role: 'assistant'; content: string; toolCalls?: { id: string; name: string; arguments: string }[] }
	| { role: 'tool'; toolCallId: string; status: 'ok' | 'error'; content: string }
	| { role: 'review'; reason: string };

/** One span of a trace, as `GET /api/trace` returns it. Times are microseconds. */
export type TraceSpan = {
	id: string;
	parentId: string | null;
	name: string;
	startMicros: number;
	durationMicros: number;
	status: 'ok' | 'error' | 'cancelled';
	error?: string;
	attrs: Record<string, unknown>;
};
export type Trace = { traceId: string; spans: TraceSpan[] };

export class ApiError extends Error {
	constructor(
		message: string,
		readonly status: number
	) {
		super(message);
	}
}

async function fail(res: Response): Promise<never> {
	let message = `${res.status} ${res.statusText}`;
	try {
		const body = await res.json();
		if (body && typeof body.error === 'string') message = body.error;
	} catch {
		// not JSON
	}
	throw new ApiError(message, res.status);
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
	const res = await fetch(path, init);
	if (!res.ok) await fail(res);
	return res.json() as Promise<T>;
}

export const getState = () => json<State>('/api/state');

export const getHistory = (session: string) =>
	json<HistoryMessage[]>(`/api/history?session=${encodeURIComponent(session)}`);

/** The stored conversations, newest first. */
export const getConversations = () => json<ConversationInfo[]>('/api/conversations');

/** Deletes a conversation with its plan and traces; ApiError 409 while an answer runs in it, 404 when unknown. */
export const deleteConversation = (session: string) =>
	json<{ ok: boolean }>(`/api/conversations?session=${encodeURIComponent(session)}`, { method: 'DELETE' });

/** The projects and their chats; null when the server has no `workspace` plugin (503). */
export async function getWorkspaces(): Promise<Workspaces | null> {
	const res = await fetch('/api/workspaces');
	if (res.status === 503) return null;
	if (!res.ok) await fail(res);
	return res.json() as Promise<Workspaces>;
}

/** Whether the session runs without tool approvals (YOLO) and the config default; null when the server has no `permissions` plugin (503). */
export type Permissions = { bypass: boolean; default: boolean };
export async function getPermissions(session: string): Promise<Permissions | null> {
	const res = await fetch(`/api/permissions?session=${encodeURIComponent(session)}`);
	if (res.status === 503) return null;
	if (!res.ok) await fail(res);
	return res.json() as Promise<Permissions>;
}

/** Whether the session is in plan mode and the config default; null when the server has no `planner` plugin (503). */
export type Mode = { plan: boolean; default: boolean };
export async function getMode(session: string): Promise<Mode | null> {
	const res = await fetch(`/api/mode?session=${encodeURIComponent(session)}`);
	if (res.status === 503) return null;
	if (!res.ok) await fail(res);
	return res.json() as Promise<Mode>;
}

/** The argument of a command line: quoted so a path with spaces stays one argument. */
export const quoteArg = (arg: string) => (arg.includes('"') ? `'${arg}'` : `"${arg}"`);

/** Runs a command and returns what it printed; ApiError when it failed, was unknown or got bad arguments. */
export async function runCommand(session: string, line: string, signal?: AbortSignal): Promise<string> {
	let text = '';
	let problem: string | null = null;
	await stream('/api/command', { session, line }, ({ event, data }) => {
		const d = data as Record<string, unknown>;
		if (event === 'delta') text += String(d.text ?? '');
		else if (event === 'unknown') problem = `Unknown command /${d.name}.`;
		else if (event === 'usage') problem = `Usage: /${d.usage} (${d.message})`;
		else if (event === 'error') problem = String(d.message);
		else if (event === 'approve' || event === 'plan' || event === 'question') problem = 'This command needs an approval; run it from the chat.';
	}, signal);
	if (problem) throw new ApiError(problem, 400);
	return text.trim();
}

/** One trace of the session; ApiError 404 when unknown or when tracing is off (its message says which). */
export const getTrace = (session: string, id: string) =>
	json<Trace>(`/api/trace?session=${encodeURIComponent(session)}&id=${encodeURIComponent(id)}`);

export const getModels = (filter = '') =>
	json<Model[]>(`/api/models?filter=${encodeURIComponent(filter)}`);

/** `reasoningEffort`: a level, or "off". */
export type SettingsChange = { provider?: string; model?: string; reasoningEffort?: string };
export const putSettings = (change: SettingsChange) =>
	json<State>('/api/settings', {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(change)
	});

/** Answers an `approve` event of a chat or command stream. */
export const postApproval = (session: string, id: string, allow: boolean) =>
	json<{ ok: boolean }>('/api/approve', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ session, id, allow })
	});

/** Answers a `plan` event: approve the plan, or send it back with `feedback`. */
export const postPlan = (session: string, id: string, approve: boolean, feedback = '') =>
	json<{ ok: boolean }>('/api/plan', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ session, id, approve, feedback })
	});

/** Answers a `question` event: one answer per question, in order; all empty skips. */
export const postAnswer = (session: string, id: string, answers: { selected: string[]; other: string }[]) =>
	json<{ ok: boolean }>('/api/answer', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ session, id, answers })
	});

/** Asks the server to stop the session's answer; the answer's stream then ends with `cancelled`. */
export const postStop = (session: string) =>
	json<{ ok: boolean }>('/api/stop', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ session })
	});

/** POSTs `body` and hands every server-sent event to `onEvent` until the stream ends. */
export async function stream(
	path: '/api/chat' | '/api/command',
	body: Record<string, string>,
	onEvent: (e: SseEvent) => void,
	signal?: AbortSignal
): Promise<void> {
	const res = await fetch(path, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body),
		signal
	});
	await events(res, onEvent);
}

/**
 * Attaches to the session's answer in flight (events after SSE id `after`, then live). Null when none runs;
 * else read its events with `events(res, onEvent)`.
 */
export async function follow(session: string, after: number, signal?: AbortSignal): Promise<Response | null> {
	const res = await fetch(`/api/stream?session=${encodeURIComponent(session)}&after=${after}`, { signal });
	return res.status === 204 ? null : res;
}

/** Hands every server-sent event of `res` to `onEvent` until the stream ends. */
export async function events(res: Response, onEvent: (e: SseEvent) => void): Promise<void> {
	if (!res.ok || !res.body) await fail(res);
	const parser = createSseParser(onEvent);
	const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		parser.feed(value);
	}
	parser.end();
}
