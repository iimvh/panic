<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import { reasoningLabel, type Reasoning } from '$lib/reply';

	/**
	 * One line where the model thought, in the order things happened: "Thinking · 12s" with the newest line of the
	 * thought while it lasts, "Thought for 12s" after. It never grows, so it never pushes the answer down; clicking it
	 * opens the whole thought in the inspector. `agent` names the sub-agent that thought, if any.
	 */
	let { part, now = Date.now(), agent }: { part: Reasoning; now?: number; agent?: string } = $props();

	const live = $derived(part.endedAt === undefined);
	const lastLine = $derived(live ? (part.text.trimEnd().split('\n').at(-1) ?? '') : '');
	const selected = $derived(chat.inspected?.kind === 'part' && chat.inspected.part === part);
</script>

<button
	type="button"
	class="reasoning"
	class:live
	class:selected
	aria-pressed={selected}
	title="Show the whole thought"
	onclick={() => chat.inspect({ kind: 'part', part, agent })}
>
	<span class="label">{reasoningLabel(part, now)}</span>
	{#if lastLine}<span class="tail">{lastLine}</span>{/if}
	<span class="more" aria-hidden="true">›</span>
</button>

<style>
	.reasoning {
		display: flex;
		align-items: baseline;
		gap: 8px;
		width: 100%;
		max-width: 100%;
		margin: 4px 0;
		padding: 2px 8px;
		border: 0;
		border-left: 2px solid var(--border);
		background: none;
		color: var(--muted);
		font: inherit;
		font-size: 13px;
		text-align: left;
		cursor: pointer;
	}
	.reasoning:hover,
	.reasoning.selected {
		background: var(--surface);
		color: var(--text);
	}
	.reasoning:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: 2px;
		border-radius: 3px;
	}
	.live {
		border-left-color: var(--accent);
	}
	.label {
		flex: none;
	}
	.live .label {
		color: var(--text);
	}
	.tail {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-style: italic;
	}
	.more {
		margin-left: auto;
		flex: none;
	}
</style>
