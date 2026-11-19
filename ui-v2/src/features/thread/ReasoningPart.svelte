<script lang="ts">
	import { reasoningLabel, type Reasoning } from '$lib/core/reply';
	import Icon from '$lib/ui/Icon.svelte';

	/**
	 * What the model thought: one quiet line, "Thought for 12s", folded by default. Open, it shows the whole thought
	 * (growing while it streams). No live box of scrolling words: the progress line says the model is thinking.
	 */
	let { part }: { part: Reasoning } = $props();

	let open = $state(false);
	const live = $derived(part.endedAt === undefined);
	const label = $derived(live ? 'Thinking' : reasoningLabel(part, part.endedAt ?? 0));
</script>

<div class="reasoning">
	<button type="button" class="toggle" aria-expanded={open} onclick={() => (open = !open)}>
		<span class="chev" class:open><Icon name="chevron-right" size={14} /></span>
		{label}
	</button>
	{#if open}
		<div class="text">{part.text}</div>
	{/if}
</div>

<style>
	.reasoning {
		margin: var(--sp-1) 0;
	}
	.toggle {
		display: inline-flex;
		align-items: center;
		gap: var(--sp-1);
		padding: 0;
		border: 0;
		background: none;
		color: var(--muted);
		font-size: var(--fs-2);
	}
	.toggle:hover {
		color: var(--text-2);
	}
	.chev {
		display: inline-flex;
		transition: transform 0.12s;
	}
	.chev.open {
		transform: rotate(90deg);
	}
	.text {
		margin: var(--sp-1) 0 var(--sp-2) 6px;
		padding-left: var(--sp-3);
		border-left: 1px solid var(--border);
		color: var(--muted);
		font-size: var(--fs-2);
		white-space: pre-wrap;
		max-height: 360px;
		overflow-y: auto;
	}
</style>
