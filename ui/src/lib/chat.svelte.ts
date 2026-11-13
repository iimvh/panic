import { ApiError, getHistory, getState, putSettings, stream, type State } from './api';
import { sessionId } from './session';

export type Message = {
	id: number;
	role: 'user' | 'assistant' | 'command';
	text: string;
	/** model · tokens, "stopped", ... */
	meta?: string;
	error?: boolean;
	streaming?: boolean;
};

let nextId = 1;

/** All UI state: what the server runs (state), this session's messages, and the request in flight. */
class Chat {
	readonly session = sessionId();
	state = $state<State | null>(null);
	messages = $state<Message[]>([]);
	busy = $state(false);
	/** Server unreachable or failing; shown as a banner. */
	offline = $state<string | null>(null);
	#abort: AbortController | null = null;

	async load() {
		try {
			const [state, history] = await Promise.all([getState(), getHistory(this.session)]);
			this.state = state;
			this.messages = history
				.filter((m) => m.role !== 'system')
				.map((m) => ({ id: nextId++, role: m.role as 'user' | 'assistant', text: m.content }));
			this.offline = null;
		} catch (e) {
			this.offline = errorText(e);
		}
	}

	async refresh() {
		try {
			this.state = await getState();
			this.offline = null;
		} catch (e) {
			this.offline = errorText(e);
		}
	}

	/** Plain text asks the bot; a line starting with "/" runs a command, as in the CLI. */
	async send(input: string) {
		const text = input.trim();
		if (!text || this.busy) return;
		if (text.startsWith('/')) await this.#command(text);
		else await this.#ask(text);
	}

	stop() {
		this.#abort?.abort();
	}

	async newChat() {
		if (this.busy) this.stop();
		await this.#command('/reset', true);
		this.messages = [];
	}

	async settings(change: { provider?: string; model?: string }) {
		this.state = await putSettings(change);
	}

	async #ask(text: string) {
		this.messages.push({ id: nextId++, role: 'user', text });
		const reply = this.#push({ id: nextId++, role: 'assistant', text: '', streaming: true });
		await this.#run(reply, () =>
			stream('/api/chat', { session: this.session, text }, ({ event, data }) => {
				const d = data as Record<string, unknown>;
				if (event === 'delta') reply.text += String(d.text ?? '');
				else if (event === 'done') reply.meta = meta(d);
				else if (event === 'unavailable') {
					reply.error = true;
					reply.text = 'No LLM is connected. Open Settings to pick a provider with an API key.';
				} else if (event === 'error') fail(reply, String(d.message));
				else if (event === 'cancelled') reply.meta = 'stopped';
			}, this.#abort?.signal)
		);
		if (!this.state?.connected) void this.refresh();
	}

	async #command(line: string, quiet = false) {
		const name = line.slice(1).split(/\s+/, 1)[0];
		const out = this.#push({ id: nextId++, role: 'command', text: '', meta: line, streaming: true });
		await this.#run(out, () =>
			stream('/api/command', { session: this.session, line }, ({ event, data }) => {
				const d = data as Record<string, unknown>;
				if (event === 'delta') out.text += String(d.text ?? '');
				else if (event === 'unknown') fail(out, `Unknown command /${d.name}. Type /help for the commands available now.`);
				else if (event === 'usage') fail(out, `Usage: /${d.usage} (${d.message})`);
				else if (event === 'error') fail(out, String(d.message));
				else if (event === 'cancelled') out.meta = `${line} · stopped`;
			}, this.#abort?.signal)
		);
		if (name === 'reset' && !out.error) this.messages = [];
		else if (quiet) this.messages = this.messages.filter((m) => m.id !== out.id);
		void this.refresh(); // commands may change what runs (provider, model, ...)
	}

	/** Adds a message and returns its reactive proxy (mutations on it update the page). */
	#push(m: Message): Message {
		this.messages.push(m);
		return this.messages[this.messages.length - 1];
	}

	async #run(target: Message, request: () => Promise<void>) {
		this.busy = true;
		this.#abort = new AbortController();
		try {
			await request(); // reads this.#abort lazily, so it gets the controller made above
		} catch (e) {
			if (e instanceof DOMException && e.name === 'AbortError') target.meta = (target.meta ? target.meta + ' · ' : '') + 'stopped';
			else fail(target, errorText(e));
		} finally {
			target.streaming = false;
			this.busy = false;
			this.#abort = null;
		}
	}
}

function fail(m: Message, text: string) {
	m.error = true;
	m.text = m.text ? `${m.text}\n\n${text}` : text;
}

function meta(d: Record<string, unknown>): string {
	const parts = [String(d.model ?? '')];
	const u = d.usage as { promptTokens?: number; completionTokens?: number } | undefined;
	const tokens = (u?.promptTokens ?? 0) + (u?.completionTokens ?? 0);
	if (tokens > 0) parts.push(`${tokens} tokens`);
	if (d.finishReason && d.finishReason !== 'stop') parts.push(String(d.finishReason));
	return parts.filter(Boolean).join(' · ');
}

function errorText(e: unknown): string {
	if (e instanceof ApiError) return e.message;
	if (e instanceof TypeError) return 'Cannot reach the Panic server.';
	return e instanceof Error ? e.message : String(e);
}

export const chat = new Chat();
