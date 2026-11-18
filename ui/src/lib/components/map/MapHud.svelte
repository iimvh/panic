<script lang="ts">
	import { duration } from '$lib/reply';
	import type { Hud } from '$lib/scene';

	/** The numbers of the answer on the map, in tiles: time, steps, files, commands, tokens and the chat's mode. */
	let { hud }: { hud: Hud } = $props();

	const MODE = { ask: 'ASK FIRST', plan: 'PLAN', auto: 'AUTO-RUN' };
	const tiles = $derived([
		{ label: 'Time', value: hud.elapsed === null ? '—' : duration(hud.elapsed) },
		{ label: 'Steps', value: String(hud.steps) },
		{ label: 'Files R/W', value: `${hud.reads}/${hud.writes}` },
		{ label: 'Commands', value: String(hud.commands) },
		{ label: 'Tokens', value: hud.tokens === null ? '—' : compact(hud.tokens) },
		{ label: 'Mode', value: MODE[hud.mode], tone: hud.mode }
	]);

	function compact(n: number): string {
		return n < 1000 ? String(n) : `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)}k`;
	}
</script>

<div class="hud">
	{#each tiles as tile (tile.label)}
		<div class="tile {tile.tone ?? ''}">
			<span class="label-caps">{tile.label}</span>
			<b>{tile.value}</b>
		</div>
	{/each}
</div>

<style>
	.hud {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(92px, 1fr));
		gap: 6px;
	}
	/* narrow screens: one row that scrolls, so the stage keeps its height */
	@media (max-width: 860px) {
		.hud {
			grid-template-columns: none;
			grid-auto-flow: column;
			grid-auto-columns: 86px;
			overflow-x: auto;
		}
	}
	.tile {
		display: flex;
		flex-direction: column;
		gap: 1px;
		min-width: 0;
		padding: 5px 9px;
		border: 1px solid var(--border);
		border-left: 3px solid var(--edge);
		border-radius: 6px;
		background: var(--surface);
	}
	.tile .label-caps {
		font-size: 9.5px;
		white-space: nowrap;
	}
	b {
		font-size: 14px;
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.tile.plan {
		border-left-color: var(--blue);
	}
	.tile.auto {
		border-left-color: var(--rust);
	}
</style>
