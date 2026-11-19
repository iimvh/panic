<script lang="ts">
	import { chat } from '$lib/state/chat.svelte';
	import { inspector } from '$lib/state/inspector.svelte';
	import { layout } from '$lib/state/layout.svelte';
	import IconButton from '$lib/ui/IconButton.svelte';
	import Tooltip from '$lib/ui/Tooltip.svelte';

	/** Where you are and what runs: the chat's title, the model (it opens Settings), the inspector. */
	let { title }: { title: string } = $props();

	const model = $derived(chat.state?.model ?? (chat.state ? 'No model' : ''));
	const down = $derived(!!chat.offline || chat.state?.connected === false);
</script>

<header>
	<IconButton icon="sidebar" label={layout.sidebar ? 'Hide sidebar' : 'Show sidebar'} shortcut="Ctrl B" onclick={() => layout.toggleSidebar()} />
	<h1>{title}</h1>
	<span class="spacer"></span>
	{#if model}
		<Tooltip text={down ? (chat.offline ?? 'Not connected: open Settings') : `${chat.state?.provider} · open Settings`}>
			{#snippet trigger(props)}
				<button {...props} class="model" class:down onclick={() => (layout.settings = true)}>{model}</button>
			{/snippet}
		</Tooltip>
	{/if}
	<IconButton icon="settings" label="Settings" onclick={() => (layout.settings = true)} tooltip={false} class="narrow-only" />
	<IconButton icon="inspector" label="Inspector" shortcut="Ctrl I" pressed={!!inspector.panel} onclick={() => inspector.toggle()} />
</header>

<style>
	header {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		height: var(--bar-h);
		padding: 0 var(--sp-2);
		flex: none;
	}
	h1 {
		margin: 0;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: var(--fs-3);
		font-weight: 500;
	}
	.spacer {
		flex: 1;
	}
	.model {
		max-width: 280px;
		height: 28px;
		padding: 0 var(--sp-2);
		border: 0;
		border-radius: var(--radius);
		background: none;
		color: var(--muted);
		font-family: var(--mono);
		font-size: var(--fs-1);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.model:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.model.down {
		color: var(--danger);
	}
	header :global(.narrow-only) {
		display: none;
	}
	@media (max-width: 640px) {
		.model {
			display: none;
		}
		header :global(.narrow-only) {
			display: inline-grid;
		}
	}
</style>
