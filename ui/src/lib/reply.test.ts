import { describe, expect, it } from 'vitest';
import type { Message } from './chat.svelte';
import { askApproval, meta, toolStep } from './reply';

describe('toolStep', () => {
	it('adds a call when it starts and updates it when it ends', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"x"}', status: 'running' });
		toolStep(m, { id: 'b', name: 'time', arguments: '{}', status: 'running' });
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"x"}', status: 'error', content: 'not found: x' });
		expect(m.tools?.map((t) => [t.name, t.status, t.content])).toEqual([
			['read_file', 'error', 'not found: x'],
			['time', 'running', undefined]
		]);
	});

	it('keeps repeated calls with the same id apart', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 't', status: 'running' });
		toolStep(m, { id: 'a', name: 't', status: 'ok' });
		toolStep(m, { id: 'a', name: 't', status: 'running' });
		expect(m.tools?.map((t) => t.status)).toEqual(['ok', 'running']);
	});
});

describe('meta', () => {
	it('shows tools and why the bot stopped', () => {
		expect(meta({ model: 'm', usage: { promptTokens: 3, completionTokens: 2 }, toolCalls: 2, stop: 'done', finishReason: 'stop' }))
			.toBe('m · 5 tokens · 2 tools');
		expect(meta({ model: 'm', toolCalls: 1, stop: 'max_steps', finishReason: 'tool_calls' })).toBe('m · 1 tool · stopped: max steps');
		expect(meta({ model: 'm', finishReason: 'length' })).toBe('m · length');
		expect(meta({ model: 'm', stop: 'rejected', review: 'no tests run' })).toBe('m · stopped: rejected (no tests run)');
	});
});

describe('askApproval', () => {
	it('shows the question until the call it is about finishes', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 'write_file', arguments: '{}', status: 'running' });
		askApproval(m, { id: 'q1', callId: 'a', name: 'write_file', arguments: '{"path":"x"}', risk: 'write' });
		expect(m.approval).toEqual({ id: 'q1', callId: 'a', name: 'write_file', arguments: '{"path":"x"}', risk: 'write' });

		toolStep(m, { id: 'b', name: 'time', status: 'ok' });
		expect(m.approval?.id).toBe('q1');

		toolStep(m, { id: 'a', name: 'write_file', status: 'error', content: 'write_file: denied by the user' });
		expect(m.approval).toBeUndefined();
	});
});
