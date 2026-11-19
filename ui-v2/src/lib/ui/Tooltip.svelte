<script lang="ts">
	import { Tooltip } from 'bits-ui';
	import type { Snippet } from 'svelte';

	/** A short hint on hover or focus. The wrapped element is the trigger (`trigger` gets its props). */
	let { text, side = 'bottom', trigger }: { text: string; side?: 'top' | 'bottom' | 'left' | 'right'; trigger: Snippet<[Record<string, unknown>]> } =
		$props();
</script>

<Tooltip.Root>
	<Tooltip.Trigger>
		{#snippet child({ props })}
			{@render trigger(props)}
		{/snippet}
	</Tooltip.Trigger>
	<Tooltip.Portal>
		<Tooltip.Content class="ui-tooltip" {side} sideOffset={6}>{text}</Tooltip.Content>
	</Tooltip.Portal>
</Tooltip.Root>

<style>
	:global(.ui-tooltip) {
		z-index: 60;
		padding: var(--sp-1) var(--sp-2);
		border-radius: var(--radius-sm);
		background: var(--text);
		color: var(--bg);
		font-size: var(--fs-1);
		line-height: 1.4;
		max-width: 280px;
	}
</style>
