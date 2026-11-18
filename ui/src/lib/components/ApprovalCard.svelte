<script lang="ts">
	import { chat, type Message } from '$lib/chat.svelte';
	import { shellCommand } from '$lib/reply';

	let { message }: { message: Message } = $props();

	const a = $derived(message.approval);
	const command = $derived(a ? shellCommand(a.arguments) : null);
</script>

{#if a}
	<div class="approval" role="group" aria-label="Approve tool call">
		<div class="head">
			<span class="label">Allow</span>
			{#if a.agent}<span class="agent">{a.agent}:</span>{/if}
			<span class="name">{a.name}</span>
			<span class="risk {a.risk}">{a.risk}</span>
		</div>
		<pre class="args">{command ?? a.arguments}</pre>
		{#if message.waiting?.length}
			<div class="more">{message.waiting.length} more waiting</div>
		{/if}
		<div class="actions">
			<button class="deny" disabled={a.answering} onclick={() => chat.approve(message, false)}>Deny</button>
			<button class="allow" disabled={a.answering} onclick={() => chat.approve(message, true)}>Allow</button>
		</div>
	</div>
{/if}

<style>
	.approval {
		margin: 0 0 8px;
		border: 1.5px solid var(--text);
		border-top: 6px solid var(--warn);
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
	}
	.label {
		color: var(--muted);
		font-size: 13px;
	}
	.name {
		font: 600 13px var(--mono);
	}
	.agent {
		font-size: 13px;
		font-weight: 600;
	}
	.more {
		margin: -2px 0 8px;
		font-size: 12px;
		color: var(--muted);
	}
	.risk {
		font: 11px var(--mono);
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--warn);
		border: 1px solid currentColor;
		border-radius: 999px;
		padding: 0 6px;
	}
	.risk.exec,
	.risk.network {
		color: var(--danger);
	}
	.args {
		margin: 8px 0;
		max-height: 160px;
		overflow: auto;
		font: 12px/1.5 var(--mono);
		background: var(--code-bg);
		border-radius: 8px;
		padding: 6px 8px;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 8px;
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
	button.allow {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	button:disabled {
		opacity: 0.6;
		cursor: default;
	}
</style>
