<script lang="ts">
	import { shellCommand, type ToolStep } from '$lib/core/reply';
	import { failure, stepLabel } from '$lib/core/summary';
	import { inspector } from '$lib/state/inspector.svelte';
	import Icon from '$lib/ui/Icon.svelte';
	import Spinner from '$lib/ui/Spinner.svelte';
	import ReasoningPart from './ReasoningPart.svelte';
	import TextPart from './TextPart.svelte';
	import ToolRow from './ToolRow.svelte';

	/**
	 * One tool call: status, name, what it worked on. Clicking shows the call and its result in the inspector. A
	 * call that runs a sub-agent shows the sub-agent's work under it, open while it runs.
	 */
	let { step }: { step: ToolStep } = $props();

	const selected = $derived(inspector.panel === 'step' && inspector.step === step.id);
	const busy = $derived(step.status === 'running' || step.status === 'drafting');
	const label = $derived(step.status === 'drafting' ? 'writing the call…' : (stepLabel(step) || (shellCommand(step.arguments) ?? '')));
	const agent = $derived(step.agent);
	let agentOpen = $state<boolean | null>(null);
	const showAgent = $derived(agentOpen ?? step.status === 'running');
</script>

<li>
	<button type="button" class="row" class:selected aria-current={selected || undefined} onclick={() => inspector.open('step', { step: step.id })}>
		<span class="status {step.status}">
			{#if busy}<Spinner size={11} label="running" />{:else if step.status === 'error'}<Icon name="alert" size={14} label="failed" />{:else}<Icon
					name="check"
					size={14}
					label="done"
				/>{/if}
		</span>
		<code class="name">{step.name || 'tool'}</code>
		{#if label}<span class="label">{label}</span>{/if}
		{#if step.status === 'error'}<span class="fail">{failure(step)}</span>{/if}
	</button>
	{#if agent}
		<button type="button" class="agent-toggle" aria-expanded={showAgent} onclick={() => (agentOpen = !showAgent)}>
			{agent.name || 'sub-agent'} · {agent.parts.filter((p) => p.kind === 'tool').length} calls
		</button>
		{#if showAgent}
			<div class="agent">
				{#each agent.parts as part, i (i)}
					{#if part.kind === 'reasoning'}
						<ReasoningPart {part} />
					{:else if part.kind === 'text'}
						<div class="agent-text"><TextPart text={part.text} /></div>
					{:else if part.kind === 'tool'}
						<ul class="tools"><ToolRow step={part.step} /></ul>
					{/if}
				{/each}
			</div>
		{/if}
	{/if}
</li>

<style>
	li {
		list-style: none;
	}
	.row {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		width: 100%;
		min-width: 0;
		padding: 2px var(--sp-2);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		text-align: left;
		font-size: var(--fs-2);
		color: var(--text-2);
	}
	.row:hover {
		background: var(--surface-2);
	}
	.row.selected {
		background: var(--accent-soft);
	}
	.status {
		display: inline-grid;
		place-items: center;
		width: 14px;
		color: var(--muted);
	}
	.status.error {
		color: var(--danger);
	}
	.name {
		font-size: var(--fs-1);
		color: var(--text);
		flex: none;
	}
	.label {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--muted);
	}
	.fail {
		margin-left: auto;
		flex: none;
		color: var(--danger);
		font-size: var(--fs-1);
	}
	.agent-toggle {
		margin-left: 30px;
		padding: 0;
		border: 0;
		background: none;
		color: var(--muted);
		font-size: var(--fs-1);
	}
	.agent-toggle:hover {
		color: var(--text-2);
	}
	.agent {
		margin: var(--sp-1) 0 var(--sp-1) 15px;
		padding-left: var(--sp-3);
		border-left: 1px solid var(--border);
	}
	.agent-text {
		font-size: var(--fs-2);
		color: var(--text-2);
		margin: var(--sp-1) 0;
	}
	.tools {
		margin: 0;
		padding: 0;
	}
</style>
