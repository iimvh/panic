<script lang="ts">
	import { chat } from '$lib/chat.svelte';

	let { onsettings }: { onsettings: () => void } = $props();

	const state = $derived(chat.state);
	const label = $derived(
		state?.provider ? `${state.provider} / ${state.model || 'no model'}` : 'no provider selected'
	);
</script>

<header>
	<div class="brand">
		<img src="/favicon.svg" alt="" width="24" height="24" />
		<span>Panic</span>
	</div>
	<button class="status" onclick={onsettings} title="Provider and model">
		<span class="dot" class:on={state?.connected} class:off={chat.offline}></span>
		<span class="label">{chat.offline ? 'offline' : label}</span>
	</button>
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
	.actions {
		margin-left: auto;
		display: flex;
		gap: 8px;
	}
	.icon {
		padding: 6px 8px;
	}
	@media (max-width: 520px) {
		.brand span {
			display: none;
		}
	}
</style>
