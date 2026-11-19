<script lang="ts">
	import { renderMarkdown } from '$lib/core/markdown';

	/** One text part of an answer. Its own component, so only the part being streamed renders its markdown again. */
	let { text }: { text: string } = $props();

	const html = $derived(renderMarkdown(text));
</script>

<div class="md">{@html html}</div>
