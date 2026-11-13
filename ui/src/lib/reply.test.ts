import { describe, expect, it } from 'vitest';
import type { Message } from './chat.svelte';
import { activity, appendText, createAnswer, askApproval, createBatcher, duration, endDrafts, fromHistory, meta, review, size, toolDelta, toolStep, type Part } from './reply';

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

describe('fromHistory', () => {
	it('folds a turn into one answer with its tool calls and reviews', () => {
		let n = 0;
		const out = fromHistory(
			[
				{ role: 'user', content: 'q' },
				{ role: 'assistant', content: 'Let me look.', toolCalls: [{ id: 'a', name: 'read_file', arguments: '{}' }, { id: 'b', name: 'nope', arguments: '{}' }] },
				{ role: 'tool', toolCallId: 'a', status: 'ok', content: 'text' },
				{ role: 'tool', toolCallId: 'b', status: 'error', content: 'unknown tool' },
				{ role: 'assistant', content: 'first' },
				{ role: 'review', reason: 'no tests' },
				{ role: 'assistant', content: 'second' },
				{ role: 'user', content: 'q2' },
				{ role: 'assistant', content: 'a2' }
			],
			() => ++n
		);
		expect(out.map((m) => m.role)).toEqual(['user', 'assistant', 'user', 'assistant']);
		expect(kinds(out[1])).toEqual(['text', 'tool', 'tool', 'text', 'review', 'text']);
		expect(steps(out[1]).map((t) => [t.name, t.status, t.content])).toEqual([
			['read_file', 'ok', 'text'],
			['nope', 'error', 'unknown tool']
		]);
		expect(out[1].parts?.[3]).toEqual({ kind: 'text', text: 'first' });
		expect(out.map((m) => m.id)).toEqual([1, 2, 3, 4]);
	});

	it('gives each answer the trace of its question', () => {
		const out = fromHistory(
			[
				{ role: 'user', content: 'q', traceId: 't1' },
				{ role: 'assistant', content: 'a' },
				{ role: 'user', content: 'untraced' },
				{ role: 'assistant', content: 'b' }
			],
			() => 0
		);
		expect(out.map((m) => m.traceId)).toEqual([undefined, 't1', undefined, undefined]);
	});
});

describe('toolDelta', () => {
	it('grows a draft until the call starts in its place', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		appendText(m, 'Writing it.');
		toolDelta(m, { index: 0, id: 'a', name: 'write_file', arguments: '{"path":' });
		toolDelta(m, { index: 1, id: 'b', name: 'time', arguments: '{}' });
		toolDelta(m, { index: 0, arguments: '"x"}' });
		expect(steps(m).map((t) => [t.name, t.status, t.arguments])).toEqual([
			['write_file', 'drafting', '{"path":"x"}'],
			['time', 'drafting', '{}']
		]);
		expect(activity(m)).toBe('Writing time call…');

		toolStep(m, { id: 'a', name: 'write_file', arguments: '{"path":"x"}', status: 'running' });
		toolStep(m, { id: 'a', name: 'write_file', status: 'ok' });
		toolStep(m, { id: 'b', name: 'time', arguments: '{}', status: 'running' });
		expect(kinds(m)).toEqual(['text', 'tool', 'tool']);
		expect(steps(m).map((t) => [t.name, t.status])).toEqual([
			['write_file', 'ok'],
			['time', 'running']
		]);
	});

	it('takes the first draft when the pieces had no id', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolDelta(m, { index: 0, name: 'time', arguments: '{}' });
		toolStep(m, { id: 'call_0', name: 'time', status: 'running' });
		expect(steps(m).map((t) => [t.id, t.status])).toEqual([['call_0', 'running']]);
	});

	it('marks drafts left at the end as not run', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolDelta(m, { index: 0, id: 'a', name: 'write_file', arguments: '{' });
		endDrafts(m);
		expect(steps(m).map((t) => [t.status, t.content])).toEqual([['error', 'not run']]);
	});

	it('sizes the arguments', () => {
		expect(size('abc')).toBe('3 B');
		expect(size('x'.repeat(3277))).toBe('3.2 KB');
	});
});

describe('createAnswer', () => {
	const now = (run: () => void) => run();

	it('builds the answer, remembers the last id and knows when it ended', () => {
		const reply: Message = { id: 2, role: 'assistant', text: '', parts: [] };
		const a = createAnswer(reply, now);
		a.onEvent({ event: 'start', data: { question: 'q', startedAt: 5 }, id: '1' });
		expect(reply.startedAt).toBeUndefined(); // a new ask keeps the page's own clock
		a.onEvent({ event: 'delta', data: { text: 'Hi' }, id: '2' });
		a.onEvent({ event: 'tool_delta', data: { index: 0, id: 'a', name: 'time', arguments: '{}' }, id: '3' });
		expect(a.ended()).toBe(false);
		a.onEvent({ event: 'done', data: { model: 'm' }, id: '4' });
		a.finish();
		expect(a.seq()).toBe(4);
		expect(a.ended()).toBe(true);
		expect(kinds(reply)).toEqual(['text', 'tool']);
		expect(steps(reply)[0].status).toBe('error'); // a draft that never ran
		expect(reply.meta).toBe('m');
	});

	it('learns the trace id from start, and from done when start was missed', () => {
		const reply: Message = { id: 2, role: 'assistant', text: '', parts: [] };
		const a = createAnswer(reply, now);
		a.onEvent({ event: 'start', data: { question: 'q', traceId: 'abc' }, id: '1' });
		expect(reply.traceId).toBe('abc');
		const later: Message = { id: 3, role: 'assistant', text: '', parts: [] };
		createAnswer(later, now).onEvent({ event: 'done', data: { model: 'm', traceId: 'def' }, id: '9' });
		expect(later.traceId).toBe('def');
	});

	it('fills in the question of a resumed answer, and replays do not go back', () => {
		const question: Message = { id: 1, role: 'user', text: '' };
		const reply: Message = { id: 2, role: 'assistant', text: '', parts: [] };
		const a = createAnswer(reply, now, question);
		a.onEvent({ event: 'start', data: { question: 'q', startedAt: 5 }, id: '1' });
		a.onEvent({ event: 'delta', data: { text: 'a' }, id: '3' });
		a.onEvent({ event: 'delta', data: { text: 'b' }, id: '2' });
		expect(question.text).toBe('q');
		expect(reply.startedAt).toBe(5);
		expect(a.seq()).toBe(3);
	});

	it('shows errors in the timeline', () => {
		const reply: Message = { id: 2, role: 'assistant', text: '', parts: [] };
		const a = createAnswer(reply, now);
		a.onEvent({ event: 'delta', data: { text: 'part' } });
		a.onEvent({ event: 'error', data: { message: 'boom' } });
		expect(reply.error).toBe(true);
		expect(reply.parts?.at(-1)).toEqual({ kind: 'error', text: 'boom' });
		expect(a.ended()).toBe(true);
	});
});
