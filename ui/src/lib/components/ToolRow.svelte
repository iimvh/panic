<script lang="ts">
	import { shellCommand, size, type ToolStep } from '$lib/reply';
	import ReasoningPart from './ReasoningPart.svelte';
	import TextPart from './TextPart.svelte';
	import ToolRow from './ToolRow.svelte';

	/**
	 * One tool call of an answer. A call that runs a sub-agent also shows the sub-agent's work under it (its text
	 * and its own tool calls, in order), open while it runs and folded once it is done.
	 */
	let { step }: { step: ToolStep } = $props();

	const agent = $derived(step.agent);
	const working = $derived(step.status === 'running');
	const command = $derived(step.status === 'drafting' ? null : shellCommand(step.arguments));
</script>

<div class="tool {step.status}" title={step.content ?? step.arguments}>
	<span class="name">{step.name}</span>
	{#if step.status === 'drafting'}
		<!-- the end of what is being written, right-aligned so the newest text shows -->
		<span class="args tail"><bdi>{step.arguments.slice(-200)}</bdi></span>
		<span class="status">writing · {size(step.arguments)}</span>
	{:else}
		<span class="args">{command ?? step.arguments}</span>
		<span class="status">{working ? '…' : step.status}</span>
	{/if}
</div>
{#if agent}
	<details class="agent" open={working}>
		<summary>
			<span class="who">{agent.name || 'sub-agent'}</span>
			{working ? 'is working' : `worked · ${agent.parts.filter((p) => p.kind === 'tool').length} tool calls`}
		</summary>
		<div class="work">
			{#each agent.parts as part, i (i)}
				{#if part.kind === 'reasoning'}
					<ReasoningPart {part} />
				{:else if part.kind === 'text'}
					<TextPart text={part.text} live={working && i === agent.parts.length - 1} />
				{:else if part.kind === 'tool'}
					<ToolRow step={part.step} />
				{/if}
			{/each}
		</div>
	</details>
{/if}

<style>
	.tool {
		display: flex;
		gap: 8px;
		align-items: baseline;
		min-width: 0;
		margin: 4px 0;
		font: 12px var(--mono);
		color: var(--muted);
		border-left: 2px solid var(--border);
		padding-left: 8px;
	}
	.tool .name {
		color: var(--text);
	}
	.tool .args {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	/* clip the start, not the end: the newest text stays in view */
	.tool .tail {
		direction: rtl;
	}
	.tool.drafting {
		border-left-style: dashed;
		border-left-color: var(--accent);
	}
	.tool.running {
		border-left-color: var(--accent);
	}
	.tool.error {
		border-left-color: var(--danger);
	}
	.tool.error .status {
		color: var(--danger);
	}
	.agent {
		margin: 2px 0 8px 10px;
		border-left: 1px dashed var(--border);
		padding-left: 10px;
		font-size: 13px;
	}
	.agent summary {
		cursor: pointer;
		color: var(--muted);
		font-size: 12px;
	}
	.agent summary:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: 2px;
		border-radius: 3px;
	}
	.agent .who {
		color: var(--text);
		font-weight: 600;
	}
	.work {
		margin-top: 4px;
		color: var(--muted);
	}
</style>
