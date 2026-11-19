import type { Page, Route } from '@playwright/test';

/**
 * A scripted stand-in for the `web` plugin's API, installed with `page.route`: no Java server, no model, no keys.
 * Event names and fields are the ones `src/lib/core/reply.ts` reads; when the Java API changes, change these in
 * the same commit.
 *
 * An answer is a script of segments. Between two segments the server waits for the user's decision
 * (`/api/approve`, `/api/plan` or `/api/answer`), the way the real one blocks a turn: the page's stream ends
 * without a last event, and the page attaches again (`/api/stream?after=N`) to get the rest.
 */
export type Ev = { event: string; data: Record<string, unknown> };
export type Decision = { kind: 'approve' | 'plan' | 'answer'; body: Record<string, unknown> };
export type Script = (question: string) => { segments: ((decision?: Decision) => Ev[])[]; answer: string } | null;

type Session = {
	title: string;
	history: Record<string, unknown>[];
	events: Ev[];
	waiting: ((d: Decision) => void) | null;
	running: boolean;
};

export class FakeApi {
	sessions = new Map<string, Session>();
	modes = new Map<string, { plan: boolean; bypass: boolean }>();
	connected = true;
	offline = false;
	commands = [
		{ name: 'help', usage: 'help', description: 'List the commands' },
		{ name: 'models', usage: 'models [filter]', description: 'List the models' },
		{ name: 'plan', usage: 'plan on|off', description: 'Plan mode' }
	];

	constructor(private script: Script) {}

	async install(page: Page) {
		await page.route('**/api/**', (route) => this.handle(route));
	}

	private state() {
		return {
			provider: 'acme',
			model: this.connected ? 'acme/fast-1' : null,
			reasoningEffort: null,
			connected: this.connected,
			effortSupported: false,
			providers: [
				{ id: 'acme', connected: this.connected },
				{ id: 'other', connected: false }
			],
			commands: this.commands
		};
	}

	private session(id: string): Session {
		let s = this.sessions.get(id);
		if (!s) this.sessions.set(id, (s = { title: '', history: [], events: [], waiting: null, running: false }));
		return s;
	}

	private mode(id: string) {
		let m = this.modes.get(id);
		if (!m) this.modes.set(id, (m = { plan: false, bypass: false }));
		return m;
	}

	private async handle(route: Route) {
		const req = route.request();
		const url = new URL(req.url());
		const q = (k: string) => url.searchParams.get(k) ?? '';
		const body = (req.postDataJSON?.() ?? {}) as Record<string, unknown>;
		const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
		if (this.offline) return route.abort('connectionrefused');

		switch (url.pathname) {
			case '/api/state':
				return json(this.state());
			case '/api/conversations':
				if (req.method() === 'DELETE') {
					this.sessions.delete(q('session'));
					return json({ ok: true });
				}
				return json(
					[...this.sessions.entries()]
						.filter(([, s]) => s.title)
						.map(([id, s], i) => ({ id, title: s.title, updatedAt: Date.now() - i * 60_000, running: s.running }))
						.reverse()
				);
			case '/api/workspaces':
				return json({ projects: [{ path: 'D:\\work\\app', name: 'app', conversations: [] }], hidden: [] });
			case '/api/permissions':
				return json({ bypass: this.mode(q('session')).bypass, default: false });
			case '/api/mode':
				return json({ plan: this.mode(q('session')).plan, default: false });
			case '/api/history':
				return json(this.sessions.get(q('session'))?.history ?? []);
			case '/api/models':
				return json([
					{ id: 'acme/fast-1', name: 'Fast', contextLength: 128000, maxOutput: 8000, free: true, reasoning: false },
					{ id: 'acme/deep-2', name: 'Deep', contextLength: 200000, maxOutput: 32000, free: false, reasoning: true }
				]);
			case '/api/settings':
				return json(this.state());
			case '/api/trace':
				return json({
					traceId: q('id'),
					spans: [
						{ id: 'a', parentId: null, name: 'turn', startMicros: 0, durationMicros: 3_000_000, status: 'ok', attrs: {} },
						{ id: 'b', parentId: 'a', name: 'llm', startMicros: 10_000, durationMicros: 1_200_000, status: 'ok', attrs: { step: 1, promptTokens: 900 } },
						{ id: 'c', parentId: 'a', name: 'tool', startMicros: 1_300_000, durationMicros: 400_000, status: 'ok', attrs: { tool: 'read_file' } }
					]
				});
			case '/api/stop':
				return json({ ok: true });
			case '/api/approve':
				return this.decide(String(body.session), { kind: 'approve', body }, json);
			case '/api/plan':
				return this.decide(String(body.session), { kind: 'plan', body }, json);
			case '/api/answer':
				return this.decide(String(body.session), { kind: 'answer', body }, json);
			case '/api/command':
				return this.command(route, String(body.session), String(body.line));
			case '/api/chat':
				return this.chat(route, String(body.session), String(body.text));
			case '/api/stream':
				return this.stream(route, q('session'), Number(q('after')));
		}
		return json({ error: `no fake for ${url.pathname}` }, 404);
	}

