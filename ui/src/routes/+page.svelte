<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { chat } from '$lib/chat.svelte';
	import Composer from '$lib/components/Composer.svelte';
	import ChatList from '$lib/components/ChatList.svelte';
	import Header from '$lib/components/Header.svelte';
	import MessageView from '$lib/components/MessageView.svelte';
	import Settings from '$lib/components/Settings.svelte';

	let settingsOpen = $state(false);
	let listOpen = $state(false);
	let scroller: HTMLElement;
	let thread: HTMLElement;
	let stick = $state(true); // follow the answer while the user is at the bottom

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

	function toBottom() {
		scroller?.scrollTo({ top: scroller.scrollHeight });
	}

	$effect(() => {
		// once per layout change (streamed text, tool rows, approval cards...), not once per token
		const watch = new ResizeObserver(() => {
			if (stick) toBottom();
		});
		watch.observe(thread);
		return () => watch.disconnect();
	});

	$effect(() => {
		// a new message of the user's brings the view back to the bottom
		void chat.messages.length;
		stick = true;
		tick().then(toBottom);
	});

	const starters = ['Explain virtual threads in Java', 'Write a haiku about deadlines', '/help', '/models free'];
</script>

<div class="shell">
<ChatList open={listOpen} onclose={() => (listOpen = false)} />
<div class="app">
	<Header onsettings={() => (settingsOpen = true)} onmenu={() => (listOpen = true)} />

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
		<div class="thread" bind:this={thread}>
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

	{#if !stick && chat.busy}
		<button class="btn jump" onclick={() => ((stick = true), toBottom())}>↓ Jump to latest</button>
	{/if}

	<Composer />

	{#if settingsOpen}
		<Settings onclose={() => (settingsOpen = false)} />
	{/if}
</div>
</div>

<style>
	.shell {
		display: flex;
		height: 100dvh;
	}
	.app {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		height: 100dvh;
	}
	main {
		flex: 1;
		overflow-y: auto;
	}
	.jump {
		position: fixed;
		left: 50%;
		bottom: 110px;
		transform: translateX(-50%);
		border-radius: 999px;
		box-shadow: 0 2px 8px rgb(0 0 0 / 0.15);
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
