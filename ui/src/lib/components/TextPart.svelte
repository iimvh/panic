<script lang="ts">
	import { renderMarkdown } from '$lib/markdown';

	/** One text part of an answer. Its own component, so only the part being streamed renders its markdown again. */
	let { text, live = false }: { text: string; live?: boolean } = $props();

	const html = $derived(renderMarkdown(text));
</script>

<div class="md" class:live>{@html html}</div>

<style>
	/* a caret after the last block while the text is still coming */
	.live > :global(:last-child::after) {
		content: '';
		display: inline-block;
		width: 0.5em;
		height: 1em;
		margin-left: 2px;
		vertical-align: text-bottom;
		background: currentColor;
		opacity: 0.6;
		animation: caret 1s steps(1) infinite;
	}
	@keyframes caret {
		50% {
			opacity: 0;
		}
	}
</style>
