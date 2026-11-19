import { describe, expect, it } from 'vitest';
import type { Message } from './chat.svelte';
import { toolStep } from './reply';
import { failure, lineDelta, liveThoughts, replayThoughts, route, scene, stepLabel, todos, type SceneContext } from './scene';

const ctx: SceneContext = { model: 'acme/fast-1', connected: true, mode: 'ask', now: 10_000 };
const answer = (over: Partial<Message> = {}): Message => ({ id: 1, role: 'assistant', text: '', parts: [], streaming: true, startedAt: 0, ...over });
const node = (s: ReturnType<typeof scene>, id: string) => s.nodes.find((n) => n.id === id);

describe('route', () => {
	it('sends each tool to its block with the right packet', () => {
		expect(route('read_file')).toMatchObject({ node: 'workspace', packet: 'read' });
		expect(route('edit_file')).toMatchObject({ node: 'workspace', packet: 'write' });
		expect(route('exec')).toMatchObject({ node: 'shell', packet: 'command' });
		expect(route('mcp__github__search')).toMatchObject({ node: 'mcp:github', kind: 'mcp', packet: 'network' });
		expect(route('todo_write')).toMatchObject({ node: 'todo', packet: 'write' });
		expect(route('whatever')).toMatchObject({ node: 'tool:whatever', kind: 'tool' });
	});
});

