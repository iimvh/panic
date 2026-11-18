<script lang="ts">
	import { chat, type Message } from '$lib/chat.svelte';
	import { renderMarkdown } from '$lib/markdown';

	let { message }: { message: Message } = $props();

	const p = $derived(message.plan);
	const html = $derived(p ? renderMarkdown(p.plan) : '');
	let feedback = $state('');
	let revising = $state(false);

	function approve() {
		void chat.answerPlan(message, true);
		feedback = '';
		revising = false;
	}

	/** A plan goes back with what to change: without it the agent can only guess. */
	function sendBack() {
		if (!feedback.trim()) return;
		void chat.answerPlan(message, false, feedback);
		feedback = '';
		revising = false;
	}
</script>

{#if p}
	<div class="plan" role="group" aria-label="Approve the plan">
		<div class="head">
			<span class="label">Proposed plan</span>
			<span class="hint">nothing has been changed yet</span>
		</div>
		<div class="md">{@html html}</div>
		{#if p.items.length}
			<div class="section">Steps</div>
			<ol>
				{#each p.items as item, i (i)}<li>{item}</li>{/each}
			</ol>
		{/if}
		{#if p.criteria.length}
			<div class="section">Acceptance criteria</div>
			<ul>
				{#each p.criteria as c, i (i)}<li>{c}</li>{/each}
			</ul>
		{/if}
		{#if revising}
			<textarea
				bind:value={feedback}
				rows="3"
				placeholder="What should change in the plan?"
				disabled={p.answering}
				onkeydown={(e) => {
					if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) sendBack();
				}}
			></textarea>
		{/if}
		<div class="actions">
			{#if revising}
				<button disabled={p.answering} onclick={() => (revising = false)}>Cancel</button>
				<button class="primary" disabled={p.answering || !feedback.trim()} onclick={sendBack}>Send back</button>
			{:else}
				<button disabled={p.answering} onclick={() => (revising = true)}>Keep planning</button>
				<button class="primary" disabled={p.answering} onclick={approve}>Approve and start</button>
			{/if}
		</div>
	</div>
{/if}

<style>
	.plan {
		margin: 0 0 8px;
		border: 1.5px solid var(--text);
		border-top: 6px solid var(--blue);
		border-radius: var(--radius);
		background: var(--surface);
		padding: 10px 12px;
		box-shadow: var(--pop);
	}
	.head {
		display: flex;
		gap: 8px;
		align-items: baseline;
		flex-wrap: wrap;
		margin-bottom: 6px;
	}
	.label {
		font-weight: 600;
		font-size: 13px;
	}
	.hint,
	.section {
		color: var(--muted);
		font-size: 12px;
	}
	.section {
		margin-top: 8px;
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}
	.md {
		max-height: 360px;
		overflow: auto;
		overflow-wrap: anywhere;
	}
	ol,
	ul {
		margin: 4px 0 0;
		padding-left: 22px;
		font-size: 14px;
	}
	textarea {
		display: block;
		width: 100%;
		box-sizing: border-box;
		margin-top: 8px;
		font: inherit;
		font-size: 14px;
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 6px 8px;
		background: var(--surface-2);
		color: var(--text);
		resize: vertical;
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 8px;
		margin-top: 10px;
	}
	button {
		font: inherit;
		font-size: 13px;
		border-radius: 8px;
		padding: 5px 14px;
		cursor: pointer;
		border: 1px solid var(--border);
		background: var(--surface-2);
		color: var(--text);
	}
	button.primary {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	button:disabled {
		opacity: 0.6;
		cursor: default;
	}
</style>
