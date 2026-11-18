import { describe, expect, it } from 'vitest';
import type { Message } from './chat.svelte';
import { canContinue, findStep, shellCommand, activity, agentText, appendReasoning, appendText, reasoningLabel, clearApproval, createAnswer, askApproval, askPlan, askUser, ASK_USER_TOOL, EXIT_PLAN_TOOL, createBatcher, duration, endDrafts, fromHistory, meta, review, size, thoughtTail, toolDelta, toolStep, type Part } from './reply';

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

	it('keeps a failed review apart from a verdict', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		review(m, { attempt: 1, error: 'the reviewer gave no verdict: maybe' });
		expect(m.parts?.[0]).toEqual({ kind: 'review', attempt: 1, pass: true, reason: '', error: 'the reviewer gave no verdict: maybe' });
		expect(activity(m)).toBe('Waiting for the model…');
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
		expect(activity(m)).toBe('Waiting for the model…');
		appendText(m, 'hi');
		expect(activity(m)).toBe('Writing…');
		toolStep(m, { id: 'a', name: 'write_file', status: 'running' });
		expect(activity(m)).toBe('Running write_file…');
		askApproval(m, { id: 'q', callId: 'a', name: 'write_file', risk: 'write' });
		expect(activity(m)).toBe('Waiting for approval');
		toolStep(m, { id: 'a', name: 'write_file', status: 'ok' });
		expect(activity(m)).toBe('Waiting for the model…');
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

describe('askPlan', () => {
	it('shows the plan until the exit_plan_mode call finishes', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'p', name: EXIT_PLAN_TOOL, arguments: '{}', status: 'running' });
		askPlan(m, { id: 'x', plan: '# Plan', items: ['look', 'fix'], criteria: ['fixed'] });
		expect(m.plan).toEqual({ id: 'x', plan: '# Plan', items: ['look', 'fix'], criteria: ['fixed'] });
		expect(activity(m)).toBe('Waiting for plan approval');

		toolStep(m, { id: 'b', name: 'read_file', status: 'ok' });
		expect(m.plan?.id).toBe('x');
		// a sub-agent's call of the same name does not answer it
		toolStep(m, { id: 'c', name: EXIT_PLAN_TOOL, status: 'error', parent: 'p', agent: 'explore' });
		expect(m.plan?.id).toBe('x');

		toolStep(m, { id: 'p', name: EXIT_PLAN_TOOL, status: 'ok', content: 'approved' });
		expect(m.plan).toBeUndefined();
	});

	it('comes from the plan event of an answer, missing lists read as empty', () => {
		const reply: Message = { id: 2, role: 'assistant', text: '', parts: [] };
		createAnswer(reply, (run) => run()).onEvent({ event: 'plan', data: { id: 'y', plan: 'p' }, id: '1' });
		expect(reply.plan).toEqual({ id: 'y', plan: 'p', items: [], criteria: [] });
	});
});

describe('canContinue', () => {
	it('offers to continue an answer a hard limit stopped', () => {
		const reply: Message = { id: 2, role: 'assistant', text: '', parts: [], streaming: true };
		const a = createAnswer(reply, (run) => run());
		a.onEvent({ event: 'done', data: { model: 'm', stop: 'max_steps' }, id: '1' });
		expect(canContinue(reply)).toBe(false); // still streaming
		reply.streaming = false;
		expect(reply.stop).toBe('max_steps');
		expect(canContinue(reply)).toBe(true);
		expect(canContinue({ ...reply, stop: 'budget' })).toBe(true);
		expect(canContinue({ ...reply, stop: 'done' })).toBe(false);
		expect(canContinue({ ...reply, stop: 'rejected' })).toBe(false);
	});
});

describe('shellCommand', () => {
	it('shows an exec call as a command line', () => {
		expect(shellCommand('{"command":"npm run dev","cwd":"web","background":true}')).toBe('$ npm run dev   (in web)   [background]');
		expect(shellCommand('{"command":"ls"}')).toBe('$ ls');
		expect(shellCommand('{"path":"x"}')).toBeNull();
		expect(shellCommand('{oops')).toBeNull();
	});
});

