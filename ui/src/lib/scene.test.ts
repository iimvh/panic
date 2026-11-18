import { describe, expect, it } from 'vitest';
import type { Message } from './chat.svelte';
import { toolStep } from './reply';
import { failure, lineDelta, liveThought, route, scene, stepLabel, todos, type SceneContext } from './scene';

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
});

describe('liveThought', () => {
	it('gives the newest line of the thought at the end of the answer', () => {
		const m = answer({ parts: [{ kind: 'reasoning', text: 'first idea\nsecond idea\n', startedAt: 0 }] });
		expect(liveThought(m)).toEqual({ text: 'second idea' });
	});

	it('has nothing once the thought ended or the answer stopped', () => {
		expect(liveThought(answer({ parts: [{ kind: 'reasoning', text: 'x', startedAt: 0, endedAt: 1 }] }))).toBeUndefined();
		expect(liveThought(answer({ streaming: false, parts: [{ kind: 'reasoning', text: 'x', startedAt: 0 }] }))).toBeUndefined();
		expect(liveThought(undefined)).toBeUndefined();
	});

	it('names a running sub-agent that thinks', () => {
		const m = answer();
		toolStep(m, { id: 'p', name: 'agent', arguments: '{}', status: 'running' });
		const step = m.parts![0].kind === 'tool' ? m.parts![0].step : undefined;
		step!.agent = { name: 'explorer', text: '', parts: [{ kind: 'reasoning', text: 'where is parse()?', startedAt: 0 }] };
		expect(liveThought(m)).toEqual({ text: 'where is parse()?', agent: 'explorer' });
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
