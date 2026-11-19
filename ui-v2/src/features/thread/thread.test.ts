import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { goto } from '$app/navigation';
import type { Reasoning, ToolStep } from '$lib/core/reply';
import { chat, type Message } from '$lib/state/chat.svelte';
import MessageView from './MessageView.svelte';
import QuestionCard from './QuestionCard.svelte';
import ReasoningPart from './ReasoningPart.svelte';
import ToolGroup from './ToolGroup.svelte';
import { setUrl } from '../../test-setup';

beforeEach(() => setUrl('/c/abc'));

const step = (id: string, status: ToolStep['status'] = 'ok', name = 'read_file', args: Record<string, unknown> = { path: `src/${id}.ts` }): ToolStep => ({
	id,
	name,
	arguments: JSON.stringify(args),
	status
});

describe('ToolGroup', () => {
	it('shows a single call as it is', () => {
		render(ToolGroup, { steps: [step('a')] });
		expect(screen.getByText('read_file')).toBeTruthy();
		expect(screen.getByText('a.ts')).toBeTruthy();
		expect(screen.queryByText(/tool calls/)).toBeNull();
	});

	it('folds finished calls and says what failed', async () => {
		render(ToolGroup, { steps: [step('a'), step('b', 'error'), step('c')] });
		const toggle = screen.getByRole('button', { name: /3 tool calls/ });
		expect(toggle.textContent).toContain('1 failed');
		expect(toggle.getAttribute('aria-expanded')).toBe('false');
		expect(screen.queryByText('a.ts')).toBeNull();
		await fireEvent.click(toggle);
		expect(screen.getByText('a.ts')).toBeTruthy();
	});

	it('stays open while a call runs', () => {
		render(ToolGroup, { steps: [step('a'), step('b', 'running')] });
		expect(screen.getByRole('button', { name: /2 tool calls/ }).getAttribute('aria-expanded')).toBe('true');
	});

	it('opens a call in the inspector', async () => {
		render(ToolGroup, { steps: [step('x1')] });
		await fireEvent.click(screen.getByRole('button', { name: /read_file/ }));
		expect(vi.mocked(goto)).toHaveBeenLastCalledWith(expect.objectContaining({ search: '?panel=step&step=x1' }), expect.anything());
	});
});

describe('file changes', () => {
	const edit = (diff?: string): ToolStep => ({
		...step('e1', 'ok', 'edit_file', { path: 'src/app.ts', old_str: 'two', new_str: '2' }),
		content: 'edited src/app.ts: replaced 1 occurrence (first at line 2)',
		diff
	});

	it('shows what an edit changed, with line numbers, under the call', () => {
		render(ToolGroup, { steps: [edit('@@ -1,3 +1,3 @@\n one\n-two\n+2\n three\n')] });
		expect(screen.getByRole('button', { name: /Added 1 line, removed 1 line/ }).getAttribute('aria-expanded')).toBe('true');
		const table = screen.getByRole('table', { name: 'Changes to app.ts' });
		const rows = [...table.querySelectorAll('.row')].map((r) => [...r.querySelectorAll('[role=cell]')].map((c) => c.textContent));
		expect(rows).toEqual([
			['1', '1', ' ', 'one'],
			['2', '', '-', 'two'],
			['', '2', '+', '2'],
			['3', '3', ' ', 'three']
		]);
	});

	it('folds the change on demand', async () => {
		render(ToolGroup, { steps: [edit('@@ -1,1 +1,1 @@\n-two\n+2\n')] });
		await fireEvent.click(screen.getByRole('button', { name: /Added 1 line/ }));
		expect(screen.queryByRole('table')).toBeNull();
	});

	it('rebuilds the change from the arguments when the server sent none', () => {
		render(ToolGroup, { steps: [edit()] });
		expect(screen.getByRole('table').textContent).toContain('two');
		expect(screen.getByRole('button', { name: /Added 1 line, removed 1 line/ })).toBeTruthy();
	});

	it('shows the first lines of a long change, the rest on demand', async () => {
		const content = Array.from({ length: 40 }, (_, i) => `line ${i + 1}`).join('\n');
		render(ToolGroup, { steps: [{ ...step('w1', 'ok', 'write_file', { path: 'big.txt', content }), content: 'wrote' }] });
		expect(screen.getAllByRole('row')).toHaveLength(20);
		await fireEvent.click(screen.getByRole('button', { name: 'Show 20 more lines' }));
		expect(screen.getAllByRole('row')).toHaveLength(40);
	});

	it('shows no change for a failed call', () => {
		render(ToolGroup, { steps: [{ ...edit('@@ -1,1 +1,1 @@\n-a\n+b\n'), status: 'error' }] });
		expect(screen.queryByRole('table')).toBeNull();
	});

	it('says what a folded group changed', () => {
		render(ToolGroup, { steps: [step('a'), edit('@@ -1,1 +1,2 @@\n-a\n+b\n+c\n')] });
		expect(screen.getByRole('button', { name: /2 tool calls/ }).textContent).toContain('+2 −1');
	});
});