describe('scene', () => {
	it('draws only the setup before any answer, and says when no model is connected', () => {
		const s = scene(undefined, { ...ctx, connected: false });
		expect(node(s, 'home')?.state).toBe('ghost');
		expect(node(s, 'model')).toMatchObject({ state: 'error', pill: { text: 'NOT CONNECTED' } });
		expect(s.hud.elapsed).toBeNull();
	});

	it('works the model while nothing came back yet', () => {
		const s = scene(answer(), ctx);
		expect(node(s, 'model')).toMatchObject({ state: 'active', pill: { text: 'reading the prompt' } });
		expect(s.links.find((l) => l.id === 'home>model')?.state).toBe('active');
		expect(node(s, 'home')?.state).toBe('active');
		expect(s.hud.elapsed).toBe(10_000);
	});

	it('gives each block a spot in front of it to stand at', () => {
		const s = scene(answer(), ctx);
		const ws = node(s, 'workspace')!;
		expect(ws.spot.x).toBeLessThan(ws.x);
		expect(ws.spot.y).toBeGreaterThan(ws.y);
		expect(node(s, 'home')!.spot.x).toBe(node(s, 'home')!.x);
	});

	it('works the workspace through the gate on a read', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"src/app.ts"}', status: 'running' });
		const s = scene(m, ctx);
		expect(node(s, 'workspace')).toMatchObject({ state: 'active', pill: { text: 'app.ts' } });
		expect(s.links.find((l) => l.id === 'gate>workspace')?.state).toBe('active');
		expect(s.links.find((l) => l.id === 'home>gate')?.state).toBe('active');
		expect(s.hud.reads).toBe(1);
		expect(s.current).toBe(0);
	});

	it('marks a failed command on the shell with its exit code', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'exec', arguments: '{"command":"npm test"}', status: 'running' });
		toolStep(m, { id: 'a', name: 'exec', arguments: '{"command":"npm test"}', status: 'error', content: 'tests failed\nexit code 1' });
		const s = scene(m, ctx);
		expect(node(s, 'shell')).toMatchObject({ state: 'error', pill: { text: 'exit 1', tone: 'error' } });
		expect(s.links.find((l) => l.id === 'gate>shell')?.state).toBe('error');
		expect(s.hud.commands).toBe(1);
		toolStep(m, { id: 'b', name: 'exec', arguments: '{"command":"npm test"}', status: 'ok', content: 'exit code 0' });
		const again = scene(m, ctx);
		expect(node(again, 'shell')).toMatchObject({ state: 'idle', pill: { text: 'exit 0' } });
		expect(again.links.find((l) => l.id === 'gate>shell')?.state).toBe('idle');
	});

	it('draws no block for a sub-agent: its calls leave from home, its kind is explained', () => {
		const m = answer();
		toolStep(m, { id: 'p', name: 'agent', arguments: '{"agent":"explorer"}', status: 'running' });
		const only = scene(m, ctx);
		expect(only.nodes.some((n) => n.kind === 'agent')).toBe(false);
		expect(only.focus).toBe('agent');
		toolStep(m, { id: 'c', name: 'list_dir', arguments: '{"path":"."}', status: 'running', parent: 'p', agent: 'explorer' });
		const s = scene(m, ctx);
		expect(node(s, 'workspace')?.state).toBe('active');
		expect(s.links.find((l) => l.id === 'home>gate')?.state).toBe('active');
		expect(s.steps.map((x) => x.agent)).toEqual([undefined, 'explorer']);
	});

	it('marks the gate and its way to you while an approval waits', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'write_file', arguments: '{"path":"a.txt","content":"x"}', status: 'running' });
		m.approval = { id: 'q', callId: 'a', name: 'write_file', arguments: '', risk: 'write' };
		const s = scene(m, ctx);
		expect(node(s, 'gate')).toMatchObject({ state: 'waiting', pill: { text: 'WAITING FOR YOU' } });
		expect(s.links.find((l) => l.id === 'gate>you')?.state).toBe('waiting');
		expect(s.focus).toBe('gate');
	});

	it('gives each MCP server its own block', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'mcp__github__search', arguments: '{}', status: 'ok' });
		toolStep(m, { id: 'b', name: 'mcp__slack__post', arguments: '{}', status: 'ok' });
		const s = scene(m, ctx);
		expect(s.nodes.filter((n) => n.kind === 'mcp').map((n) => n.label)).toEqual(['github', 'slack']);
	});

	it('dims the shell and marks the gate read-only in plan mode', () => {
		const s = scene(answer(), { ...ctx, mode: 'plan' });
		expect(node(s, 'shell')?.state).toBe('dim');
		expect(node(s, 'gate')?.pill?.text).toBe('READ-ONLY');
	});

	it('replays a finished answer at a step: later calls are not on the map yet', () => {
		const m = answer({ streaming: false, endedAt: 4000, usage: { promptTokens: 100, completionTokens: 20, reasoningTokens: 0 } });
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"a"}', status: 'ok' });
		toolStep(m, { id: 'b', name: 'exec', arguments: '{"command":"ls"}', status: 'ok' });
		const s = scene(m, { ...ctx, atStep: 0 });
		expect(node(s, 'workspace')?.state).toBe('active');
		expect(node(s, 'shell')?.state).toBe('ghost');
		expect(s.steps).toHaveLength(2);
		expect(s.caption).toBe('reads a');
		expect(s.hud.tokens).toBeNull();
		const all = scene(m, ctx);
		expect(all.hud).toMatchObject({ elapsed: 4000, tokens: 120, steps: 2 });
	});

	it('keeps adding up over the answers of the conversation', () => {
		const usage = { promptTokens: 100, completionTokens: 20, reasoningTokens: 0 };
		const first = answer({ id: 1, streaming: false, endedAt: 3000, usage });
		toolStep(first, { id: 'a', name: 'read_file', arguments: '{"path":"a"}', status: 'ok' });
		toolStep(first, { id: 'b', name: 'write_file', arguments: '{"path":"b","content":"x"}', status: 'ok' });
		const second = answer({ id: 3, startedAt: 5000 });
		toolStep(second, { id: 'c', name: 'read_file', arguments: '{"path":"c"}', status: 'running' });
		const s = scene([first, second], ctx);
		// the workspace of the first answer is still there, with its counts
		expect(node(s, 'workspace')).toMatchObject({ state: 'active', badge: '+1 −0' });
		expect(s.steps.map((r) => r.turn)).toEqual([0, 0, 1]);
		expect(s.current).toBe(2);
		expect(s.hud).toMatchObject({ steps: 3, turnSteps: 1, turn: 2, turns: 2, reads: 2, writes: 1, tokens: 120, elapsed: 5000 });
		// replayed at a step of the first answer: later steps are not on the map, its own time shows
		const back = scene([first, second], { ...ctx, atStep: 0 });
		expect(back.hud).toMatchObject({ steps: 1, turnSteps: 1, turn: 1, writes: 0 });
		expect(back.caption).toBe('reads a');
	});

	it('works the model, not the block, while a call is still being written', () => {
		const m = answer();
		m.parts!.push({ kind: 'tool', step: { id: 'w', name: 'write_file', arguments: '{"path":"a.ts","con', status: 'drafting', draft: 0 } });
		const s = scene(m, ctx);
		expect(node(s, 'model')).toMatchObject({ state: 'active', pill: { text: 'drafting write_file call' } });
		expect(node(s, 'workspace')?.state).toBe('ghost');
		expect(node(s, 'gate')?.state).toBe('ghost');
		expect(s.focus).toBe('model');
		expect(s.hud.writes).toBe(0);
		// the rail still shows it, as the step happening
		expect(s.current).toBe(0);
	});
});

