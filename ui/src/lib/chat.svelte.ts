import { ApiError, events, follow, getHistory, getState, postApproval, postStop, putSettings, stream, type State } from './api';
import { askApproval, createAnswer, duration, failAnswer, fromHistory, type Approval, type Part } from './reply';
import { sessionId } from './session';

export type Message = {
	id: number;
	role: 'user' | 'assistant' | 'command';
	text: string;
	/** model · tokens, "stopped", ... */
	meta?: string;
	/** An answer's text, tool calls and reviews in the order they happened (assistant only). */
	parts?: Part[];
	/** When the answer was asked (ms since epoch), for the elapsed time while it streams. */
	startedAt?: number;
	/** A tool call waiting for the user to allow or deny it. */
	approval?: Approval;
	error?: boolean;
	streaming?: boolean;
	/** The answer's trace (assistant only), when the server traces turns. */
	traceId?: string;
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
	/** The request in flight is an answer (stopped through the server) rather than a command. */
	#answering = false;
	/** The request in flight, to wait for it to end. */
	#current: Promise<void> = Promise.resolve();

	/** The stored conversation, then the answer still running in this session, if any (after a reload). */
	async load() {
		try {
			const [state, history] = await Promise.all([getState(), getHistory(this.session)]);
			this.state = state;
			if (!this.busy) this.messages = fromHistory(history, () => nextId++);
			this.offline = null;
		} catch (e) {
			this.offline = errorText(e);
			return;
		}
		if (!this.busy) await this.#resume();
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

	/** An answer is stopped by the server (it outlives the connection), so its stream ends with `cancelled`. */
	stop() {
		if (!this.#answering) return this.#abort?.abort();
		postStop(this.session).catch(() => this.#abort?.abort());
	}

	async newChat() {
		if (this.busy) {
			this.stop();
			await this.#current; // the stopped answer ends first, so it cannot come back after the reset
		}
		await this.#command('/reset', true);
		this.messages = [];
	}

	/** Answers the approval question shown in `m`. The tool step that follows shows what happened. */
	async approve(m: Message, allow: boolean) {
		const a = m.approval;
		if (!a || a.answering) return;
		a.answering = true;
		try {
			await postApproval(this.session, a.id, allow);
		} catch (e) {
			// 404: the question is gone (answered, timed out or stopped); the stream tells the rest
			if (!(e instanceof ApiError && e.status === 404)) fail(m, errorText(e));
		} finally {
			if (m.approval?.id === a.id) m.approval = undefined;
		}
	}

	async settings(change: { provider?: string; model?: string }) {
		this.state = await putSettings(change);
	}

	async #ask(text: string) {
		this.messages.push({ id: nextId++, role: 'user', text });
		const reply = this.#push({ id: nextId++, role: 'assistant', text: '', parts: [], streaming: true, startedAt: Date.now() });
		const answer = createAnswer(reply, frame);
		await this.#run(
			() => reply,
			async (signal) => {
				try {
					try {
						await stream('/api/chat', { session: this.session, text }, answer.onEvent, signal);
					} catch (e) {
						// the answer started and the connection broke: it is still running, attach again
						if (!(e instanceof TypeError) || answer.seq() === 0) throw e;
					}
					await this.#reattach(answer, signal);
				} finally {
					answer.finish();
				}
			},
			true
		);
		reply.meta = [reply.meta, duration(Date.now() - (reply.startedAt ?? Date.now()))].filter(Boolean).join(' · ');
		if (!this.state?.connected) void this.refresh();
	}

	/** Picks up the answer this session still runs on the server (the page was reloaded while it ran). */
	async #resume() {
		let reply: Message | null = null;
		await this.#run(
			() => reply,
			async (signal) => {
				const res = await follow(this.session, 0, signal);
				if (!res) return;
				const question = this.#push({ id: nextId++, role: 'user', text: '' });
				reply = this.#push({ id: nextId++, role: 'assistant', text: '', parts: [], streaming: true, startedAt: Date.now() });
				const answer = createAnswer(reply, frame, question);
				try {
					try {
						await events(res, answer.onEvent);
					} catch (e) {
						if (!(e instanceof TypeError)) throw e;
					}
					await this.#reattach(answer, signal);
				} finally {
					answer.finish();
				}
			},
			true
		);
	}

	/**
	 * Attaches again (with backoff, a few times) until the answer's last event came. If it ended while the
	 * page was away, the stored conversation already holds it: the messages are loaded again.
	 */
	async #reattach(answer: ReturnType<typeof createAnswer>, signal: AbortSignal) {
		for (let tries = 0; !answer.ended(); tries++) {
			if (tries === 5) throw new Error('Lost the connection to the answer. Reload the page to see it.');
			await sleep(500 * 2 ** tries, signal);
			try {
				const res = await follow(this.session, answer.seq(), signal);
				if (!res) {
					this.messages = fromHistory(await getHistory(this.session), () => nextId++);
					return;
				}
				await events(res, answer.onEvent);
				tries = -1; // it was working: a new drop starts the count again
			} catch (e) {
				if (!(e instanceof TypeError)) throw e;
			}
		}
	}

	async #command(line: string, quiet = false) {
		const name = line.slice(1).split(/\s+/, 1)[0];
		const out = this.#push({ id: nextId++, role: 'command', text: '', meta: line, streaming: true });
		await this.#run(() => out, (signal) =>
			stream('/api/command', { session: this.session, line }, ({ event, data }) => {
				const d = data as Record<string, unknown>;
				if (event === 'delta') out.text += String(d.text ?? '');
				else if (event === 'approve') askApproval(out, d);
				else if (event === 'unknown') fail(out, `Unknown command /${d.name}. Type /help for the commands available now.`);
				else if (event === 'usage') fail(out, `Usage: /${d.usage} (${d.message})`);
				else if (event === 'error') fail(out, String(d.message));
				else if (event === 'cancelled') out.meta = `${line} · stopped`;
			}, signal)
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

	/** Runs one request at a time; `target` is the message its errors and its end are shown on (none yet: null). */
	#run(target: () => Message | null, request: (signal: AbortSignal) => Promise<void>, answering = false) {
		this.#current = this.#runNow(target, request, answering);
		return this.#current;
	}

	async #runNow(target: () => Message | null, request: (signal: AbortSignal) => Promise<void>, answering: boolean) {
		this.busy = true;
		this.#answering = answering;
		this.#abort = new AbortController();
		try {
			await request(this.#abort.signal);
		} catch (e) {
			const t = target();
			if (!t) this.offline = errorText(e);
			else if (e instanceof DOMException && e.name === 'AbortError') t.meta = (t.meta ? t.meta + ' · ' : '') + 'stopped';
			else fail(t, errorText(e));
		} finally {
			const t = target();
			if (t) {
				t.streaming = false;
				t.approval = undefined;
			}
			this.busy = false;
			this.#answering = false;
			this.#abort = null;
		}
	}
}

/** An answer shows the error where it happened, after what was streamed; a command appends it to its output. */
function fail(m: Message, text: string) {
	if (m.role === 'assistant') return failAnswer(m, text);
	m.error = true;
	m.text = m.text ? `${m.text}\n\n${text}` : text;
}

/** Waits `ms`, or throws an AbortError as soon as `signal` aborts (Stop while waiting to attach again). */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
	return new Promise((resolve, reject) => {
		if (signal.aborted) return reject(new DOMException('Aborted', 'AbortError'));
		const timer = setTimeout(resolve, ms);
		signal.addEventListener('abort', () => {
			clearTimeout(timer);
			reject(new DOMException('Aborted', 'AbortError'));
		}, { once: true });
	});
}

/** Next animation frame; a timer where there is none (tests, a hidden tab still gets its text at the end). */
function frame(run: () => void) {
	if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
	else setTimeout(run, 16);
}

function errorText(e: unknown): string {
	if (e instanceof ApiError) return e.message;
	if (e instanceof TypeError) return 'Cannot reach the Panic server.';
	return e instanceof Error ? e.message : String(e);
}

export const chat = new Chat();
