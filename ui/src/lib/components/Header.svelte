<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import { theme } from '$lib/theme.svelte';

	/** `expanded`: the chat list shows (the sidebar, or the drawer on a narrow screen). */
	let {
		onsettings,
		onmenu,
		expanded = true
	}: { onsettings: () => void; onmenu: () => void; expanded?: boolean } = $props();

	const THEME = { auto: 'system', light: 'light', dark: 'dark' } as const;
	const next = $derived(theme.pref === 'auto' ? 'light' : theme.pref === 'light' ? 'dark' : 'auto');

	const info = $derived(chat.state);
	const label = $derived(
		info?.provider ? `${info.provider} / ${info.model || 'no model'}` : 'no provider selected'
	);
</script>

<header>
	<button
		class="btn icon menu"
		onclick={onmenu}
		aria-label="Chats"
		aria-expanded={expanded}
		title="{expanded ? 'Hide' : 'Show'} chats (Ctrl+B)"
	>
		<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"
			><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" d="M4 7h16M4 12h16M4 17h16" /></svg
		>
	</button>
	<div class="brand">
		<img src="/panic.png" alt="" width="30" height="30" />
		<span>Panic</span>
	</div>
	<button class="status" onclick={onsettings} title="Provider and model">
		<span class="dot" class:on={info?.connected} class:off={chat.offline}></span>
		<span class="label">{chat.offline ? 'offline' : label}</span>
	</button>
	<div class="actions">
		<button class="btn" onclick={() => chat.newChat()} disabled={!!chat.offline}>New chat</button>
		<button
			class="btn icon"
			onclick={() => theme.cycle()}
			aria-label="Theme: {THEME[theme.pref]}"
			title="Theme: {THEME[theme.pref]} (click for {THEME[next]})"
		>
			<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
				{#if theme.pref === 'light'}
					<circle cx="12" cy="12" r="4" /><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4" />
				{:else if theme.pref === 'dark'}
					<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z" />
				{:else}
					<circle cx="12" cy="12" r="8" /><path d="M12 4a8 8 0 0 1 0 16Z" fill="currentColor" />
				{/if}
			</svg>
		</button>
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
		font-weight: 800;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		font-size: 13px;
	}
	.status {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
		border: 1px solid var(--border);
		background: var(--bg);
		border-radius: 999px;
		padding: 2px 10px;
		color: var(--muted);
		font-size: 12px;
	}
	.status:hover {
		border-color: var(--edge);
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
	.menu {
		display: inline-flex;
	}
	@media (max-width: 520px) {
		.actions .btn:not(.icon) {
			display: none;
		}
		.brand span {
			display: none;
		}
	}
</style>
