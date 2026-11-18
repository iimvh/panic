<script lang="ts">
	import { chat } from '$lib/chat.svelte';

	/**
	 * The modes of this chat, under the message box where the next message is decided: plan mode, and whether tools
	 * ask first or run unasked (bypass). Both last for the whole chat, not one message, and both say so.
	 */
	let problem = $state<string | null>(null);

	const busyNote = 'Can be changed once the answer is done.';

	/** Turning bypass on is asked again: the agent then writes, runs commands and uses the network unasked. */
	async function toggleBypass() {
		const on = !chat.permissions?.bypass;
		if (
			on &&
			!confirm(
				'Bypass permissions in this chat?\n\nThe agent will write files, run commands and use the network without asking you. Tools denied in the config stay denied.'
			)
		)
			return;
		problem = await chat.setBypass(on);
	}

	/** Plan mode: the agent researches read-only and proposes a plan before it changes anything. */
	async function togglePlan() {
		problem = await chat.setPlanMode(!chat.mode?.plan);
	}

	const locked = $derived(!!chat.offline || chat.busy);
</script>

<div class="modes">
	{#if chat.mode}
		<button
			type="button"
			class="chip plan"
			class:on={chat.mode.plan}
			aria-pressed={chat.mode.plan}
			onclick={togglePlan}
			disabled={locked}
			title={problem ??
				(chat.busy
					? busyNote
					: chat.mode.plan
						? 'For this chat: the agent researches read-only and proposes a plan for you to approve. Click to turn off.'
						: 'For this chat: have the agent research and propose a plan before it changes anything. Click to turn on.')}
		>
			Plan mode
		</button>
	{/if}
	{#if chat.permissions}
		<button
			type="button"
			class="chip bypass"
			class:on={chat.permissions.bypass}
			aria-pressed={chat.permissions.bypass}
			onclick={toggleBypass}
			disabled={locked}
			title={problem ??
				(chat.busy
					? busyNote
					: chat.permissions.bypass
						? 'For this chat: tools write, run commands and use the network without asking. Click to ask again.'
						: 'For this chat: tools ask before they write, run commands or use the network. Click to bypass.')}
		>
			{chat.permissions.bypass ? 'Bypass: tools run unasked' : 'Ask before tools'}
		</button>
	{/if}
	{#if problem}<span class="problem" role="status">{problem}</span>{/if}
</div>

<style>
	.modes {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px;
		min-width: 0;
	}
	.chip {
		flex: none;
		border: 1px solid var(--border);
		border-radius: 999px;
		padding: 2px 10px;
		background: none;
		color: var(--muted);
		font: inherit;
		font-size: 12px;
		cursor: pointer;
	}
	/* hover never hides the state: an "on" chip keeps its colour */
	.chip:hover:not(:disabled):not(.on) {
		background: var(--surface-2);
		color: var(--text);
	}
	.chip:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: 2px;
	}
	.plan.on {
		color: var(--accent-text);
		background: var(--accent);
		border-color: var(--accent);
		font-weight: 600;
	}
	.bypass.on {
		color: #fff;
		background: var(--danger);
		border-color: var(--danger);
		font-weight: 600;
	}
	.chip:disabled {
		opacity: 0.6;
		cursor: default;
	}
	.problem {
		font-size: 12px;
		color: var(--danger);
	}
</style>
