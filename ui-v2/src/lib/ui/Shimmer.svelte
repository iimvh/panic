<script lang="ts">
	import type { Snippet } from 'svelte';

	/**
	 * A line of status text that a light sweeps across while the system works ("Thinking…", "Running exec…"). It
	 * replaces a spinner beside the text: the words themselves say that something runs. Only for the system's own
	 * work, never while it waits for the user. The one place a gradient and this animation are allowed
	 * (src/lib/tokens.test.ts): use this component, do not copy it.
	 *
	 * At rest the text is --shimmer-base (AA on every surface); --shimmer-glow is the light passing over it, for a
	 * moment, so it is exempt from contrast (the words read before and after it). Without
	 * motion (reduced motion, forced colours) it is plain muted text.
	 */
	let { children }: { children: Snippet } = $props();
</script>

<span class="ui-shimmer">{@render children()}</span>

<style>
	.ui-shimmer {
		/* a band of light whose centre goes from the left edge to the right edge, so it is always on the text */
		background: linear-gradient(90deg, var(--shimmer-base) 0%, var(--shimmer-base) 40%, var(--shimmer-glow) 50%, var(--shimmer-base) 60%, var(--shimmer-base) 100%);
		background-size: 200% 100%;
		-webkit-background-clip: text;
		background-clip: text;
		color: transparent;
		animation: shimmer 1.6s linear infinite;
	}
	@keyframes shimmer {
		from {
			background-position: 100% 0;
		}
		to {
			background-position: 0% 0;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.ui-shimmer {
			animation: none;
			background: none;
			color: var(--shimmer-base);
		}
	}
	@media (forced-colors: active) {
		.ui-shimmer {
			animation: none;
			background: none;
			color: CanvasText;
		}
	}
</style>
