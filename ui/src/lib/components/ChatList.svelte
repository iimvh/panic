<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import { ago } from '$lib/conversations';

	/**
	 * The stored chats, newest first: open one, start a new one, delete one (asked again before it goes). A
	 * sidebar on wide screens; on narrow ones a drawer, shown while `open`.
	 */
	let { open = false, onclose }: { open?: boolean; onclose: () => void } = $props();

	let confirming = $state<string | null>(null);
	let problem = $state<string | null>(null);
	let now = $state(Date.now());

	$effect(() => {
		const timer = setInterval(() => (now = Date.now()), 60_000);
		return () => clearInterval(timer);
	});

	async function pick(id: string) {
		confirming = null;
		problem = null;
		onclose();
		await chat.open(id);
	}

	async function fresh() {
		confirming = null;
		problem = null;
		onclose();
		await chat.newChat();
	}

	async function remove(id: string) {
		problem = await chat.remove(id);
		confirming = null;
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			if (confirming) confirming = null;
			else onclose();
		}
	}
</script>

<svelte:window {onkeydown} />

{#if open}
	<button class="scrim" aria-label="Close the chat list" onclick={onclose}></button>
{/if}
<nav class:open aria-label="Chats">
	<button class="btn new" onclick={fresh} disabled={!!chat.offline}>
		<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"
			><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 5v14M5 12h14" /></svg
		>
		New chat
	</button>
	{#if problem}<p class="problem" role="alert">{problem}</p>{/if}
	<ul>
		{#each chat.conversations as c (c.id)}
			<li class:current={c.id === chat.session}>
				{#if confirming === c.id}
					<div class="confirm">
						<span>Delete this chat?</span>
						<button class="btn danger" onclick={() => remove(c.id)}>Delete</button>
						<button class="btn" onclick={() => (confirming = null)}>Cancel</button>
					</div>
				{:else}
					<button
						class="item"
						onclick={() => pick(c.id)}
						aria-current={c.id === chat.session ? 'page' : undefined}
						title={c.title}
					>
						<span class="title">{c.title || 'Untitled chat'}</span>
						<span class="time">{ago(c.updatedAt, now)}</span>
					</button>
					<button class="delete" aria-label={`Delete "${c.title || 'Untitled chat'}"`} onclick={() => (confirming = c.id)}>
						<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"
							><path
								fill="none"
								stroke="currentColor"
								stroke-width="1.8"
								stroke-linecap="round"
								d="M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12"
							/></svg
						>
					</button>
				{/if}
			</li>
		{:else}
			<li class="none">No chats yet</li>
		{/each}
	</ul>
</nav>

<style>
	nav {
		display: flex;
		flex-direction: column;
		gap: 8px;
		width: 264px;
		flex: none;
		padding: 12px 8px;
		border-right: 1px solid var(--border);
		background: var(--surface);
		overflow-y: auto;
	}
	.new {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	li {
		display: flex;
		align-items: center;
		border-radius: 8px;
	}
	li:hover,
	li:focus-within {
		background: var(--surface-2);
	}
	li.current {
		background: color-mix(in srgb, var(--accent) 12%, var(--surface-2));
	}
	.item {
		flex: 1;
		min-width: 0;
		display: flex;
		align-items: baseline;
		gap: 8px;
		padding: 7px 4px 7px 10px;
		border: 0;
		background: none;
		color: var(--text);
		font: inherit;
		font-size: 14px;
		text-align: left;
		cursor: pointer;
	}
	.title {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.time {
		flex: none;
		font-size: 12px;
		color: var(--muted);
	}
	.delete {
		flex: none;
		display: grid;
		place-items: center;
		width: 28px;
		height: 28px;
		margin-right: 4px;
		border: 0;
		border-radius: 6px;
		background: none;
		color: var(--muted);
		cursor: pointer;
		opacity: 0;
	}
	li:hover .delete,
	li:focus-within .delete,
	li.current .delete {
		opacity: 1;
	}
	.delete:hover {
		color: var(--danger);
		background: var(--surface);
	}
	.item:focus-visible,
	.delete:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: -2px;
		border-radius: 8px;
	}
	.confirm {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px;
		width: 100%;
		padding: 6px 8px;
		font-size: 13px;
	}
	.confirm span {
		flex-basis: 100%;
	}
	.confirm .btn {
		font-size: 13px;
		padding: 3px 10px;
	}
	.btn.danger {
		color: #fff;
		background: var(--danger);
		border-color: var(--danger);
	}
	.problem {
		margin: 0;
		padding: 6px 8px;
		font-size: 13px;
		color: var(--danger);
	}
	.none {
		padding: 8px 10px;
		font-size: 13px;
		color: var(--muted);
	}
	.scrim {
		display: none;
	}
	@media (max-width: 860px) {
		nav {
			position: fixed;
			inset: 0 auto 0 0;
			z-index: 20;
			width: min(300px, 85vw);
			transform: translateX(-100%);
			transition: transform 0.18s ease;
			box-shadow: 0 0 24px rgb(0 0 0 / 0.2);
		}
		nav.open {
			transform: none;
		}
		.scrim {
			display: block;
			position: fixed;
			inset: 0;
			z-index: 19;
			border: 0;
			background: rgb(0 0 0 / 0.35);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		nav {
			transition: none;
		}
	}
</style>
