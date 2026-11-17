<script lang="ts">
	import { chat } from '$lib/chat.svelte';

	let { onsettings, onmenu }: { onsettings: () => void; onmenu: () => void } = $props();

	let problem = $state<string | null>(null);

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

	const info = $derived(chat.state);
	const label = $derived(
		info?.provider ? `${info.provider} / ${info.model || 'no model'}` : 'no provider selected'
	);
</script>

<header>
	<button class="btn icon menu" onclick={onmenu} aria-label="Chats">
		<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"
			><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M4 7h16M4 12h16M4 17h16" /></svg
		>
	</button>
	<div class="brand">
		<img src="/favicon.svg" alt="" width="24" height="24" />
		<span>Panic</span>
	</div>
	<button class="status" onclick={onsettings} title="Provider and model">
		<span class="dot" class:on={info?.connected} class:off={chat.offline}></span>
		<span class="label">{chat.offline ? 'offline' : label}</span>
	</button>
	{#if chat.mode}
		<button
			class="mode plan"
			class:on={chat.mode.plan}
			aria-pressed={chat.mode.plan}
			onclick={togglePlan}
			disabled={!!chat.offline || chat.busy}
			title={problem ??
				(chat.mode.plan
					? 'The agent researches read-only and proposes a plan for you to approve. Click to turn off.'
					: 'Have the agent research and propose a plan before it changes anything. Click to turn on.')}
		>
			{chat.mode.plan ? 'Plan on' : 'Plan off'}
		</button>
	{/if}
	{#if chat.permissions}
		<button
			class="mode bypass"
			class:on={chat.permissions.bypass}
			aria-pressed={chat.permissions.bypass}
			onclick={toggleBypass}
			disabled={!!chat.offline || chat.busy}
			title={problem ??
				(chat.permissions.bypass
					? 'Tools run without asking. Click to ask again.'
					: 'Ask before tools write, run commands or use the network. Click to bypass.')}
		>
			{chat.permissions.bypass ? 'Bypass on' : 'Bypass off'}
		</button>
	{/if}
	<div class="actions">
		<button class="btn" onclick={() => chat.newChat()} disabled={!!chat.offline}>New chat</button>
		<button class="btn icon" onclick={onsettings} aria-label="Settings">
			<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"
				><path
					fill="none"
					stroke="currentColor"
					stroke-width="1.8"
					stroke-linecap="round"
					d="M4 7h10M18 7h2M4 17h4M12 17h8M16 4v6M10 14v6"
				/></svg
			>
		</button>
	</div>
</header>

<style>
	header {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 16px;
		border-bottom: 1px solid var(--border);
		background: var(--surface);
	}
	.brand {
		display: flex;
		align-items: center;
		gap: 8px;
		font-weight: 650;
		letter-spacing: -0.01em;
	}
	.status {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
		border: 1px solid transparent;
		background: none;
		border-radius: 999px;
		padding: 4px 10px;
		color: var(--muted);
		font-size: 13px;
	}
	.status:hover {
		border-color: var(--border);
		background: var(--surface-2);
	}
	.label {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.dot {
		flex: none;
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--warn);
	}
	.dot.on {
		background: var(--ok);
	}
	.dot.off {
		background: var(--danger);
	}
	.mode {
		flex: none;
		border: 1px solid var(--border);
		border-radius: 999px;
		padding: 3px 10px;
		background: none;
		color: var(--muted);
		font: inherit;
		font-size: 12px;
		cursor: pointer;
	}
	.bypass.on {
		color: #fff;
		background: var(--danger);
		border-color: var(--danger);
		font-weight: 600;
	}
	.plan.on {
		color: var(--accent-text);
		background: var(--accent);
		border-color: var(--accent);
		font-weight: 600;
	}
	.mode:disabled {
		opacity: 0.6;
		cursor: default;
	}
	.actions {
		margin-left: auto;
		display: flex;
		gap: 8px;
	}
	.icon {
		padding: 6px 8px;
	}
	/* the chat list is a sidebar on wide screens: its button only shows when it is a drawer */
	.menu {
		display: none;
	}
	@media (max-width: 860px) {
		.menu {
			display: inline-flex;
		}
		.actions .btn:not(.icon) {
			display: none;
		}
	}
	@media (max-width: 520px) {
		.brand span {
			display: none;
		}
	}
</style>
