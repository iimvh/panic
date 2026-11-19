<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import Icon, { type IconName } from './Icon.svelte';

	/**
	 * default: bordered, for most actions. primary: the one action a view is about. ghost: quiet, in toolbars and
	 * lists. danger: destroys something (the text says what).
	 */
	type Props = HTMLButtonAttributes & {
		variant?: 'default' | 'primary' | 'ghost' | 'danger';
		size?: 'sm' | 'md';
		icon?: IconName;
		children?: Snippet;
		ref?: HTMLButtonElement | null;
	};
	let { variant = 'default', size = 'md', icon, children, ref = $bindable(null), type = 'button', class: cls = '', ...rest }: Props = $props();
</script>

<button bind:this={ref} {type} class="ui-btn {variant} {size} {cls}" {...rest}>
	{#if icon}<Icon name={icon} size={size === 'sm' ? 14 : 16} />{/if}
	{@render children?.()}
</button>

<style>
	:global(.ui-btn) {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: var(--sp-2);
		height: 32px;
		padding: 0 var(--sp-3);
		border: 1px solid var(--border-strong);
		border-radius: var(--radius);
		background: var(--bg);
		color: var(--text);
		font-size: var(--fs-3);
		font-weight: 500;
		white-space: nowrap;
	}
	:global(.ui-btn.sm) {
		height: 28px;
		padding: 0 var(--sp-2);
		font-size: var(--fs-2);
	}
	:global(.ui-btn:hover:not(:disabled)) {
		background: var(--surface-2);
	}
	:global(.ui-btn:disabled) {
		opacity: 0.5;
	}
	:global(.ui-btn.primary) {
		background: var(--accent-bg);
		border-color: var(--accent-bg);
		color: var(--accent-fg);
	}
	:global(.ui-btn.primary:hover:not(:disabled)) {
		background: var(--accent-bg);
		opacity: 0.9;
	}
	:global(.ui-btn.ghost) {
		border-color: transparent;
		background: transparent;
		color: var(--text-2);
	}
	:global(.ui-btn.danger) {
		color: var(--danger);
	}
</style>
