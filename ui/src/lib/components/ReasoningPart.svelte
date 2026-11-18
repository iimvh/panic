<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import { reasoningLabel, thoughtTail, type Reasoning } from '$lib/reply';

	/**
	 * Where the model thought, in the order things happened. While it thinks: "Thinking · 12s", and under it a box of
	 * fixed height with the last lines of the thought, newest at the bottom; the box keeps its height while the text
	 * streams, so the thread changes height once, when the thought ends and the box folds into "Thought for 12s".
	 * `expand` false keeps it to one line with the newest line beside the label (sub-agents: several may think at
	 * once). Clicking the label opens the whole thought in the Step tab. `agent` names the sub-agent, if any.
	 */
	let { part, now = Date.now(), agent, expand = true }: { part: Reasoning; now?: number; agent?: string; expand?: boolean } =
		$props();

	const live = $derived(part.endedAt === undefined);
	const lastLine = $derived(live && !expand ? thoughtTail(part.text, 1, 300) : '');
	const stream = $derived(live && expand ? thoughtTail(part.text, 5, 600) : '');
	const selected = $derived(chat.inspected?.kind === 'part' && chat.inspected.part === part);
	// a fixed name: the streamed words are not read out (the whole thought is in the Step tab)
	const name = $derived(`${live ? 'Thinking' : reasoningLabel(part, now)}: show the whole thought`);
</script>

<div class="thought">
	<button
		type="button"
		class="reasoning"
		class:live
		class:selected
		aria-pressed={selected}
		aria-label={name}
		title="Show the whole thought"
		onclick={() => chat.inspect({ kind: 'part', part, agent })}
	>
		<span class="label">{reasoningLabel(part, now)}</span>
		{#if lastLine}<span class="tail">{lastLine}</span>{/if}
		<span class="more" aria-hidden="true">›</span>
	</button>
	{#if live && expand}
		<div class="stream" aria-hidden="true"><p>{stream}</p></div>
	{/if}
</div>

<style>
	.thought {
		container-type: inline-size;
		margin: 4px 0;
	}
	.reasoning {
		display: flex;
		align-items: baseline;
		gap: 8px;
		width: 100%;
		max-width: 100%;
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
		border-left-color: var(--rust);
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
	/* the newest lines of the thought: a fixed height from the start, text anchored at the bottom */
	.stream {
		--lines: 5;
		display: flex;
		flex-direction: column;
		justify-content: flex-end;
		height: calc(var(--lines) * 1.55em + 8px);
		overflow: hidden;
		padding: 4px 10px;
		border-left: 2px solid var(--rust);
		font-size: 12.5px;
		line-height: 1.55;
		color: var(--muted);
		font-style: italic;
		mask-image: linear-gradient(to bottom, transparent, #000 40%);
	}
	.stream p {
		margin: 0;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	@container (max-width: 480px) {
		.stream {
			--lines: 3;
		}
	}
</style>