describe('ReasoningPart', () => {
	it('shimmers "Thinking" while the thought runs, not after', async () => {
		const part: Reasoning = { kind: 'reasoning', text: 'hmm', startedAt: 0 };
		const { rerender } = render(ReasoningPart, { part });
		expect(document.querySelector('.ui-shimmer')?.textContent).toBe('Thinking');
		await rerender({ part: { ...part, endedAt: 2000 } });
		expect(document.querySelector('.ui-shimmer')).toBeNull();
		expect(screen.getByRole('button', { name: 'Thought for 2s' })).toBeTruthy();
	});

	it('is one folded line until opened', async () => {
		const part: Reasoning = { kind: 'reasoning', text: 'secret plan', startedAt: 0, endedAt: 4200 };
		render(ReasoningPart, { part });
		const toggle = screen.getByRole('button', { name: 'Thought for 4s' });
		expect(screen.queryByText('secret plan')).toBeNull();
		await fireEvent.click(toggle);
		expect(screen.getByText('secret plan')).toBeTruthy();
	});
});

describe('MessageView', () => {
	const answer = (over: Partial<Message> = {}): Message => ({ id: 1, role: 'assistant', text: '', parts: [{ kind: 'text', text: 'Done.' }], ...over });

	it('shows what an answer cost in one quiet line, linked to its trace', async () => {
		render(MessageView, { message: answer({ meta: 'm · 1.2k tokens · 2 tools', startedAt: 0, endedAt: 3000, traceId: 't9' }) });
		const meta = screen.getByRole('button', { name: 'm · 1.2k tokens · 2 tools · 3s' });
		await fireEvent.click(meta);
		expect(vi.mocked(goto)).toHaveBeenLastCalledWith(expect.objectContaining({ search: '?panel=trace&trace=t9' }), expect.anything());
	});

	it('says only a rejected review', () => {
		render(MessageView, {
			message: answer({
				parts: [
					{ kind: 'review', attempt: 1, pass: true, reason: '' },
					{ kind: 'review', attempt: 2, pass: false, reason: 'tests fail' }
				]
			})
		});
		expect(screen.queryByText(/Review passed/)).toBeNull();
		expect(screen.getByText(/tests fail/)).toBeTruthy();
	});

	it('shows progress while streaming and no meta line', () => {
		render(MessageView, { message: answer({ streaming: true, startedAt: Date.now(), parts: [{ kind: 'tool', step: step('a', 'running') }] }) });
		expect(screen.getByText('Running read_file…')).toBeTruthy();
		expect(document.querySelector('.meta')).toBeNull();
	});

	it('shimmers the progress line while the system works, and only the progress line', () => {
		render(MessageView, { message: answer({ streaming: true, startedAt: Date.now(), parts: [{ kind: 'tool', step: step('a', 'running') }] }) });
		const shimmering = [...document.querySelectorAll('.ui-shimmer')].map((e) => e.textContent);
		expect(shimmering).toEqual(['Running read_file…']);
		// the tool row keeps its small spinner
		expect(document.querySelector('.row [role="status"]')).not.toBeNull();
	});

	it('does not shimmer while waiting for the user', () => {
		render(MessageView, {
			message: answer({
				streaming: true,
				startedAt: Date.now(),
				parts: [{ kind: 'tool', step: step('c1', 'running', 'exec', { command: 'npm test' }) }],
				approval: { id: 'ap', callId: 'c1', name: 'exec', arguments: '{"command":"npm test"}', risk: 'exec' }
			})
		});
		expect(screen.getByText('Waiting for approval')).toBeTruthy();
		expect(document.querySelector('.ui-shimmer')).toBeNull();
		expect(screen.getByRole('region', { name: 'Approve tool call' }).querySelector('.ui-shimmer')).toBeNull();
	});

	it('offers Continue after a limit, on the last answer only', () => {
		const { unmount } = render(MessageView, { message: answer({ stop: 'max_steps' }), last: true });
		expect(screen.getByRole('button', { name: 'Continue' })).toBeTruthy();
		unmount();
		render(MessageView, { message: answer({ stop: 'max_steps' }), last: false });
		expect(screen.queryByRole('button', { name: 'Continue' })).toBeNull();
	});
});

describe('QuestionCard', () => {
	it('submits only once every question is answered', async () => {
		const spy = vi.spyOn(chat, 'answerQuestions').mockResolvedValue();
		const message: Message = {
			id: 2,
			role: 'assistant',
			text: '',
			parts: [],
			question: {
				id: 'q1',
				questions: [{ question: 'Which DB?', header: '', multiSelect: false, options: [{ label: 'Postgres' }, { label: 'SQLite' }] }]
			} as unknown as Message['question']
		};
		render(QuestionCard, { message });
		const submit = await screen.findByRole('button', { name: 'Submit' });
		expect((submit as HTMLButtonElement).disabled).toBe(true);
		await fireEvent.click(screen.getByLabelText('SQLite'));
		expect((submit as HTMLButtonElement).disabled).toBe(false);
		await fireEvent.click(submit);
		expect(spy).toHaveBeenCalledWith(message, [{ selected: ['SQLite'], other: '' }]);
	});
});
