import { ApiError, deleteConversation, events, follow, getConversations, getHistory, getMode, getPermissions, getState, getWorkspaces, postAnswer, postApproval, postPlan, postStop, putSettings, quoteArg, runCommand, stream, type Mode, type Permissions, type State } from './api';
import { without, type ConversationInfo, type Workspaces } from './conversations';
import { CONTINUE_TEXT, askApproval, askPlan, askUser, clearApproval, createAnswer, duration, failAnswer, fromHistory, type Approval, type Part, type PlanProposal, type Questions } from './reply';
import { newSessionId, sessionId, setSessionId } from './session';

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
	/** More questions, asked while `approval` is shown (calls running in parallel); shown next, in order. */
	waiting?: Approval[];
	/** A plan the agent proposes (plan mode), waiting for the user to approve it or send it back. */
	plan?: PlanProposal;
	/** Why the answer ended (`done`, `max_steps`, `budget`, `rejected`), from the `done` event (assistant only). */
	stop?: string;
	/** Questions the agent asks, waiting for the user's answers. */
	question?: Questions;
	error?: boolean;
	streaming?: boolean;
	/** The answer's trace (assistant only), when the server traces turns. */
	traceId?: string;
};

/**
 * What the inspector panel shows: a thought in full, a tool call, or an answer's trace. A thought is the live part
 * itself (it grows in place while it streams); a tool call is found again by its answer and id, because a call that
 * ends replaces its part. `agent` names the sub-agent the thought or call belongs to, if any.
 */
export type Inspected =
	| { kind: 'part'; part: Part; agent?: string }
	| { kind: 'tool'; messageId: number; id: string; agent?: string }
	| { kind: 'trace'; traceId: string };

let nextId = 1;

/** All UI state: what the server runs (state), this session's messages, and the request in flight. */
class Chat {
	/** The conversation shown; another one is opened with `open`, a new one started with `newChat`. */
	session = $state(sessionId());
	state = $state<State | null>(null);
	messages = $state<Message[]>([]);
	/** The stored conversations, newest first, for the chat list. */
	conversations = $state<ConversationInfo[]>([]);
	/** The projects (workspaces) and their chats; null while unknown or when the server has none. */
	workspaces = $state<Workspaces | null>(null);
	/** Bypass mode (tool calls run without asking) of this session; null when the server has no policy plugin. */
	permissions = $state<Permissions | null>(null);
	/** Plan mode of this session; null when the server has no planner plugin. */
	mode = $state<Mode | null>(null);
	busy = $state(false);
	/** Shown in the inspector panel; null when it is closed. Page state only: nothing is asked of the server. */
	inspected = $state<Inspected | null>(null);
	/** Server unreachable or failing; shown as a banner. */
	offline = $state<string | null>(null);
	#abort: AbortController | null = null;
	/** The folder dialog being waited for (Add project), to give up on it. */
	#picking: AbortController | null = null;
	/** The request in flight is an answer (stopped through the server) rather than a command. */
	#answering = false;
	/** The request in flight, to wait for it to end. */
	#current: Promise<void> = Promise.resolve();

