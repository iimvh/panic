<script lang="ts">
	import { tick } from 'svelte';
	import { chat } from '$lib/state/chat.svelte';
	import IconButton from '$lib/ui/IconButton.svelte';
	import MessageView from './MessageView.svelte';

	/** The conversation, following new output while the reader is at the bottom; scrolled up, it stays put. */
	let scroller = $state<HTMLElement | null>(null);
	let pinned = $state(true);

	function onscroll() {
		if (!scroller) return;
		pinned = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
	}

	function toBottom(smooth = false) {
		scroller?.scrollTo({ top: scroller.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
	}

	// new output (or another conversation) while at the bottom: stay at the bottom
	$effect(() => {
		void chat.session;
		const last = chat.messages.at(-1);
		const part = last?.parts?.at(-1);
		// a cheap signature of the newest output: how many parts, how long the last one is, whether it ended
		void [chat.messages.length, last?.text.length, last?.parts?.length, last?.streaming, last?.approval, last?.plan, last?.question];
		if (part?.kind === 'text' || part?.kind === 'reasoning') void part.text.length;
		if (part?.kind === 'tool') void [part.step.status, part.step.agent?.parts.length];
		if (pinned) void tick().then(() => toBottom());
	});

	// a question sent: follow it
	$effect(() => {
		if (chat.busy) {
			pinned = true;
			void tick().then(() => toBottom());
		}
	});
</script>

<div class="scroller" bind:this={scroller} {onscroll}>
	<div class="thread" role="log" aria-label="Conversation">
		{#each chat.messages as message, i (message.id)}
			<MessageView {message} last={i === chat.messages.length - 1} />
		{/each}
	</div>
</div>
{#if !pinned}
	<div class="jump">
		<IconButton icon="arrow-down" label="Jump to latest" onclick={() => toBottom(true)} />
	</div>
{/if}

<style>
	.scroller {
		flex: 1;
		min-height: 0;
		overflow-y: auto;
		scrollbar-gutter: stable;
	}
	.thread {
		max-width: var(--thread-w);
		margin: 0 auto;
		padding: var(--sp-6) var(--sp-5) var(--sp-4);
	}
	.jump {
		position: absolute;
		left: 50%;
		bottom: var(--sp-2);
		transform: translate(-50%, -100%);
		border: 1px solid var(--border);
		border-radius: 50%;
		background: var(--bg);
		box-shadow: var(--shadow-pop);
	}
	.jump :global(.ui-icon-btn) {
		border-radius: 50%;
	}
</style>