describe('replayThoughts', () => {
	const done = (over: Partial<Message> = {}) => answer({ streaming: false, ...over });
	it('gives the thought before the step shown, shared by the calls made at once', () => {
		const m = done({ parts: [{ kind: 'reasoning', text: 'a\nb\nc\nd', startedAt: 0, endedAt: 0 }] });
		toolStep(m, { id: 'x', name: 'read_file', arguments: '{}', status: 'ok' });
		toolStep(m, { id: 'y', name: 'read_file', arguments: '{}', status: 'ok' });
		m.parts!.push({ kind: 'reasoning', text: 'now edit', startedAt: 0, endedAt: 0 });
		toolStep(m, { id: 'z', name: 'edit_file', arguments: '{}', status: 'ok' });
		expect(replayThoughts([m], 0)).toEqual([{ id: 'panic', text: 'b\nc\nd', past: true }]);
		expect(replayThoughts([m], 1)).toEqual(replayThoughts([m], 0));
		expect(replayThoughts([m], 2)).toEqual([{ id: 'panic', text: 'now edit', past: true }]);
	});

	it('finds it across turns and in a sub-agent, by actor id', () => {
		const first = done({ id: 1 });
		toolStep(first, { id: 'x', name: 'read_file', arguments: '{}', status: 'ok' });
		const second = done({ id: 2 });
		toolStep(second, { id: 'p', name: 'agent', arguments: '{}', status: 'ok' });
		const call = second.parts![0];
		if (call.kind !== 'tool') throw new Error('no call');
		const inner = done({ id: 3, parts: [{ kind: 'reasoning', text: 'look at parse()', startedAt: 0, endedAt: 0 }] });
		toolStep(inner, { id: 'i', name: 'read_file', arguments: '{}', status: 'ok' });
		call.step.agent = { name: 'reviewer', text: '', parts: inner.parts ?? [] };
		expect(replayThoughts([first, second], 0)).toEqual([]);
		expect(replayThoughts([first, second], 1)).toEqual([]);
		expect(replayThoughts([first, second], 2)).toEqual([{ id: 'a:p', text: 'look at parse()', agent: 'reviewer', past: true }]);
		expect(replayThoughts([first, second], 9)).toEqual([]);
	});
});

describe('liveThoughts', () => {
	it('gives the newest line of the thought at the end of the answer', () => {
		const m = answer({ parts: [{ kind: 'reasoning', text: 'first idea\nsecond idea\n', startedAt: 0 }] });
		expect(liveThoughts(m)).toEqual([{ id: 'panic', text: 'second idea' }]);
	});

	it('has nothing once the thought ended or the answer stopped', () => {
		expect(liveThoughts(answer({ parts: [{ kind: 'reasoning', text: 'x', startedAt: 0, endedAt: 1 }] }))).toEqual([]);
		expect(liveThoughts(answer({ streaming: false, parts: [{ kind: 'reasoning', text: 'x', startedAt: 0 }] }))).toEqual([]);
		expect(liveThoughts(undefined)).toEqual([]);
	});

	it('gives each running sub-agent that thinks its own thought, by actor id', () => {
		const m = answer();
		toolStep(m, { id: 'p', name: 'agent', arguments: '{}', status: 'running' });
		toolStep(m, { id: 'q', name: 'agent', arguments: '{}', status: 'running' });
		toolStep(m, { id: 'r', name: 'agent', arguments: '{}', status: 'running' });
		const steps = m.parts!.flatMap((p) => (p.kind === 'tool' ? [p.step] : []));
		steps[0].agent = { name: 'reviewer', text: '', parts: [{ kind: 'reasoning', text: 'where is parse()?', startedAt: 0 }] };
		steps[1].agent = { name: 'reviewer', text: '', parts: [{ kind: 'reasoning', text: 'aria labels?', startedAt: 0 }] };
		steps[2].agent = { name: 'done', text: '', parts: [{ kind: 'reasoning', text: 'over', startedAt: 0, endedAt: 1 }] };
		expect(liveThoughts(m)).toEqual([
			{ id: 'a:p', text: 'where is parse()?', agent: 'reviewer' },
			{ id: 'a:q', text: 'aria labels?', agent: 'reviewer' }
		]);
	});
});

describe('todos', () => {
	it('takes the checklist of the last todo_write', () => {
		const items = (t: string) => JSON.stringify({ items: [{ text: t, status: 'in_progress' }, { text: 'b', status: 'odd' }] });
		expect(todos([{ name: 'todo_write', arguments: items('a') }, { name: 'todo_write', arguments: items('c') }, { name: 'exec', arguments: '{}' }])).toEqual([
			{ text: 'c', status: 'in_progress' },
			{ text: 'b', status: 'pending' }
		]);
	});
});

describe('labels', () => {
	it('shortens calls for the rail', () => {
		expect(stepLabel({ name: 'read_file', arguments: '{"path":"ui/src/lib/reply.ts"}' })).toBe('reply.ts');
		expect(stepLabel({ name: 'exec', arguments: '{"command":"npm test","cwd":"ui"}' })).toBe('npm test');
		expect(stepLabel({ name: 'mcp__github__search_code', arguments: '{}' })).toBe('search_code');
	});

	it('reads exit codes and line counts', () => {
		expect(failure({ name: 'exec', content: 'Exited with code 2' })).toBe('exit 2');
		expect(failure({ name: 'exec', content: 'boom' })).toBe('failed');
		expect(lineDelta({ name: 'edit_file', arguments: '{"old_str":"a\\nb","new_str":"c"}' })).toEqual({ added: 1, removed: 2 });
	});
});
