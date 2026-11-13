<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { chat } from '$lib/chat.svelte';
	import Composer from '$lib/components/Composer.svelte';
	import Header from '$lib/components/Header.svelte';
	import MessageView from '$lib/components/MessageView.svelte';
	import Settings from '$lib/components/Settings.svelte';

	let settingsOpen = $state(false);
	let scroller: HTMLElement;
	let stick = true; // follow the answer while the user is at the bottom

	onMount(() => {
		void chat.load();
		// pick up keys added to panic.json, a provider switched in the CLI, ...
		const timer = setInterval(() => {
			if (!chat.busy && document.visibilityState === 'visible') void chat.refresh();
		}, 15000);
		return () => clearInterval(timer);
	});

	function onscroll() {
		stick = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
	}

	$effect(() => {
		// re-run on every new message and every streamed piece
		const last = chat.messages.at(-1);
		void chat.messages.length;
		void last?.text;
		if (stick) tick().then(() => scroller?.scrollTo({ top: scroller.scrollHeight }));
	});

	const starters = ['Explain virtual threads in Java', 'Write a haiku about deadlines', '/help', '/models free'];
</script>

<div class="app">
	<Header onsettings={() => (settingsOpen = true)} />

	{#if chat.offline}
		<div class="banner danger">
			{chat.offline}
			<button class="btn" onclick={() => chat.load()}>Retry</button>
		</div>
	{:else if chat.state && !chat.state.connected}
		<div class="banner">
			No LLM connected: the selected provider has no API key, or no model is chosen.
			<button class="btn" onclick={() => (settingsOpen = true)}>Open settings</button>
		</div>
	{/if}

	<main bind:this={scroller} {onscroll}>
		<div class="thread">
			{#if chat.messages.length === 0}
				<div class="empty">
					<img src="/favicon.svg" alt="" width="44" height="44" />
					<h1>What can I help with?</h1>
					<div class="starters">
						{#each starters as s (s)}
							<button class="btn" onclick={() => chat.send(s)} disabled={chat.busy || !!chat.offline}>{s}</button>
						{/each}
					</div>
				</div>
			{:else}
				{#each chat.messages as message (message.id)}
					<MessageView {message} />
				{/each}
			{/if}
		</div>
	</main>

	<Composer />

	{#if settingsOpen}
		<Settings onclose={() => (settingsOpen = false)} />
	{/if}
</div>

<style>
	.app {
		display: flex;
		flex-direction: column;
		height: 100dvh;
	}
	main {
		flex: 1;
		overflow-y: auto;
	}
	.thread {
		max-width: 760px;
		margin: 0 auto;
		padding: 24px 16px 8px;
	}
	.banner {
		display: flex;
		align-items: center;
		justify-content: center;
		flex-wrap: wrap;
		gap: 12px;
		padding: 8px 16px;
		font-size: 14px;
		background: color-mix(in srgb, var(--warn) 14%, var(--surface));
		border-bottom: 1px solid var(--border);
	}
	.banner.danger {
		background: color-mix(in srgb, var(--danger) 14%, var(--surface));
	}
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		padding: 12vh 0 24px;
	}
	.empty h1 {
		margin: 14px 0 20px;
		font-size: 24px;
		font-weight: 600;
		letter-spacing: -0.01em;
	}
	.starters {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: 8px;
	}
	.starters .btn {
		font-size: 14px;
		color: var(--muted);
		border-radius: 999px;
	}
</style>