describe('askUser', () => {
	it('shows the questions until the ask_user call finishes', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'q', name: ASK_USER_TOOL, arguments: '{}', status: 'running' });
		askUser(m, {
			id: 'x',
			questions: [{ question: 'Which cache?', header: 'Cache', multiSelect: false, options: [{ label: 'Redis', description: 'shared' }, { label: 'Memory' }] }]
		});
		expect(m.question).toEqual({
			id: 'x',
			questions: [{ question: 'Which cache?', header: 'Cache', multiSelect: false, options: [{ label: 'Redis', description: 'shared' }, { label: 'Memory', description: '' }] }]
		});
		expect(activity(m)).toBe('Waiting for your answer');

		toolStep(m, { id: 'q2', name: ASK_USER_TOOL, status: 'error', parent: 'a', agent: 'explore' });
		expect(m.question?.id).toBe('x');
		toolStep(m, { id: 'q', name: ASK_USER_TOOL, status: 'ok', content: 'The user answered: …' });
		expect(m.question).toBeUndefined();
	});

	it('comes from the question event of an answer', () => {
		const reply: Message = { id: 2, role: 'assistant', text: '', parts: [] };
		createAnswer(reply, (run) => run()).onEvent({ event: 'question', data: { id: 'y', questions: [{ question: 'q?', multiSelect: true }] }, id: '1' });
		expect(reply.question).toEqual({ id: 'y', questions: [{ question: 'q?', header: '', multiSelect: true, options: [] }] });
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

describe('sub-agents', () => {
	it('nests a sub-agent’s text and calls under the call that runs it', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 'agent', arguments: '{"agent":"explore"}', status: 'running' });
		agentText(m, { parent: 'a', agent: 'explore', text: 'Looking ' });
		agentText(m, { parent: 'a', agent: 'explore', text: 'around.' });
		toolStep(m, { id: 'x', name: 'read_file', status: 'running', parent: 'a', agent: 'explore' });
		toolStep(m, { id: 'x', name: 'read_file', status: 'ok', content: 'text', parent: 'a', agent: 'explore' });
		toolStep(m, { id: 'a', name: 'agent', arguments: '{"agent":"explore"}', status: 'ok', content: 'found it' });

		expect(kinds(m)).toEqual(['tool']);
		const agent = steps(m)[0].agent!;
		expect(agent.name).toBe('explore');
		expect(agent.text).toBe('Looking around.');
		expect(agent.parts.map((p) => p.kind)).toEqual(['text', 'tool']);
		expect(agent.parts[1].kind === 'tool' && agent.parts[1].step.status).toBe('ok');
		expect(steps(m)[0].content).toBe('found it');
		expect(m.text).toBe(''); // a sub-agent’s text is not the answer’s
	});

	it('says what a sub-agent is doing and how many tools run at once', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 'agent', status: 'running' });
		agentText(m, { parent: 'a', agent: 'explore', text: 'hm' });
		expect(activity(m)).toBe('explore: working…');
		toolStep(m, { id: 'x', name: 'list_dir', status: 'running', parent: 'a', agent: 'explore' });
		expect(activity(m)).toBe('explore: running list_dir…');
		toolStep(m, { id: 'b', name: 'agent', status: 'running' });
		expect(activity(m)).toBe('Running 2 tools…');
	});

	it('batches each sub-agent’s text per frame and keeps parallel ones apart', () => {
		const m: Message = { id: 1, role: 'assistant', text: '', parts: [] };
		const frames: (() => void)[] = [];
		const answer = createAnswer(m, (run) => frames.push(run));
		answer.onEvent({ event: 'tool', data: { id: 'a', name: 'agent', status: 'running' } });
		answer.onEvent({ event: 'tool', data: { id: 'b', name: 'agent', status: 'running' } });
		answer.onEvent({ event: 'agent_delta', data: { parent: 'a', agent: 'one', text: 'x' } });
		answer.onEvent({ event: 'agent_delta', data: { parent: 'b', agent: 'two', text: 'y' } });
		answer.onEvent({ event: 'agent_delta', data: { parent: 'a', agent: 'one', text: 'z' } });
		expect(steps(m)[0].agent).toBeUndefined();
		frames.splice(0).forEach((run) => run());
		expect(steps(m).map((s) => [s.agent?.name, s.agent?.text])).toEqual([['one', 'xz'], ['two', 'y']]);
	});

	it('queues questions asked together and shows the next when one is answered', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 'agent', status: 'running' });
		toolStep(m, { id: 'x', name: 'write_file', status: 'running', parent: 'a', agent: 'explore' });
		toolStep(m, { id: 'y', name: 'write_file', status: 'running' });
		askApproval(m, { id: 'q1', callId: 'x', name: 'write_file', risk: 'write', agent: 'explore' });
		askApproval(m, { id: 'q2', callId: 'y', name: 'write_file', risk: 'write' });
		expect(m.approval?.agent).toBe('explore');
		expect(m.waiting?.map((a) => a.id)).toEqual(['q2']);

		toolStep(m, { id: 'x', name: 'write_file', status: 'ok', parent: 'a', agent: 'explore' });
		expect(m.approval?.id).toBe('q2');
		expect(m.waiting).toBeUndefined();
		clearApproval(m, (a) => a.id === 'q2');
		expect(m.approval).toBeUndefined();
	});
});

