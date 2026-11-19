<script lang="ts" module>
	import type { IconName } from './Icon.svelte';
	export type MenuItem = { label: string; icon?: IconName; danger?: boolean; disabled?: boolean; onSelect: () => void } | 'separator';
</script>

<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import Icon from './Icon.svelte';

	/** An overflow menu behind a "⋯" button: secondary actions stay out of sight until asked for. */
	let { items, label, align = 'end' }: { items: MenuItem[]; label: string; align?: 'start' | 'end' } = $props();
</script>

<DropdownMenu.Root>
	<DropdownMenu.Trigger class="ui-icon-btn ui-menu-trigger" aria-label={label}><Icon name="more" /></DropdownMenu.Trigger>
	<DropdownMenu.Portal>
		<DropdownMenu.Content class="ui-menu" {align} sideOffset={4}>
			{#each items as item, i (i)}
				{#if item === 'separator'}
					<DropdownMenu.Separator class="ui-menu-sep" />
				{:else}
					<DropdownMenu.Item class="ui-menu-item {item.danger ? 'danger' : ''}" disabled={item.disabled} onSelect={item.onSelect}>
						{#if item.icon}<Icon name={item.icon} size={14} />{/if}
						{item.label}
					</DropdownMenu.Item>
				{/if}
			{/each}
		</DropdownMenu.Content>
	</DropdownMenu.Portal>
</DropdownMenu.Root>

<style>
	:global(.ui-menu) {
		z-index: 50;
		min-width: 180px;
		padding: var(--sp-1);
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		box-shadow: var(--shadow-pop);
		outline: none;
	}
	:global(.ui-menu-item) {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		padding: var(--sp-1) var(--sp-2);
		border-radius: var(--radius-sm);
		font-size: var(--fs-2);
		color: var(--text);
		cursor: default;
		outline: none;
	}
	:global(.ui-menu-item[data-highlighted]) {
		background: var(--surface-2);
	}
	:global(.ui-menu-item[data-disabled]) {
		opacity: 0.5;
	}
	:global(.ui-menu-item.danger) {
		color: var(--danger);
	}
	:global(.ui-menu-sep) {
		height: 1px;
		margin: var(--sp-1) 0;
		background: var(--border);
	}
</style>
