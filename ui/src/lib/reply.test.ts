import { describe, expect, it } from 'vitest';
import type { Message } from './chat.svelte';
import { activity, appendText, askApproval, createBatcher, duration, meta, review, toolStep, type Part } from './reply';

const steps = (m: Message) => (m.parts ?? []).flatMap((p) => (p.kind === 'tool' ? [p.step] : []));
const kinds = (m: Message) => (m.parts ?? []).map((p: Part) => p.kind);

describe('toolStep', () => {
	it('adds a call when it starts and updates it when it ends', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"x"}', status: 'running' });
		toolStep(m, { id: 'b', name: 'time', arguments: '{}', status: 'running' });
		toolStep(m, { id: 'a', name: 'read_file', arguments: '{"path":"x"}', status: 'error', content: 'not found: x' });
		expect(steps(m).map((t) => [t.name, t.status, t.content])).toEqual([
			['read_file', 'error', 'not found: x'],
			['time', 'running', undefined]
		]);
	});

	it('keeps repeated calls with the same id apart', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 't', status: 'running' });
		toolStep(m, { id: 'a', name: 't', status: 'ok' });
		toolStep(m, { id: 'a', name: 't', status: 'running' });
		expect(steps(m).map((t) => t.status)).toEqual(['ok', 'running']);
	});
});

describe('timeline', () => {
	it('keeps text and tool calls in the order they happened', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		appendText(m, 'Let me ');
		appendText(m, 'look.');
		toolStep(m, { id: 'a', name: 'read_file', status: 'running' });
		toolStep(m, { id: 'a', name: 'read_file', status: 'ok' });
		appendText(m, 'Done.');
		expect(kinds(m)).toEqual(['text', 'tool', 'text']);
		expect(m.parts?.[0]).toEqual({ kind: 'text', text: 'Let me look.' });
		expect(m.text).toBe('Let me look.Done.');
	});

	it('puts a review between the attempts it judged', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		appendText(m, 'first');
		review(m, { attempt: 1, pass: false, reason: 'no tests' });
		appendText(m, 'second');
		review(m, { attempt: 2, pass: true, reason: 'ok' });
		expect(kinds(m)).toEqual(['text', 'review', 'text', 'review']);
		expect(m.parts?.[1]).toEqual({ kind: 'review', attempt: 1, pass: false, reason: 'no tests' });
	});

	it('ignores empty deltas', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		appendText(m, '');
		expect(m.parts).toBeUndefined();
	});
});

describe('activity', () => {
	it('says what a streaming answer is doing', () => {
		const m: Message = { id: 1, role: 'assistant', text: '', parts: [] };
		expect(activity(m)).toBe('Thinking…');
		appendText(m, 'hi');
		expect(activity(m)).toBe('Writing…');
		toolStep(m, { id: 'a', name: 'write_file', status: 'running' });
		expect(activity(m)).toBe('Running write_file…');
		askApproval(m, { id: 'q', callId: 'a', name: 'write_file', risk: 'write' });
		expect(activity(m)).toBe('Waiting for approval');
		toolStep(m, { id: 'a', name: 'write_file', status: 'ok' });
		expect(activity(m)).toBe('Thinking…');
		review(m, { attempt: 1, pass: false, reason: 'x' });
		expect(activity(m)).toBe('Trying again…');
	});
});

describe('createBatcher', () => {
	it('applies the text of one tick at once, and flushes on demand', () => {
		const applied: string[] = [];
		const ticks: (() => void)[] = [];
		const b = createBatcher((t) => applied.push(t), (run) => ticks.push(run));
		b.push('a');
		b.push('b');
		expect(applied).toEqual([]);
		expect(ticks.length).toBe(1);
		ticks.shift()!();
		expect(applied).toEqual(['ab']);

		b.push('c');
		b.flush(); // before a tool event
		expect(applied).toEqual(['ab', 'c']);
		ticks.shift()!(); // the late tick has nothing left
		expect(applied).toEqual(['ab', 'c']);
	});
});

describe('duration', () => {
	it('reads well', () => {
		expect(duration(4200)).toBe('4s');
		expect(duration(65000)).toBe('1m 05s');
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