	private decide(session: string, d: Decision, json: (data: unknown, status?: number) => Promise<void>) {
		const s = this.sessions.get(session);
		if (!s?.waiting) return json({ error: 'nothing to answer' }, 404);
		const go = s.waiting;
		s.waiting = null;
		go(d);
		return json({ ok: true });
	}

	private command(route: Route, session: string, line: string) {
		const [name, ...args] = line.replace(/^\//, '').split(/\s+/);
		if (name === 'plan') this.mode(session).plan = args[0] === 'on';
		if (name === 'bypass') this.mode(session).bypass = args[0] === 'on';
		const text = name === 'help' ? 'Commands: /help, /models, /plan' : name === 'plan' || name === 'bypass' ? `${name} ${args[0]}` : null;
		const events: Ev[] = text === null ? [{ event: 'unknown', data: { name } }] : [{ event: 'delta', data: { text } }, { event: 'done', data: {} }];
		return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse(events, 0) });
	}

	/** Starts the answer: the first segment now, the next ones once each decision comes. */
	private chat(route: Route, id: string, text: string) {
		const s = this.session(id);
		const plan = this.script(text);
		if (!plan) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([{ event: 'error', data: { message: 'no script' } }], 0) });
		s.title ||= text.slice(0, 60);
		s.running = true;
		s.history.push({ role: 'user', content: text, traceId: 'tr-' + (s.history.length + 1) });
		s.events = [{ event: 'start', data: { question: text, traceId: 'tr-' + s.history.length, startedAt: Date.now() } }];
		const run = (i: number, d?: Decision) => {
			s.events.push(...plan.segments[i](d));
			if (i + 1 < plan.segments.length) s.waiting = (next) => run(i + 1, next);
			else {
				s.running = false;
				s.history.push({ role: 'assistant', content: plan.answer });
			}
		};
		run(0);
		return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse(s.events, 0) });
	}

	/** Attaching again: the events after `after`, once there are any (held while the user decides). */
	private async stream(route: Route, id: string, after: number) {
		const s = this.sessions.get(id);
		// as the real server: nothing runs (a reload after the answer ended) → 204, and the page reads the history;
		// an answer that ended while the page was attaching again still gives its last events
		if (!s || (!s.running && (after === 0 || after >= s.events.length)) || after > 1e9) return route.fulfill({ status: 204, body: '' });
		for (let i = 0; i < 100 && s.events.length <= after; i++) await new Promise((r) => setTimeout(r, 100));
		return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse(s.events.slice(after), after) });
	}
}

/** Server-sent events with ids counting from `from + 1`. */
function sse(events: Ev[], from: number): string {
	return events.map((e, i) => `id: ${from + i + 1}\nevent: ${e.event}\ndata: ${JSON.stringify(e.data)}\n\n`).join('');
}

