<script lang="ts">
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import Icon, { type IconName } from './Icon.svelte';
	import Tooltip from './Tooltip.svelte';

	/** A square button with only an icon: `label` names it for screen readers and in its tooltip. */
	type Props = HTMLButtonAttributes & { icon: IconName; label: string; shortcut?: string; pressed?: boolean; tooltip?: boolean };
	let { icon, label, shortcut, pressed, tooltip = true, class: cls = '', type = 'button', ...rest }: Props = $props();
	const hint = $derived(shortcut ? `${label} (${shortcut})` : label);
</script>

{#snippet button(props: Record<string, unknown> = {})}
	<button {...props} {type} class="ui-icon-btn {cls}" aria-label={label} aria-pressed={pressed} {...rest}>
		<Icon name={icon} />
	</button>
{/snippet}

{#if tooltip}
	<Tooltip text={hint}>
		{#snippet trigger(props)}{@render button(props)}{/snippet}
	</Tooltip>
{:else}
	{@render button()}
{/if}

<style>
	:global(.ui-icon-btn) {
		display: inline-grid;
		place-items: center;
		width: 28px;
		height: 28px;
		padding: 0;
		border: 0;
		border-radius: var(--radius);
		background: transparent;
		color: var(--muted);
	}
	:global(.ui-icon-btn:hover:not(:disabled)),
	:global(.ui-icon-btn[aria-pressed='true']) {
		background: var(--surface-2);
		color: var(--text);
	}
	:global(.ui-icon-btn:disabled) {
		opacity: 0.4;
	}
</style>
