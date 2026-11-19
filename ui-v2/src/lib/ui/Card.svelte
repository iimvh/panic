<script lang="ts">
	import type { Snippet } from 'svelte';

	/** A decision the user has to make inside the thread: a header line, a body, and the actions at the bottom. */
	let { label, header, children, footer }: { label: string; header: Snippet; children?: Snippet; footer: Snippet } = $props();
</script>

<section class="ui-card" aria-label={label}>
	<header>{@render header()}</header>
	{#if children}<div class="body">{@render children()}</div>{/if}
	<footer>{@render footer()}</footer>
</section>

<style>
	.ui-card {
		margin: var(--sp-3) 0;
		border: 1px solid var(--border-strong);
		border-radius: var(--radius-lg);
		background: var(--bg);
	}
	header {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--sp-3);
		padding: var(--sp-3) var(--sp-4) 0;
		font-size: var(--fs-2);
	}
	.body {
		padding: var(--sp-2) var(--sp-4) 0;
	}
	footer {
		display: flex;
		align-items: center;
		justify-content: flex-end;
		gap: var(--sp-2);
		padding: var(--sp-3) var(--sp-4);
		font-size: var(--fs-1);
	}
</style>
