<script lang="ts">
	import { shellCommand } from '$lib/core/reply';
	import { chat, type Message } from '$lib/state/chat.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Card from '$lib/ui/Card.svelte';

	let { message }: { message: Message } = $props();

	const a = $derived(message.approval);
	const command = $derived(a ? shellCommand(a.arguments) : null);
	const RISK: Record<string, string> = { read: 'Reads files', write: 'Writes files', exec: 'Runs a command', network: 'Uses the network' };
	let allow = $state<HTMLButtonElement | null>(null);

	// the decision is the next thing to do: take focus, unless the user is typing somewhere
	$effect(() => {
		if (a && allow && (document.activeElement === document.body || document.activeElement === null)) allow.focus();
	});
</script>

{#if a}
	<Card label="Approve tool call">
		{#snippet header()}
			<span class="title"><span class="muted">{a.agent ?? 'Panic'} wants to run</span> <code>{a.name}</code></span>
			<span class="risk" class:warn={a.risk !== 'read'}>{RISK[a.risk] ?? a.risk}</span>
		{/snippet}
		<pre>{command ?? a.arguments}</pre>
		{#snippet footer()}
			{#if message.waiting?.length}<span class="muted waiting">{message.waiting.length} more waiting</span>{/if}
			<Button size="sm" disabled={a.answering} onclick={() => chat.approve(message, false)}>Deny</Button>
			<Button size="sm" variant="primary" bind:ref={allow} disabled={a.answering} onclick={() => chat.approve(message, true)}>Allow</Button>
		{/snippet}
	</Card>
{/if}

<style>
	code {
		font-size: var(--fs-2);
		font-weight: 600;
	}
	.risk {
		font-size: var(--fs-1);
		color: var(--muted);
		white-space: nowrap;
	}
	.risk.warn {
		color: var(--warn);
	}
	pre {
		margin: 0;
		padding: var(--sp-2) var(--sp-3);
		max-height: 220px;
		overflow: auto;
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		font-size: var(--fs-2);
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.waiting {
		margin-right: auto;
	}
</style>
