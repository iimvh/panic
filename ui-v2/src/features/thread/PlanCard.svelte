<script lang="ts">
	import { renderMarkdown } from '$lib/core/markdown';
	import { chat, type Message } from '$lib/state/chat.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Card from '$lib/ui/Card.svelte';

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
	<Card label="Approve the plan">
		{#snippet header()}
			<strong>Proposed plan</strong>
			<span class="muted">Nothing has changed yet</span>
		{/snippet}
		<div class="md">{@html html}</div>
		{#if p.items.length}
			<h4>Steps</h4>
			<ol>
				{#each p.items as item, i (i)}<li>{item}</li>{/each}
			</ol>
		{/if}
		{#if p.criteria.length}
			<h4>Done when</h4>
			<ul>
				{#each p.criteria as c, i (i)}<li>{c}</li>{/each}
			</ul>
		{/if}
		{#if revising}
			<!-- svelte-ignore a11y_autofocus -->
			<textarea
				bind:value={feedback}
				rows="3"
				autofocus
				aria-label="What should change in the plan?"
				placeholder="What should change in the plan?"
				disabled={p.answering}
				onkeydown={(e) => {
					if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) sendBack();
				}}
			></textarea>
		{/if}
		{#snippet footer()}
			{#if revising}
				<Button size="sm" disabled={p.answering} onclick={() => (revising = false)}>Cancel</Button>
				<Button size="sm" variant="primary" disabled={p.answering || !feedback.trim()} onclick={sendBack}>Send back</Button>
			{:else}
				<Button size="sm" disabled={p.answering} onclick={() => (revising = true)}>Keep planning</Button>
				<Button size="sm" variant="primary" disabled={p.answering} onclick={approve}>Approve and start</Button>
			{/if}
		{/snippet}
	</Card>
{/if}

<style>
	h4 {
		margin: var(--sp-3) 0 var(--sp-1);
		font-size: var(--fs-2);
		font-weight: 600;
		color: var(--text-2);
	}
	ol,
	ul {
		margin: 0;
		padding-left: 1.4em;
	}
	textarea {
		display: block;
		width: 100%;
		margin-top: var(--sp-3);
		padding: var(--sp-2) var(--sp-3);
		border: 1px solid var(--border-strong);
		border-radius: var(--radius);
		background: var(--bg);
		resize: vertical;
	}
</style>
