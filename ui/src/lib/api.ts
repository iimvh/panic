import { createSseParser, type SseEvent } from './sse';

export type ProviderStatus = { id: string; connected: boolean };
export type CommandInfo = { name: string; usage: string; description: string };
export type State = {
	provider: string | null;
	model: string | null;
	connected: boolean;
	providers: ProviderStatus[];
	commands: CommandInfo[];
};
export type Model = { id: string; name: string; contextLength: number; free: boolean | null };
/** One stored message: questions, answers with their tool calls, tool results and reviewer rejections. */
export type HistoryMessage =
	| { role: 'user'; content: string }
	| { role: 'assistant'; content: string; toolCalls?: { id: string; name: string; arguments: string }[] }
	| { role: 'tool'; toolCallId: string; status: 'ok' | 'error'; content: string }
	| { role: 'review'; reason: string };

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

export const getModels = (filter = '') =>
	json<Model[]>(`/api/models?filter=${encodeURIComponent(filter)}`);

export const putSettings = (change: { provider?: string; model?: string }) =>
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
