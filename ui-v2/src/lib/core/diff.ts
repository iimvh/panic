import type { ToolStep } from './reply';

/** One line of a diff: kept (`ctx`), added, removed, with its number in the old and the new file when known. */
export type DiffLine = { kind: 'ctx' | 'add' | 'del'; old?: number; new?: number; text: string };

/** A run of changed lines with the lines around them. */
export type Hunk = { lines: DiffLine[] };

/** What a file tool changed: its hunks, and how many added and removed lines a long diff leaves out. */
export type FileDiff = { hunks: Hunk[]; hidden: { added: number; removed: number } };

const HEADER = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/;
const NOTE = /^\\ not shown: \+(\d+) -(\d+)/;

/**
 * The hunks of a unified diff as the server sends it (`@@ -a,b +c,d @@`, then lines starting with space, `+` or
 * `-`, and maybe a closing `\ not shown: +A -R` when it was cut).
 */
export function parseUnified(diff: string): FileDiff {
	const hunks: Hunk[] = [];
	const hidden = { added: 0, removed: 0 };
	let hunk: Hunk | null = null;
	let oldNo = 0;
	let newNo = 0;
	for (const line of diff.split('\n')) {
		const h = HEADER.exec(line);
		if (h) {
			// a count of 0 names the line before the (empty) range
			oldNo = h[2] === '0' ? Number(h[1]) + 1 : Number(h[1]);
			newNo = h[4] === '0' ? Number(h[3]) + 1 : Number(h[3]);
			hunk = { lines: [] };
			hunks.push(hunk);
			continue;
		}
		const note = NOTE.exec(line);
		if (note) {
			hidden.added = Number(note[1]);
			hidden.removed = Number(note[2]);
			continue;
		}
		if (!hunk || !line) continue;
		const text = line.slice(1);
		if (line[0] === '+') hunk.lines.push({ kind: 'add', new: newNo++, text });
		else if (line[0] === '-') hunk.lines.push({ kind: 'del', old: oldNo++, text });
		else if (line[0] === ' ') hunk.lines.push({ kind: 'ctx', old: oldNo++, new: newNo++, text });
	}
	return { hunks, hidden };
}

const MAX_LCS = 200;

/** `before` into `after` line by line: the common lines kept, the rest removed then added. */
function lineDiff(before: string[], after: string[], oldStart?: number, newStart?: number): DiffLine[] {
	const n = before.length;
	const m = after.length;
	let o = oldStart;
	let w = newStart;
	const del = (text: string): DiffLine => ({ kind: 'del', old: o === undefined ? undefined : o++, text });
	const add = (text: string): DiffLine => ({ kind: 'add', new: w === undefined ? undefined : w++, text });
	if (n > MAX_LCS || m > MAX_LCS) return [...before.map(del), ...after.map(add)];
	// lcs[i][j]: the longest common run of before[i..] and after[j..]
	const lcs = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
	for (let i = n - 1; i >= 0; i--)
		for (let j = m - 1; j >= 0; j--) lcs[i][j] = before[i] === after[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
	const out: DiffLine[] = [];
	let i = 0;
	let j = 0;
	while (i < n || j < m) {
		if (i < n && j < m && before[i] === after[j]) {
			out.push({ kind: 'ctx', old: o === undefined ? undefined : o++, new: w === undefined ? undefined : w++, text: before[i] });
			i++;
			j++;
		} else if (i < n && (j === m || lcs[i + 1][j] >= lcs[i][j + 1])) out.push(del(before[i++])); // removed first, as git does
		else out.push(add(after[j++]));
	}
	return out;
}

function lines(text: string): string[] {
	if (!text) return [];
	const out = text.replace(/\r\n/g, '\n').split('\n');
	if (out[out.length - 1] === '') out.pop();
	return out;
}

function args(json: string): Record<string, unknown> {
	try {
		const v = JSON.parse(json);
		return v && typeof v === 'object' ? v : {};
	} catch {
		return {};
	}
}

/**
 * The change a file tool made, from its arguments, when the server sent no diff (a conversation stored before it
 * did): `edit_file`'s old and new text at the line its result names, `write_file`'s content as added lines.
 */
export function fallbackDiff(step: Pick<ToolStep, 'name' | 'arguments' | 'content'>): FileDiff | null {
	const a = args(step.arguments);
	if (step.name === 'edit_file' && typeof a.old_str === 'string' && typeof a.new_str === 'string') {
		const at = /first at line (\d+)/.exec(step.content ?? '');
		const line = at ? Number(at[1]) : undefined;
		const out = lineDiff(lines(a.old_str), lines(a.new_str), line, line);
		return out.length ? { hunks: [{ lines: out }], hidden: { added: 0, removed: 0 } } : null;
	}
	if (step.name === 'write_file' && typeof a.content === 'string') {
		const start = a.append === true ? undefined : 1;
		const out = lineDiff([], lines(a.content), undefined, start);
		return out.length ? { hunks: [{ lines: out }], hidden: { added: 0, removed: 0 } } : null;
	}
	return null;
}

/** What a finished call changed in a file: the server's diff, else one rebuilt from its arguments; null for none. */
export function stepDiff(step: Pick<ToolStep, 'name' | 'arguments' | 'content' | 'status' | 'diff'>): FileDiff | null {
	if (step.status !== 'ok') return null;
	if (step.diff) return parseUnified(step.diff);
	return fallbackDiff(step);
}

/** The lines a diff adds and removes, those it leaves out included. */
export function diffStats(diff: FileDiff | null): { added: number; removed: number } {
	let added = diff?.hidden.added ?? 0;
	let removed = diff?.hidden.removed ?? 0;
	for (const h of diff?.hunks ?? [])
		for (const l of h.lines) {
			if (l.kind === 'add') added++;
			if (l.kind === 'del') removed++;
		}
	return { added, removed };
}

/** "Added 3 lines, removed 1 line", as the summary of a change. */
export function describeChange({ added, removed }: { added: number; removed: number }): string {
	const n = (k: number) => `${k} ${k === 1 ? 'line' : 'lines'}`;
	if (added && removed) return `Added ${n(added)}, removed ${n(removed)}`;
	if (removed) return `Removed ${n(removed)}`;
	return `Added ${n(added)}`;
}
