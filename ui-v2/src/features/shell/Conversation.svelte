<script lang="ts">
	import { Composer } from '$features/composer';
	import { Thread } from '$features/thread';
	import { chat } from '$lib/state/chat.svelte';
	import { layout } from '$lib/state/layout.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Icon from '$lib/ui/Icon.svelte';

	/** The conversation shown, or for a new one only the message box: nothing else asks for attention. */
	const empty = $derived(chat.messages.length === 0);
</script>

{#if chat.offline}
	<div class="banner" role="alert">
		<Icon name="alert" size={14} />
		<span>{chat.offline}</span>
		<Button size="sm" onclick={() => chat.load()}>Retry</Button>
	</div>
{:else if chat.state && !chat.state.connected}
	<div class="banner" role="status">
		<Icon name="alert" size={14} />
		<span>No model is connected. Commands still work.</span>
		<Button size="sm" onclick={() => (layout.settings = true)}>Open settings</Button>
	</div>
{/if}

{#if empty}
	<div class="empty">
		<h2>What should Panic do?</h2>
		<Composer autofocus />
	</div>
{:else}
	<div class="thread">
		<Thread />
		<Composer />
	</div>
{/if}

<style>
	.banner {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		margin: 0 var(--sp-3);
		padding: var(--sp-1) var(--sp-2) var(--sp-1) var(--sp-3);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		background: var(--surface);
		color: var(--text-2);
		font-size: var(--fs-2);
	}
	.banner span {
		flex: 1;
	}
	.banner :global(.icon) {
		color: var(--warn);
	}
	.empty {
		flex: 1;
		display: flex;
		flex-direction: column;
		justify-content: center;
		padding-bottom: 12vh;
	}
	h2 {
		margin: 0 0 var(--sp-5);
		text-align: center;
		font-size: var(--fs-5);
		font-weight: 600;
	}
	.thread {
		position: relative;
		flex: 1;
		min-height: 0;
		display: flex;
		flex-direction: column;
	}
</style>
