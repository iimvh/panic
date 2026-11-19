<script lang="ts">
	import type { Scene } from '$lib/scene';
	import { view } from '$lib/view.svelte';

	/**
	 * "In this chat": every tool call of the conversation as a numbered dot, in order, each answer after a `Turn N`
	 * mark. Clicking one shows the map as it was at that step; the buttons replay the conversation, step through it,
	 * and go back to what happens now.
	 */
	let { scene }: { scene: Scene } = $props();

	const replaying = $derived(view.atStep !== undefined);
	let rail = $state<HTMLElement>();
	$effect(() => {
		// keep the current dot in view; live with nothing running, the newest one
		const i = scene.current >= 0 ? scene.current : view.streaming ? scene.steps.length - 1 : -1;
		if (i < 0 || !rail) return;
		rail.querySelectorAll('button.dot')[i]?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
	});
</script>

<div class="rail-box">
	<div class="head">
		<span class="label-caps">In this chat · {scene.steps.length} {scene.steps.length === 1 ? 'step' : 'steps'}</span>
		<div class="controls">
			<button class="ctl" onclick={() => view.step(-1)} disabled={!scene.steps.length} aria-label="Previous step" title="Previous step (←)">←</button>
			<button
				class="ctl"
				onclick={() => (view.playing ? view.toggle() : view.play(true))}
				disabled={!scene.steps.length || view.streaming}
				title={view.playing ? 'Pause (Space)' : 'Replay this chat (R)'}
			>
				{view.playing ? 'Pause' : 'Replay'}
			</button>
			<button class="ctl" onclick={() => view.step(1)} disabled={!scene.steps.length} aria-label="Next step" title="Next step (→)">→</button>
			<button class="ctl live" class:on={!replaying} onclick={() => view.live()} title="Follow the answer as it happens">
				<span class="dot-live" aria-hidden="true"></span>Live
			</button>
		</div>
	</div>
	{#if scene.steps.length}
		<ol class="rail" bind:this={rail}>
			{#each scene.steps as s, i (i)}
				{@const later = replaying && i > scene.current}
				{#if scene.hud.turns > 1 && (i === 0 || scene.steps[i - 1].turn !== s.turn)}
					<li class="turn" title="Answer {s.turn + 1}"><span>T{s.turn + 1}</span></li>
				{/if}
				<li>
					<button
						class="dot {s.status}"
						class:current={i === scene.current}
						class:later
						title="{i + 1}. {s.agent ? `${s.agent} · ` : ''}{s.name} {s.label}"
						onclick={() => view.pick(i)}
					>
						{i + 1}
					</button>
					{#if i === scene.current}<span class="what">{s.label}</span>{/if}
				</li>
			{/each}
		</ol>
	{:else}
		<p class="none">No tool calls yet. Each call becomes a step here.</p>
	{/if}
</div>

<style>
	.rail-box {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		flex-wrap: wrap;
	}
	.controls {
		display: flex;
		gap: 4px;
	}
	.ctl {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		border: 1px solid var(--edge);
		background: var(--surface);
		box-shadow: var(--lift);
		border-radius: 6px;
		padding: 1px 8px;
		font-size: 11px;
	}
	.ctl:disabled {
		opacity: 0.45;
		cursor: default;
	}
	.ctl.live.on {
		background: var(--green);
	}
	.dot-live {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--muted);
	}
	.on .dot-live {
		background: var(--ok);
	}
	.rail {
		display: flex;
		align-items: center;
		gap: 0;
		margin: 0;
		padding: 4px 2px 6px;
		list-style: none;
		overflow-x: auto;
	}
	li {
		display: flex;
		align-items: center;
		flex: none;
	}
	li + li::before {
		content: '';
		width: 10px;
		height: 1.5px;
		background: var(--edge);
		opacity: 0.5;
	}
	/* the start of an answer: a mark, with a gap before it instead of the joining line */
	li.turn + li::before {
		display: none;
	}
	li.turn:not(:first-child) {
		margin-left: 8px;
	}
	li.turn:not(:first-child)::before {
		display: none;
	}
	li.turn span {
		margin-right: 4px;
		padding: 0 5px;
		border-left: 2px solid var(--edge);
		font-size: 10px;
		font-weight: 700;
		color: var(--muted);
	}
	.dot {
		width: 24px;
		height: 24px;
		border-radius: 50%;
		border: 1.5px solid var(--edge);
		background: var(--surface);
		font-size: 10.5px;
		font-weight: 700;
		padding: 0;
		font-variant-numeric: tabular-nums;
	}
	.dot.ok {
		background: var(--green);
	}
	.dot.error {
		background: var(--danger);
		border-color: var(--danger);
		color: #fff;
	}
	.dot.running,
	.dot.drafting {
		background: var(--amber);
		border-style: dashed;
	}
	.dot.later {
		background: var(--bg);
		color: var(--muted);
		border-color: var(--border);
	}
	.dot.current {
		box-shadow: var(--pop);
		outline: 2px solid var(--rust);
		outline-offset: 1px;
	}
	.what {
		margin-left: 6px;
		font-size: 11px;
		max-width: 160px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.none {
		margin: 0;
		font-size: 12px;
		color: var(--muted);
	}
</style>
