import { describe, expect, it } from 'vitest';
import { ago, without } from './conversations';

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

describe('ago', () => {
	it('says how long ago, coarser as time goes by', () => {
		const now = Date.UTC(2026, 10, 13, 12);
		expect(ago(now - 10_000, now)).toBe('just now');
		expect(ago(now + 5_000, now)).toBe('just now'); // a clock a little ahead
		expect(ago(now - 5 * MIN, now)).toBe('5m');
		expect(ago(now - 3 * HOUR, now)).toBe('3h');
		expect(ago(now - DAY - HOUR, now)).toBe('yesterday');
		expect(ago(now - 4 * DAY, now)).toBe('4d');
		expect(ago(now - 30 * DAY, now)).not.toMatch(/d$/);
	});
});

describe('without', () => {
	it('drops one chat and keeps the order', () => {
		const list = [
			{ id: 'a', title: 'A', updatedAt: 3 },
			{ id: 'b', title: 'B', updatedAt: 2 },
			{ id: 'c', title: 'C', updatedAt: 1 }
		];
		expect(without(list, 'b').map((c) => c.id)).toEqual(['a', 'c']);
		expect(without(list, 'zz')).toEqual(list);
	});
});
