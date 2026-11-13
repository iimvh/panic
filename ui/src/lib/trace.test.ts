import { describe, expect, it } from 'vitest';
import type { TraceSpan } from './api';
import { layout, ms } from './trace';

const span = (id: string, parentId: string | null, name: string, start: number, duration: number, attrs: Record<string, unknown> = {}, status: TraceSpan['status'] = 'ok'): TraceSpan => ({
	id,
	parentId,
	name,
	startMicros: start,
	durationMicros: duration,
	status,
	attrs
});

describe('layout', () => {
	it('places each span under its parent, in start order, scaled to the root', () => {
		const rows = layout([
			span('t2', 'r', 'llm', 1_600_000, 400_000, { step: 2, promptTokens: 30, completionTokens: 5 }),
			span('r', null, 'turn', 1_000_000, 1_000_000, { steps: 2, stop: 'done', promptTokens: 50, completionTokens: 10 }),
			span('t1', 'r', 'llm', 1_000_000, 500_000, { step: 1, firstTokenMicros: 120_000, promptTokens: 20, completionTokens: 5 }),
			span('x', 'r', 'tool', 1_500_000, 100_000, { tool: 'read_file', outputChars: 42 }, 'error')
		]);
		expect(rows.map((r) => [r.label, r.depth])).toEqual([
			['turn', 0],
			['llm · step 1', 1],
			['tool · read_file', 1],
			['llm · step 2', 1]
		]);
		expect(rows[0]).toMatchObject({ offset: 0, width: 100, durationMs: 1000, tokens: 60, detail: '2 steps · 50+10 tok' });
		expect(rows[1]).toMatchObject({ offset: 0, width: 50, detail: '20+5 tok · first token 120ms' });
		expect(rows[2]).toMatchObject({ offset: 50, width: 10, startMs: 500, status: 'error', detail: '42 chars' });
		expect(rows[3]).toMatchObject({ offset: 60, width: 40 });
	});

	it('keeps bars inside the root and survives a zero-length root', () => {
		const rows = layout([span('r', null, 'turn', 0, 0), span('a', 'r', 'llm', 0, 5)]);
		expect(rows.every((r) => r.offset >= 0 && r.offset + r.width <= 100)).toBe(true);
	});

	it('hangs orphans under the root and nests grandchildren', () => {
		const rows = layout([span('r', null, 'turn', 0, 100), span('o', 'gone', 'tool', 10, 5, { tool: 'x' }), span('g', 'o', 'sub', 11, 1)]);
		expect(rows.map((r) => [r.id, r.depth])).toEqual([
			['r', 0],
			['o', 1],
			['g', 2]
		]);
	});

	it('names stops other than done', () => {
		expect(layout([span('r', null, 'turn', 0, 1, { stop: 'max_steps', steps: 1 })])[0].detail).toBe('stopped: max steps · 1 step');
	});

	it('is empty for no spans', () => {
		expect(layout([])).toEqual([]);
	});
});

describe('ms', () => {
	it('picks a readable unit', () => {
		expect(ms(0.85)).toBe('850µs');
		expect(ms(12.4)).toBe('12ms');
		expect(ms(1250)).toBe('1.25s');
		expect(ms(125_000)).toBe('2m 05s');
	});
});
