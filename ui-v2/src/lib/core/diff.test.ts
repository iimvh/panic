import { describe, expect, it } from 'vitest';
import { describeChange, diffStats, fallbackDiff, parseUnified, stepDiff } from './diff';

describe('parseUnified', () => {
	it('numbers kept, added and removed lines from the hunk header', () => {
		const d = parseUnified('@@ -4,3 +4,4 @@\n one\n-two\n+2\n+2b\n three\n');
		expect(d.hunks).toHaveLength(1);
		expect(d.hunks[0].lines).toEqual([
			{ kind: 'ctx', old: 4, new: 4, text: 'one' },
			{ kind: 'del', old: 5, text: 'two' },
			{ kind: 'add', new: 5, text: '2' },
			{ kind: 'add', new: 6, text: '2b' },
			{ kind: 'ctx', old: 6, new: 7, text: 'three' }
		]);
	});

	it('starts a new file at line 1 and keeps hunks apart', () => {
		const d = parseUnified('@@ -0,0 +1,1 @@\n+a\n@@ -9,1 +10,1 @@\n-x\n+y\n');
		expect(d.hunks[0].lines[0]).toEqual({ kind: 'add', new: 1, text: 'a' });
		expect(d.hunks[1].lines.map((l) => [l.old, l.new])).toEqual([
			[9, undefined],
			[undefined, 10]
		]);
	});

	it('counts what a cut diff leaves out', () => {
		const d = parseUnified('@@ -0,0 +1,2 @@\n+a\n+b\n\\ not shown: +7 -1\n');
		expect(d.hidden).toEqual({ added: 7, removed: 1 });
		expect(diffStats(d)).toEqual({ added: 9, removed: 1 });
	});
});

describe('fallbackDiff', () => {
	it('rebuilds an edit from its arguments at the line its result names', () => {
		const d = fallbackDiff({
			name: 'edit_file',
			arguments: JSON.stringify({ path: 'a.ts', old_str: 'x\ny\nz', new_str: 'x\nY\nz' }),
			content: 'edited a.ts: replaced 1 occurrence (first at line 12)'
		});
		expect(d?.hunks[0].lines).toEqual([
			{ kind: 'ctx', old: 12, new: 12, text: 'x' },
			{ kind: 'del', old: 13, text: 'y' },
			{ kind: 'add', new: 13, text: 'Y' },
			{ kind: 'ctx', old: 14, new: 14, text: 'z' }
		]);
	});

	it('shows a write as added lines', () => {
		const d = fallbackDiff({ name: 'write_file', arguments: JSON.stringify({ path: 'n', content: 'a\nb\n' }) });
		expect(diffStats(d)).toEqual({ added: 2, removed: 0 });
		expect(d?.hunks[0].lines[1]).toEqual({ kind: 'add', new: 2, text: 'b' });
	});

	it('has nothing for other tools', () => {
		expect(fallbackDiff({ name: 'read_file', arguments: '{"path":"a"}' })).toBeNull();
	});
});

describe('stepDiff', () => {
	const base = { name: 'edit_file', arguments: '{"old_str":"a","new_str":"b"}', content: 'edited' };
	it('prefers the diff the server sent', () => {
		expect(diffStats(stepDiff({ ...base, status: 'ok', diff: '@@ -1,1 +1,2 @@\n-a\n+b\n+c\n' }))).toEqual({ added: 2, removed: 1 });
	});
	it('shows nothing for a call that failed or still runs', () => {
		expect(stepDiff({ ...base, status: 'error' })).toBeNull();
		expect(stepDiff({ ...base, status: 'running' })).toBeNull();
	});
});

describe('describeChange', () => {
	it('says what was added and removed', () => {
		expect(describeChange({ added: 18, removed: 0 })).toBe('Added 18 lines');
		expect(describeChange({ added: 1, removed: 2 })).toBe('Added 1 line, removed 2 lines');
		expect(describeChange({ added: 0, removed: 1 })).toBe('Removed 1 line');
	});
});
