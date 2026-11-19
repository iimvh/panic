<script lang="ts">
	import { goto } from '$app/navigation';
	import { ago } from '$lib/core/conversations';
	import { chat } from '$lib/state/chat.svelte';
	import { layout } from '$lib/state/layout.svelte';
	import Dialog from '$lib/ui/Dialog.svelte';

	/**
	 * Ctrl+K: find a chat by its title, or start a new one. Client-side only: it filters the list the page has.
	 * A combobox: typing filters, ↑ ↓ move, Enter opens.
	 */
	let query = $state('');
	let active = $state(0);
	let now = $state(0);

	const results = $derived.by(() => {
		const q = query.trim().toLowerCase();
		const chats = chat.conversations
			.filter((c) => !q || (c.title || 'new chat').toLowerCase().includes(q))
			.slice(0, 50)
			.map((c) => ({ id: c.id, label: c.title || 'New chat', hint: ago(c.updatedAt, now), href: `/c/${c.id}` }));
		return [...(!q || 'new chat'.includes(q) ? [{ id: 'new', label: 'New chat', hint: 'Ctrl Shift O', href: '/' }] : []), ...chats];
	});

	$effect(() => {
		if (!layout.search) return;
		query = '';
		now = Date.now();
	});
	$effect(() => {
		void results.length;
		active = 0;
	});

	async function go(href: string) {
		layout.search = false;
		await goto(href);
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.isComposing) return;
		const n = results.length;
		if (e.key === 'ArrowDown' && n) (e.preventDefault(), (active = (active + 1) % n));
		else if (e.key === 'ArrowUp' && n) (e.preventDefault(), (active = (active + n - 1) % n));
		else if (e.key === 'Enter' && results[active]) (e.preventDefault(), void go(results[active].href));
	}
</script>

<Dialog bind:open={layout.search} title="Search chats" width={560}>
	<!-- svelte-ignore a11y_autofocus -->
	<input
		type="text"
		role="combobox"
		aria-expanded="true"
		aria-controls="search-results"
		aria-activedescendant={results[active] ? `sr-${results[active].id}` : undefined}
		aria-label="Search chats"
		placeholder="Type to filter"
		autofocus
		bind:value={query}
		{onkeydown}
	/>
	<ul id="search-results" role="listbox" aria-label="Chats">
		{#each results as r, i (r.id)}
			<li id="sr-{r.id}" role="option" aria-selected={i === active}>
				<button type="button" tabindex="-1" class:active={i === active} onmousemove={() => (active = i)} onclick={() => go(r.href)}>
					<span class="t">{r.label}</span><span class="hint">{r.hint}</span>
				</button>
			</li>
		{:else}
			<li class="none">No chats match.</li>
		{/each}
	</ul>
</Dialog>

<style>
	input {
		width: 100%;
		height: 36px;
		padding: 0 var(--sp-3);
		border: 1px solid var(--border-strong);
		border-radius: var(--radius);
		background: var(--bg);
	}
	ul {
		margin: var(--sp-2) 0 0;
		padding: 0;
		max-height: 50vh;
		overflow-y: auto;
		list-style: none;
	}
	button {
		display: flex;
		align-items: center;
		gap: var(--sp-3);
		width: 100%;
		padding: var(--sp-2) var(--sp-3);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		text-align: left;
	}
	button.active {
		background: var(--surface-2);
	}
	.t {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.hint {
		color: var(--muted);
		font-size: var(--fs-1);
	}
	.none {
		padding: var(--sp-3);
		color: var(--muted);
		font-size: var(--fs-2);
	}
</style>
