<script lang="ts">
	import type { DiffLine, FileDiff } from '$lib/core/diff';

	/**
	 * A file change as lines: old and new line numbers, `+` or `-`, the text, added lines on green and removed on red.
	 * Hunks are parted by `⋯`. With `limit`, only the first lines show until "Show N more lines" is pressed.
	 */
	let { diff, limit, label = 'File changes' }: { diff: FileDiff; limit?: number; label?: string } = $props();

	type Row = { kind: 'gap' } | { kind: 'line'; line: DiffLine };

	const rows = $derived.by(() => {
		const out: Row[] = [];
		diff.hunks.forEach((h, i) => {
			if (i > 0) out.push({ kind: 'gap' });
			for (const line of h.lines) out.push({ kind: 'line', line });
		});
		return out;
	});
	const total = $derived(rows.filter((r) => r.kind === 'line').length);
	let expanded = $state(false);
	const cut = $derived(!expanded && limit !== undefined && total > limit + 2);
	const shown = $derived.by(() => {
		if (!cut) return rows;
		const out: Row[] = [];
		let n = 0;
		for (const r of rows) {
			if (r.kind === 'line' && n++ >= limit!) break;
			out.push(r);
		}
		return out;
	});
	const hidden = $derived(diff.hidden.added + diff.hidden.removed);
	const sign = { ctx: ' ', add: '+', del: '-' } as const;
</script>

<div class="ui-diff" role="table" aria-label={label}>
	<div class="scroll">
		{#each shown as row, i (i)}
			{#if row.kind === 'gap'}
				<div class="row gap" role="row"><span role="cell">⋯</span></div>
			{:else}
				{@const l = row.line}
				<div class="row {l.kind}" role="row">
					<span class="no" role="cell">{l.old ?? ''}</span>
					<span class="no" role="cell">{l.new ?? ''}</span>
					<span class="sign" role="cell" aria-label={l.kind === 'add' ? 'added' : l.kind === 'del' ? 'removed' : undefined}>{sign[l.kind]}</span>
					<span class="text" role="cell">{l.text}</span>
				</div>
			{/if}
		{/each}
	</div>
	{#if cut}
		<button type="button" class="more" onclick={() => (expanded = true)}>Show {total - limit!} more lines</button>
	{/if}
	{#if hidden && !cut}
		<p class="note">{hidden} more changed {hidden === 1 ? 'line' : 'lines'} not shown</p>
	{/if}
</div>

<style>
	.ui-diff {
		border: 1px solid var(--border);
		border-radius: var(--radius);
		background: var(--surface);
		font-family: var(--mono);
		font-size: var(--fs-1);
		line-height: 1.6;
		overflow: hidden;
	}
	.scroll {
		overflow-x: auto;
		padding: var(--sp-1) 0;
	}
	.row {
		display: grid;
		grid-template-columns: max-content max-content 2ch 1fr;
		min-width: max-content;
		width: 100%;
		color: var(--text);
	}
	.row.add {
		background: var(--diff-add-bg);
	}
	.row.del {
		background: var(--diff-del-bg);
	}
	.no {
		min-width: 4ch;
		padding: 0 var(--sp-1);
		text-align: right;
		color: var(--muted);
		font-variant-numeric: tabular-nums;
		user-select: none;
	}
	.sign {
		text-align: center;
		user-select: none;
	}
	.add .sign {
		color: var(--diff-add-fg);
	}
	.del .sign {
		color: var(--diff-del-fg);
	}
	.text {
		padding-right: var(--sp-3);
		white-space: pre;
	}
	.gap {
		display: block;
		padding: 0 var(--sp-2);
		color: var(--muted);
	}
	.more,
	.note {
		display: block;
		width: 100%;
		margin: 0;
		padding: var(--sp-1) var(--sp-3);
		border: 0;
		border-top: 1px solid var(--border);
		background: none;
		text-align: left;
		font-family: var(--font);
		font-size: var(--fs-1);
		color: var(--muted);
	}
	.more:hover {
		color: var(--text-2);
		background: var(--surface-2);
	}
</style>
