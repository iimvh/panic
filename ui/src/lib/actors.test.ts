import { describe, expect, it } from 'vitest';
import { actors, layout } from './actors';
import type { Message } from './chat.svelte';
import { toolStep } from './reply';
import { scene, type SceneContext } from './scene';

const ctx: SceneContext = { model: 'acme/fast-1', connected: true, mode: 'ask', now: 0 };
const answer = (over: Partial<Message> = {}): Message => ({ id: 1, role: 'assistant', text: '', parts: [], streaming: true, startedAt: 0, ...over });
const main = (m: Message, atStep?: number) => actors(m, { atStep }).find((a) => a.kind === 'main')!;

describe('actors', () => {
	it('keeps Panic at home with no answer, and when the answer is done', () => {
		expect(actors(undefined, {})).toEqual([{ id: 'panic', kind: 'main', target: 'home' }]);
		expect(main(answer({ streaming: false }))).toMatchObject({ target: 'home' });
	});

	it('takes Panic to the model while it thinks, with the prompt', () => {
		expect(main(answer())).toMatchObject({ target: 'model', carry: 'prompt', thinking: false });
		const m = answer({ parts: [{ kind: 'reasoning', text: 'hm', startedAt: 0 }] });
		expect(main(m)).toMatchObject({ target: 'model', thinking: true });
	});

	it('takes Panic to the workspace with a read packet', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"a.ts"}', status: 'running' });
		expect(main(m)).toMatchObject({ target: 'workspace', carry: 'read' });
	});

	it('stops Panic at the gate while an approval waits', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'exec', arguments: '{"command":"rm x"}', status: 'running' });
		m.approval = { id: 'q', callId: 'a', name: 'exec', arguments: '', risk: 'write' };
		expect(main(m)).toMatchObject({ target: 'gate', carry: 'command', waiting: true, note: 'rm x' });
	});

	it('sends Panic to you for a plan or a question', () => {
		const m = answer({ plan: { id: 'p', plan: 'x' } as Message['plan'] });
		expect(main(m)).toMatchObject({ target: 'you', waiting: true });
	});

	it('sends a clone for each more call running at once', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"a"}', status: 'running' });
		toolStep(m, { id: 'b', name: 'exec', arguments: '{"command":"ls"}', status: 'running' });
		const cast = actors(m, {});
		expect(cast).toHaveLength(2);
		expect(cast[1]).toMatchObject({ id: 'p:b', kind: 'parallel', target: 'shell', carry: 'command' });
		toolStep(m, { id: 'b', name: 'exec', arguments: '{"command":"ls"}', status: 'ok', content: 'exit code 0' });
		expect(actors(m, {}).map((a) => a.id)).toEqual(['panic']);
	});

	it('sends a named clone for a sub-agent, to its own call or to the model, and lets it go when it ends', () => {
		const m = answer();
		toolStep(m, { id: 's', name: 'agent', arguments: '{"agent":"explorer"}', status: 'running' });
		let cast = actors(m, {});
		expect(cast[0]).toMatchObject({ target: 'home' });
		expect(cast[1]).toMatchObject({ id: 'a:s', kind: 'agent', name: 'explorer', target: 'model' });
		toolStep(m, { id: 'c', name: 'list_dir', arguments: '{"path":"src"}', status: 'running', parent: 's', agent: 'explorer' });
		cast = actors(m, {});
		expect(cast[1]).toMatchObject({ id: 'a:s', target: 'workspace', carry: 'read' });
		toolStep(m, { id: 's', name: 'agent', arguments: '{}', status: 'ok', content: 'done' });
		expect(actors(m, {}).map((a) => a.id)).toEqual(['panic']);
	});

	it('replays where everyone was at a step', () => {
		const m = answer({ streaming: false });
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"a"}', status: 'ok' });
		toolStep(m, { id: 's', name: 'agent', arguments: '{"agent":"explorer"}', status: 'ok' });
		toolStep(m, { id: 'c', name: 'exec', arguments: '{"command":"ls"}', status: 'ok', parent: 's', agent: 'explorer' });
		expect(actors(m, { atStep: 0 })).toEqual([{ id: 'panic', kind: 'main', target: 'workspace', carry: 'read' }]);
		const at2 = actors(m, { atStep: 2 });
		expect(at2[0]).toMatchObject({ target: 'home' });
		expect(at2[1]).toMatchObject({ id: 'a:s', target: 'shell', carry: 'command' });
	});
});

describe('layout', () => {
	it('walks to the shell through the gate, and finds each block’s spot', () => {
		const ways = layout(scene(answer(), ctx));
		expect(ways.path('home', 'shell')).toEqual(['home', 'gate', 'shell']);
		expect(ways.path('workspace', 'model')).toEqual(['workspace', 'gate', 'home', 'model']);
		expect(ways.path('home', 'home')).toEqual(['home']);
		expect(ways.path('home', 'nowhere')).toEqual(['nowhere']);
		expect(ways.spot('gate')).toBeDefined();
	});

	it('does not take the dashed way of someone waiting for you', () => {
		const m = answer();
		toolStep(m, { id: 'a', name: 'write_file', arguments: '{"path":"a"}', status: 'running' });
		m.approval = { id: 'q', callId: 'a', name: 'write_file', arguments: '', risk: 'write' };
		expect(layout(scene(m, ctx)).path('workspace', 'you')).toEqual(['workspace', 'gate', 'home', 'you']);
	});
});
