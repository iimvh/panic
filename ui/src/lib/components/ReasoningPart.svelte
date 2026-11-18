<script lang="ts">
	import { reasoningLabel, type Reasoning } from '$lib/reply';

	/**
	 * What the model thought, folded by default: "Thinking · 12s" while it lasts, "Thought for 12s" after. The text
	 * is rendered only while open, so a long stream of thinking costs nothing when nobody reads it.
	 */
	let { part, now = Date.now() }: { part: Reasoning; now?: number } = $props();

	let open = $state(false);
	const live = $derived(part.endedAt === undefined);
</script>

<details class="reasoning" class:live bind:open>
	<summary>{reasoningLabel(part, now)}</summary>
	{#if open}<div class="text">{part.text.trim()}</div>{/if}
</details>

<style>
	.reasoning {
		margin: 4px 0;
		font-size: 13px;
		color: var(--muted);
	}
	summary {
		cursor: pointer;
		width: fit-content;
	}
	summary:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: 2px;
		border-radius: 3px;
	}
	.live summary {
		color: var(--text);
	}
	.text {
		margin: 4px 0 8px;
		padding-left: 10px;
		border-left: 2px solid var(--border);
		white-space: pre-wrap;
		overflow-wrap: anywhere;
		max-height: 320px;
		overflow-y: auto;
	}
</style>
