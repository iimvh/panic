import { describe, expect, it } from 'vitest';
import { ago, group, without } from './conversations';

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

describe('group', () => {
	const chats = [
		{ id: 'a', title: 'A', updatedAt: 5 },
		{ id: 'b', title: 'B', updatedAt: 4 },
		{ id: 'c', title: 'C', updatedAt: 3 },
		{ id: 'd', title: 'D', updatedAt: 2 }
	];
	const ws = {
		projects: [{ path: '/w/app', name: 'app', conversations: ['b', 'new'] }],
		hidden: ['d']
	};

	it('keeps plain chats as they are when there are no projects', () => {
		expect(group(chats, null, 'a', 0)).toEqual({ groups: [], rest: chats });
	});

	it('groups a project, drops the hidden chats of removed projects, and keeps the rest', () => {
		const g = group(chats, ws, 'a', 0);
		expect(g.groups[0].chats.map((c) => c.id)).toEqual(['b']);
		expect(g.rest.map((c) => c.id)).toEqual(['a', 'c']);
	});

	it('shows the chat in use in its project before it has a message', () => {
		const g = group(chats, ws, 'new', 9);
		expect(g.groups[0].chats.map((c) => c.id)).toEqual(['new', 'b']);
		expect(g.groups[0].chats[0].updatedAt).toBe(9);
	});
});