/** The scripts the specs use, by the question asked. */
export const scripts: Script = (question) => {
	const done = (extra: Record<string, unknown> = {}) => ({
		event: 'done',
		data: { model: 'acme/fast-1', usage: { promptTokens: 1200, completionTokens: 80, reasoningTokens: 0 }, toolCalls: 2, ...extra }
	});
	if (question.startsWith('Summarise')) {
		return {
			answer: 'The project is **Panic**.',
			segments: [
				() => [
					{ event: 'reasoning', data: { text: 'Look at the files first.' } },
					{ event: 'tool', data: { id: 't1', name: 'list_dir', arguments: '{"path":"."}', status: 'running' } },
					{ event: 'tool', data: { id: 't1', name: 'list_dir', arguments: '{"path":"."}', status: 'ok', content: 'README.md\nsrc/' } },
					{ event: 'tool', data: { id: 't2', name: 'read_file', arguments: '{"path":"README.md"}', status: 'running' } },
					{ event: 'tool', data: { id: 't2', name: 'read_file', arguments: '{"path":"README.md"}', status: 'ok', content: '# Panic' } },
					{ event: 'delta', data: { text: 'The project is **Panic**.' } },
					done({ traceId: 'tr-1' })
				]
			]
		};
	}
	if (question.startsWith('Run the tests')) {
		const call = { id: 'c1', name: 'exec', arguments: '{"command":"npm test"}' };
		return {
			answer: 'Tests ran.',
			segments: [
				() => [
					{ event: 'tool', data: { ...call, status: 'running' } },
					{ event: 'approve', data: { id: 'ap1', callId: 'c1', name: 'exec', arguments: call.arguments, risk: 'exec' } }
				],
				(d) =>
					d?.body.allow
						? [
								{ event: 'tool', data: { ...call, status: 'ok', content: '12 passed' } },
								{ event: 'delta', data: { text: 'All 12 tests pass.' } },
								done()
							]
						: [
								{ event: 'tool', data: { ...call, status: 'error', content: 'denied by the user' } },
								{ event: 'delta', data: { text: 'You denied running the tests.' } },
								done()
							]
			]
		};
	}
	if (question.startsWith('Plan')) {
		return {
			answer: 'Done.',
			segments: [
				() => [{ event: 'plan', data: { id: 'p1', plan: 'Add a **cache**.', items: ['Write the cache', 'Test it'], criteria: ['Tests pass'] } }],
				(d) => [
					{ event: 'tool', data: { id: 'x', name: 'exit_plan_mode', arguments: '{}', status: 'ok', content: d?.body.approve ? 'approved' : 'sent back' } },
					{ event: 'delta', data: { text: d?.body.approve ? 'Plan approved, starting.' : `Revising: ${d?.body.feedback}` } },
					done()
				]
			]
		};
	}
	if (question.startsWith('Ask me')) {
		return {
			answer: 'Ok.',
			segments: [
				() => [
					{
						event: 'question',
						data: { id: 'q1', questions: [{ question: 'Which database?', header: 'DB', multiSelect: false, options: [{ label: 'Postgres' }, { label: 'SQLite' }] }] }
					}
				],
				(d) => [
					{ event: 'tool', data: { id: 'y', name: 'ask_user', arguments: '{}', status: 'ok', content: JSON.stringify(d?.body.answers) } },
					{ event: 'delta', data: { text: `Using ${((d?.body.answers as { selected: string[] }[])[0]?.selected ?? [])[0]}.` } },
					done()
				]
			]
		};
	}
	if (question.startsWith('Work long')) {
		return { answer: 'Partial.', segments: [() => [{ event: 'delta', data: { text: 'Partial work.' } }, done({ stop: 'max_steps' })]] };
	}
	return { answer: 'Hello.', segments: [() => [{ event: 'delta', data: { text: 'Hello.' } }, done()]] };
};