describe('reasoning', () => {
	const kinds = (m: Message) => (m.parts ?? []).map((p) => p.kind);

	it('shows what the model thinks apart from its text, and says Thinking only while it thinks', () => {
		const m: Message = { id: 1, role: 'assistant', text: '', parts: [] };
		let t = 1000;
		const a = createAnswer(m, (run) => run(), undefined, () => t);
		expect(activity(m)).toBe('Waiting for the model…');
		a.onEvent({ event: 'reasoning', data: { text: '17*23 ' } });
		a.onEvent({ event: 'reasoning', data: { text: 'is 391' } });
		expect(activity(m)).toBe('Thinking…');
		t = 5000;
		a.onEvent({ event: 'delta', data: { text: '391' } });
		expect(kinds(m)).toEqual(['reasoning', 'text']);
		expect(m.parts?.[0]).toEqual({ kind: 'reasoning', text: '17*23 is 391', startedAt: 1000, endedAt: 5000 });
		expect(m.text).toBe('391'); // the thinking is not the answer
		expect(activity(m)).toBe('Writing…');
	});

	it('starts a new block when the model thinks again after a tool, and ends one when the stream ends', () => {
		const m: Message = { id: 1, role: 'assistant', text: '', parts: [] };
		const a = createAnswer(m, (run) => run(), undefined, () => 0);
		a.onEvent({ event: 'reasoning', data: { text: 'look first' } });
		a.onEvent({ event: 'tool', data: { id: 'x', name: 'read_file', status: 'running' } });
		a.onEvent({ event: 'tool', data: { id: 'x', name: 'read_file', status: 'ok' } });
		expect(activity(m)).toBe('Waiting for the model…');
		a.onEvent({ event: 'reasoning', data: { text: 'now answer' } });
		a.finish();
		expect(kinds(m)).toEqual(['reasoning', 'tool', 'reasoning']);
		expect((m.parts ?? []).every((p) => p.kind !== 'reasoning' || p.endedAt === 0)).toBe(true);
	});

	it('keeps a sub-agent’s thinking apart from its text', () => {
		const m: Message = { id: 1, role: 'assistant', text: '', parts: [] };
		const a = createAnswer(m, (run) => run(), undefined, () => 0);
		a.onEvent({ event: 'tool', data: { id: 'a', name: 'agent', status: 'running' } });
		a.onEvent({ event: 'agent_delta', data: { parent: 'a', agent: 'explore', text: 'hmm', reasoning: true } });
		a.onEvent({ event: 'agent_delta', data: { parent: 'a', agent: 'explore', text: 'Found it.' } });
		const agent = m.parts?.[0].kind === 'tool' ? m.parts[0].step.agent : undefined;
		expect(agent?.parts.map((p) => p.kind)).toEqual(['reasoning', 'text']);
		expect(agent?.text).toBe('Found it.');
	});

	it('labels a block by how long it took', () => {
		const m: Message = { id: 1, role: 'assistant', text: '' };
		appendReasoning(m, 'x', 0);
		const p = m.parts![0];
		if (p.kind !== 'reasoning') throw new Error('not reasoning');
		expect(reasoningLabel(p, 500)).toBe('Thinking');
		expect(reasoningLabel(p, 12_000)).toBe('Thinking · 12s');
		p.endedAt = 65_000;
		expect(reasoningLabel(p, 99_000)).toBe('Thought for 1m 05s');
	});

	it('counts thinking tokens in the meta line', () => {
		expect(meta({ model: 'm', usage: { promptTokens: 10, completionTokens: 40, reasoningTokens: 30 } })).toBe(
			'm · 50 tokens (30 thinking)'
		);
	});
});

describe('thoughtTail', () => {
	it('keeps the last non-empty lines', () => {
		expect(thoughtTail('', 5, 600)).toBe('');
		expect(thoughtTail('one', 5, 600)).toBe('one');
		expect(thoughtTail('a\n\nb\nc  \n\nd\n', 2, 600)).toBe('c\nd');
	});

	it('cuts the start of a long tail', () => {
		const t = thoughtTail('x'.repeat(50) + ' end', 1, 10);
		expect(t).toBe('…xxxxx end');
		expect(t.length).toBe(10);
	});
});

describe('findStep', () => {
	it('finds a call by its answer and id, a sub-agent’s too, after it ended', () => {
		const m: Message = { id: 7, role: 'assistant', text: '' };
		toolStep(m, { id: 'a', name: 'agent', status: 'running' });
		toolStep(m, { id: 'x', name: 'read_file', status: 'running', parent: 'a', agent: 'explore' });
		toolStep(m, { id: 'x', name: 'read_file', status: 'ok', content: 'text', parent: 'a', agent: 'explore' });
		const other: Message = { id: 8, role: 'assistant', text: '' };
		toolStep(other, { id: 'x', name: 'exec', status: 'ok' });
		expect(findStep([m, other], 7, 'x')?.status).toBe('ok');
		expect(findStep([m, other], 7, 'x')?.name).toBe('read_file');
		expect(findStep([m, other], 8, 'x')?.name).toBe('exec');
		expect(findStep([m, other], 9, 'x')).toBeUndefined();
	});
});
