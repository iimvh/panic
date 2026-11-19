import { describe, expect, it } from 'vitest';
import { blocks, compact, effect, failure, lineDelta, stepLabel, summary, todos } from './summary';
import type { Part, ToolStep } from './reply';
import type { Message } from './types';

const step = (name: string, a: Record<string, unknown> = {}, status: ToolStep['status'] = 'ok'): ToolStep => ({
	id: `${name}-${Math.random()}`,
	name,
	arguments: JSON.stringify(a),
	status
});
const tool = (s: ToolStep): Part => ({ kind: 'tool', step: s });
const answer = (id: number, parts: Part[], over: Partial<Message> = {}): Message => ({ id, role: 'assistant', text: '', parts, ...over });

describe('effect', () => {
	it('classifies tools by what they do', () => {
		expect(effect('read_file')).toBe('read');
		expect(effect('edit_file')).toBe('write');
		expect(effect('exec')).toBe('command');
		expect(effect('mcp__github__search')).toBe('network');
		expect(effect('ask_user')).toBe('ask');
		expect(effect('agent')).toBe('agent');
		expect(effect('whatever')).toBe('other');
	});
});

describe('labels', () => {
	it('shortens calls', () => {
		expect(stepLabel({ name: 'read_file', arguments: '{"path":"ui/src/lib/reply.ts"}' })).toBe('reply.ts');
		expect(stepLabel({ name: 'exec', arguments: '{"command":"npm test","cwd":"ui"}' })).toBe('npm test');
		expect(stepLabel({ name: 'mcp__github__search_code', arguments: '{}' })).toBe('search_code');
		expect(stepLabel({ name: 'clock', arguments: '{}' })).toBe('');
	});

	it('reads exit codes and line counts', () => {
		expect(failure({ content: 'Exited with code 2' })).toBe('exit 2');
		expect(failure({ content: 'boom' })).toBe('failed');
		expect(lineDelta({ name: 'edit_file', arguments: '{"old_str":"a\\nb","new_str":"c"}' })).toEqual({ added: 1, removed: 2 });
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

describe('summary', () => {
	it('adds up the whole conversation, sub-agents included', () => {
		const sub = step('agent', { agent: 'reviewer' });
		sub.agent = { name: 'reviewer', text: '', parts: [tool(step('read_file', { path: 'b.ts' }))] };
		const messages: Message[] = [
			{ id: 1, role: 'user', text: 'hi' },
			answer(2, [tool(step('read_file', { path: 'a.ts' })), tool(step('edit_file', { path: 'a.ts', old_str: 'x', new_str: 'y\nz' }))], {
				usage: { promptTokens: 100, completionTokens: 20, reasoningTokens: 0 },
				startedAt: 0,
				endedAt: 4000
			}),
			answer(3, [tool(step('exec', { command: 'npm test' }, 'error')), tool(sub), tool(step('read_file', {}, 'drafting'))], { startedAt: 10_000, streaming: true })
		];
		const s = summary(messages, 12_000);
		expect(s).toMatchObject({ answers: 2, steps: 6, failed: 1, reads: 2, writes: 1, added: 2, removed: 1, commands: 1, agents: 1, tokens: 120, elapsed: 6000 });
		expect(s.files).toEqual([
			{ path: 'b.ts', write: false },
			{ path: 'a.ts', write: true }
		]);
	});

	it('says nothing it does not know', () => {
		expect(summary([], 0)).toMatchObject({ steps: 0, tokens: null, elapsed: null, files: [], todo: [] });
	});
});

describe('blocks', () => {
	it('groups consecutive tool calls', () => {
		const parts: Part[] = [{ kind: 'text', text: 'a' }, tool(step('x')), tool(step('y')), { kind: 'text', text: 'b' }, tool(step('z'))];
		expect(blocks(parts).map((b) => (b.kind === 'tools' ? b.steps.length : b.part.kind))).toEqual(['text', 2, 'text', 1]);
	});
});

describe('compact', () => {
	it('shortens large counts', () => {
		expect([compact(999), compact(1200), compact(12_345), compact(2_500_000)]).toEqual(['999', '1.2k', '12k', '2.5M']);
	});
});
