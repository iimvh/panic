<script lang="ts">
	import { diffStats, stepDiff } from '$lib/core/diff';
	import { duration, shellCommand, size } from '$lib/core/reply';
	import { allSteps, failure, stepLabel } from '$lib/core/summary';
	import { chat } from '$lib/state/chat.svelte';
	import { inspector } from '$lib/state/inspector.svelte';
	import DiffView from '$lib/ui/DiffView.svelte';
	import Icon from '$lib/ui/Icon.svelte';
	import IconButton from '$lib/ui/IconButton.svelte';
	import Spinner from '$lib/ui/Spinner.svelte';

	/**
	 * Every tool call of the conversation as a timeline; one picked, its arguments and result in full, with the
	 * previous and next call a key away (← →). This is how a run is replayed: as data, step by step. A call that
	 * changed a file shows the change first; its arguments (the whole text written) are folded under it.
	 */
	const steps = $derived(allSteps(chat.messages));
	const index = $derived(steps.findIndex((f) => f.step.id === inspector.step));
	const picked = $derived(index >= 0 ? steps[index] : null);

	function go(i: number) {
		if (i >= 0 && i < steps.length) void inspector.open('step', { step: steps[i].step.id });
	}

	function onkeydown(e: KeyboardEvent) {
		if (!picked || e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
		if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') (e.preventDefault(), go(index - 1));
		if (e.key === 'ArrowRight' || e.key === 'ArrowDown') (e.preventDefault(), go(index + 1));
	}

	/** JSON arguments, indented when they parse. */
	function pretty(args: string): string {
		try {
			return JSON.stringify(JSON.parse(args), null, 2);
		} catch {
			return args;
		}
	}
</script>

{#if picked}
	{@const step = picked.step}
	{@const command = shellCommand(step.arguments)}
	{@const diff = stepDiff(step)}
	{@const stats = diffStats(diff)}
	<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
	<div class="detail" tabindex="0" {onkeydown} role="region" aria-label="Tool call {index + 1} of {steps.length}">
		<div class="nav">
			<IconButton icon="chevron-left" label="All steps" tooltip={false} onclick={() => inspector.open('step', { step: '' })} />
			<span class="pos">Step {index + 1} of {steps.length}</span>
			<span class="spacer"></span>
			<IconButton icon="arrow-up" label="Previous step" shortcut="←" disabled={index === 0} onclick={() => go(index - 1)} />
			<IconButton icon="arrow-down" label="Next step" shortcut="→" disabled={index === steps.length - 1} onclick={() => go(index + 1)} />
		</div>
		<h3>
			<code>{step.name}</code>
			{#if picked.agent}<span class="muted">· {picked.agent}</span>{/if}
		</h3>
		<dl>
			<dt>Status</dt>
			<dd class="status {step.status}">
				{step.status === 'error' ? failure(step) : step.status}{#if step.startedAt !== undefined && step.endedAt !== undefined}<span class="muted">
						· {duration(step.endedAt - step.startedAt)}</span
					>{/if}
			</dd>
			{#if diff && diff.hunks.length}
				<dt>Changes <span class="muted">+{stats.added} −{stats.removed}</span></dt>
				<dd><DiffView {diff} label="Changes to {stepLabel(step) || 'the file'}" /></dd>
				<dt>
					<details>
						<summary>Arguments <span class="muted">{size(step.arguments)}</span></summary>
						<pre>{pretty(step.arguments)}</pre>
					</details>
				</dt>
			{:else}
				<dt>{command ? 'Command' : 'Arguments'} <span class="muted">{size(step.arguments)}</span></dt>
				<dd><pre>{command ?? pretty(step.arguments)}</pre></dd>
			{/if}
			{#if step.content !== undefined}
				<dt>Result <span class="muted">{size(step.content)}</span></dt>
				<dd><pre>{step.content}</pre></dd>
			{/if}
			{#if step.agent?.text}
				<dt>{step.agent.name} wrote</dt>
				<dd><pre>{step.agent.text}</pre></dd>
			{/if}
		</dl>
	</div>
{:else if steps.length}
	<ol class="timeline" aria-label="Tool calls of this conversation">
		{#each steps as f, i (f.step.id)}
			<li>
				<button type="button" onclick={() => go(i)}>
					<span class="n">{i + 1}</span>
					<span class="icon {f.step.status}">
						{#if f.step.status === 'running' || f.step.status === 'drafting'}<Spinner size={10} />{:else if f.step.status === 'error'}<Icon
								name="alert"
								size={13}
							/>{:else}<Icon name="check" size={13} />{/if}
					</span>
					<code>{f.step.name}</code>
					<span class="label">{f.agent ? `${f.agent}: ` : ''}{stepLabel(f.step)}</span>
				</button>
			</li>
		{/each}
	</ol>
{:else}
	<p class="empty">No tool calls yet. They show here as the agent works.</p>
{/if}

<style>
	.detail {
		padding: var(--sp-2) var(--sp-4) var(--sp-5);
		outline: none;
	}
	.nav {
		display: flex;
		align-items: center;
		gap: var(--sp-1);
		margin: 0 calc(-1 * var(--sp-2)) var(--sp-2);
	}
	.pos {
		color: var(--muted);
		font-size: var(--fs-1);
	}
	.spacer {
		flex: 1;
	}
	h3 {
		margin: 0 0 var(--sp-3);
		font-size: var(--fs-3);
		font-weight: 600;
	}
	dl {
		margin: 0;
	}
	dt {
		margin-top: var(--sp-4);
		font-size: var(--fs-1);
		font-weight: 500;
		color: var(--text-2);
	}
	dd {
		margin: var(--sp-1) 0 0;
		font-size: var(--fs-2);
	}
	.status.error {
		color: var(--danger);
	}
	summary {
		cursor: pointer;
	}
	details pre {
		margin-top: var(--sp-1);
		font-weight: 400;
		color: var(--text);
	}
	pre {
		margin: 0;
		padding: var(--sp-2) var(--sp-3);
		max-height: 50vh;
		overflow: auto;
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		font-size: var(--fs-1);
		line-height: 1.55;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.timeline {
		margin: 0;
		padding: var(--sp-2);
		list-style: none;
	}
	.timeline button {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		width: 100%;
		padding: var(--sp-1) var(--sp-2);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		text-align: left;
		font-size: var(--fs-2);
		color: var(--text-2);
	}
	.timeline button:hover {
		background: var(--surface-2);
	}
	.n {
		width: 24px;
		flex: none;
		color: var(--muted);
		font-size: var(--fs-1);
		font-variant-numeric: tabular-nums;
		text-align: right;
	}
	.icon {
		display: inline-grid;
		place-items: center;
		width: 14px;
		color: var(--muted);
	}
	.icon.error {
		color: var(--danger);
	}
	code {
		font-size: var(--fs-1);
		color: var(--text);
	}
	.label {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--muted);
	}
	.empty {
		margin: 0;
		padding: var(--sp-5) var(--sp-4);
		color: var(--muted);
		font-size: var(--fs-2);
	}
</style>
