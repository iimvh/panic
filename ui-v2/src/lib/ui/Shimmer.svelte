<script lang="ts">
	import type { Snippet } from 'svelte';

	/**
	 * A line of status text that a light sweeps across while the system works ("Thinking…", "Running exec…"). It
	 * replaces a spinner beside the text: the words themselves say that something runs. Only for the system's own
	 * work, never while it waits for the user. The one place a gradient and this animation are allowed
	 * (src/lib/tokens.test.ts): use this component, do not copy it.
	 *
	 * The sweep goes from --muted to --text and back, both AA on every surface, so the text always reads. Without
	 * motion (reduced motion, forced colours) it is plain muted text.
	 */
	let { children }: { children: Snippet } = $props();
</script>

<span class="ui-shimmer">{@render children()}</span>

<style>
	.ui-shimmer {
		background: linear-gradient(90deg, var(--muted) 0%, var(--muted) 35%, var(--text) 50%, var(--muted) 65%, var(--muted) 100%);
		background-size: 250% 100%;
		-webkit-background-clip: text;
		background-clip: text;
		color: transparent;
		animation: shimmer 1.8s linear infinite;
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
			color: var(--muted);
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
