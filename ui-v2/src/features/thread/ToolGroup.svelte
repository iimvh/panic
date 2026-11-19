<script lang="ts">
	import type { ToolStep } from '$lib/core/reply';
	import { effect, lineDelta } from '$lib/core/summary';
	import Icon from '$lib/ui/Icon.svelte';
	import ToolRow from './ToolRow.svelte';

	/**
	 * Consecutive tool calls of an answer. One call is shown as it is; several fold into "5 tool calls", open while
	 * any of them runs, folded once they are done (the user's own toggle wins). A folded group says what its file
	 * changes add and remove, so a change is never out of sight without a sign.
	 */
	let { steps }: { steps: ToolStep[] } = $props();

	const running = $derived(steps.some((s) => s.status === 'running' || s.status === 'drafting'));
	const failed = $derived(steps.filter((s) => s.status === 'error').length);
	let toggled = $state<boolean | null>(null);
	const open = $derived(toggled ?? running);
	const changed = $derived(
		steps
			.filter((s) => s.status === 'ok' && effect(s.name) === 'write')
			.map(lineDelta)
			.reduce((t, d) => ({ added: t.added + d.added, removed: t.removed + d.removed }), { added: 0, removed: 0 })
	);
</script>

{#if steps.length === 1}
	<ul class="tools"><ToolRow step={steps[0]} /></ul>
{:else}
	<div class="group">
		<button type="button" class="toggle" aria-expanded={open} onclick={() => (toggled = !open)}>
			<span class="chev" class:open><Icon name="chevron-right" size={14} /></span>
			{steps.length} tool calls{#if changed.added || changed.removed}<span> · +{changed.added} −{changed.removed}</span>{/if}{#if failed}<span
					class="failed"> · {failed} failed</span
				>{/if}
		</button>
		{#if open}
			<ul class="tools">
				{#each steps as step (step.id)}<ToolRow {step} />{/each}
			</ul>
		{/if}
	</div>
{/if}

<style>
	.group {
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
	.failed {
		color: var(--danger);
	}
	.tools {
		margin: var(--sp-1) 0;
		padding: 0;
	}
	.group .tools {
		margin-left: 6px;
		padding-left: var(--sp-2);
		border-left: 1px solid var(--border);
	}
</style>