	/** The stored conversation, then the answer still running in this session, if any (after a reload). */
	async load() {
		void this.loadConversations();
		void this.loadWorkspaces();
		void this.loadPermissions();
		void this.loadMode();
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

	/** Goes on with an answer a limit stopped: a new ask on the same history (Cursor's "Continue"). */
	async continueAnswer() {
		await this.send(CONTINUE_TEXT);
	}

	/** An answer is stopped by the server (it outlives the connection), so its stream ends with `cancelled`. */
	stop() {
		if (!this.#answering) return this.#abort?.abort();
		postStop(this.session).catch(() => this.#abort?.abort());
	}

	/** The chat list again; a failure leaves it as it was (the banner tells about a server gone). */
	async loadConversations() {
		try {
			this.conversations = await getConversations();
		} catch {
			// offline: load() reports it
		}
	}

	/** The bypass state of this session; a failure leaves it as it was. */
	async loadPermissions() {
		try {
			this.permissions = await getPermissions(this.session);
		} catch {
			// offline: load() reports it
		}
	}

	/** Turns bypass mode (YOLO) on or off for this chat; null is the error text when that failed. */
	async setBypass(on: boolean): Promise<string | null> {
		try {
			await runCommand(this.session, `bypass ${on ? 'on' : 'off'}`);
		} catch (e) {
			return errorText(e);
		}
		await this.loadPermissions();
		return null;
	}

	/** The plan mode of this session; a failure leaves it as it was. */
	async loadMode() {
		try {
			this.mode = await getMode(this.session);
		} catch {
			// offline: load() reports it
		}
	}

	/** Turns plan mode on or off for this chat; null is the error text when that failed. */
	async setPlanMode(on: boolean): Promise<string | null> {
		try {
			await runCommand(this.session, `plan ${on ? 'on' : 'off'}`);
		} catch (e) {
			return errorText(e);
		}
		await this.loadMode();
		return null;
	}

	/** The projects again; a failure leaves them as they were. */
	async loadWorkspaces() {
		try {
			this.workspaces = await getWorkspaces();
		} catch {
			// offline: load() reports it
		}
	}

	/**
	 * Starts a new conversation; the current one stays in the list. A running answer is stopped first. With
	 * `project` (a folder path) the chat works in that project; null is the error text when that failed.
	 */
	async newChat(project?: string): Promise<string | null> {
		await this.#settle();
		this.session = newSessionId();
		this.messages = [];
		this.inspected = null;
		let problem: string | null = null;
		if (project) {
			try {
				await runCommand(this.session, `workspace use ${quoteArg(project)}`);
			} catch (e) {
				problem = errorText(e);
			}
		}
		void this.loadConversations();
		void this.loadWorkspaces();
		void this.loadPermissions();
		void this.loadMode();
		return problem;
	}

	/**
	 * Adds a project: the folder `path` when given, else the one chosen in the folder dialog on the server's
	 * machine. Returns what the server said; `needsPath` when no dialog could give a folder (type one instead).
	 */
	async addProject(path?: string): Promise<{ text: string; needsPath: boolean; error: boolean }> {
		this.#picking = new AbortController();
		try {
			const text = await runCommand(this.session, path ? `workspace add ${quoteArg(path)}` : 'workspace pick', this.#picking.signal);
			void this.loadWorkspaces();
			return { text, needsPath: !path && text.includes('workspace add <path>'), error: false };
		} catch (e) {
			// cancelled by the user: the server closes the dialog when the stream goes away
			if (e instanceof DOMException && e.name === 'AbortError') return { text: '', needsPath: true, error: false };
			return { text: errorText(e), needsPath: false, error: true };
		} finally {
			this.#picking = null;
		}
	}

	/** Gives up waiting for the folder dialog: the server closes it, and the page offers a typed path. */
	cancelAddProject() {
		this.#picking?.abort();
	}

	/** Removes a project from the list; its chats are hidden until the folder is added again. */
	async removeProject(path: string): Promise<string | null> {
		try {
			await runCommand(this.session, `workspace remove ${quoteArg(path)}`);
		} catch (e) {
			return errorText(e);
		}
		await this.loadWorkspaces();
		return null;
	}

	/** Shows conversation `id`. A running answer is stopped first, so it cannot land in the other chat. */
	async open(id: string) {
		if (id === this.session) return;
		await this.#settle();
		this.session = setSessionId(id);
		this.messages = [];
		this.inspected = null;
		await this.load();
	}

	/** Opens the inspector on `what`; the same thing again closes it (a toggle, as its row reads). */
	inspect(what: Inspected) {
		this.inspected = this.isInspected(what) ? null : what;
	}

	/** True when the inspector shows `what` now. */
	isInspected(what: Inspected): boolean {
		const now = this.inspected;
		if (!now || now.kind !== what.kind) return false;
		if (what.kind === 'part') return now.kind === 'part' && now.part === what.part;
		if (what.kind === 'tool') return now.kind === 'tool' && now.messageId === what.messageId && now.id === what.id;
		return now.kind === 'trace' && now.traceId === what.traceId;
	}

	/** Stops what runs (an answer through the server) and waits for it to end. */
	async #settle() {
		if (!this.busy) return;
		this.stop();
		await this.#current;
	}

	/** Deletes conversation `id` with its plan and traces; the current one makes way for a new chat. */
	async remove(id: string): Promise<string | null> {
		if (id === this.session && this.busy) return 'Stop the answer before deleting this chat.';
		try {
			await deleteConversation(id);
		} catch (e) {
			if (e instanceof ApiError && e.status === 409) return 'An answer is still running in that chat.';
			if (!(e instanceof ApiError && e.status === 404)) return errorText(e);
		}
		this.conversations = without(this.conversations, id);
		if (id === this.session) await this.newChat();
		else void this.loadConversations();
		void this.loadWorkspaces();
		return null;
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
			clearApproval(m, (x) => x.id === a.id);
		}
	}

	/**
	 * Answers the plan shown in `m`: approve it (plan mode ends and the agent carries it out), or send it back
	 * with `feedback`. The `exit_plan_mode` step that follows shows what happened.
	 */
	async answerPlan(m: Message, approve: boolean, feedback = '') {
		const p = m.plan;
		if (!p || p.answering) return;
		p.answering = true;
		try {
			await postPlan(this.session, p.id, approve, feedback.trim());
		} catch (e) {
			// 404: the plan is gone (answered, timed out or stopped); the stream tells the rest
			if (!(e instanceof ApiError && e.status === 404)) fail(m, errorText(e));
		} finally {
			if (m.plan?.id === p.id) m.plan = undefined;
			void this.loadMode();
		}
	}

	/** Answers the questions shown in `m`, one answer per question; all empty skips. The `ask_user` step follows. */
	async answerQuestions(m: Message, answers: { selected: string[]; other: string }[]) {
		const q = m.question;
		if (!q || q.answering) return;
		q.answering = true;
		try {
			await postAnswer(this.session, q.id, answers);
		} catch (e) {
			// 404: the question is gone (answered, timed out or stopped); the stream tells the rest
			if (!(e instanceof ApiError && e.status === 404)) fail(m, errorText(e));
		} finally {
			if (m.question?.id === q.id) m.question = undefined;
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
		void this.loadConversations(); // a new chat appears, the one used moves up
		void this.loadMode(); // an approved plan ends plan mode
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
		let prompt: string | null = null;
		await this.#run(() => out, (signal) =>
			stream('/api/command', { session: this.session, line }, ({ event, data }) => {
				const d = data as Record<string, unknown>;
				if (event === 'prompt') prompt = String(d.line ?? line);
				else if (event === 'delta') out.text += String(d.text ?? '');
				else if (event === 'approve') askApproval(out, d);
				else if (event === 'plan') askPlan(out, d);
				else if (event === 'question') askUser(out, d);
				else if (event === 'unknown') fail(out, `Unknown command /${d.name}. Type /help for the commands available now.`);
				else if (event === 'usage') fail(out, `Usage: /${d.usage} (${d.message})`);
				else if (event === 'error') fail(out, String(d.message));
				else if (event === 'cancelled') out.meta = `${line} · stopped`;
			}, signal)
		);
		if (prompt !== null) {
			// not a command but a slash prompt (a skill): asked like any question, the server expands it
			this.messages = this.messages.filter((m) => m.id !== out.id);
			await this.#ask(prompt);
			return;
		}
		if (name === 'reset' && !out.error) {
			this.messages = [];
			void this.loadConversations();
		}
		else if (quiet) this.messages = this.messages.filter((m) => m.id !== out.id);
		void this.refresh(); // commands may change what runs (provider, model, ...)
		void this.loadMode(); // ... or the chat's mode (/plan on)
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
				t.waiting = undefined;
				t.plan = undefined;
				t.question = undefined;
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
