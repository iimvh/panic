<script lang="ts">
	import { Tabs } from 'bits-ui';
	import type { Snippet } from 'svelte';

	/** Tabs with arrow-key navigation; `content` renders the panel of the tab shown. */
	let {
		value,
		tabs,
		label,
		onChange,
		actions,
		content
	}: {
		value: string;
		tabs: { value: string; label: string }[];
		label: string;
		onChange: (value: string) => void;
		actions?: Snippet;
		content: Snippet<[string]>;
	} = $props();
</script>

<Tabs.Root {value} onValueChange={(v) => v !== value && onChange(v)} class="ui-tabs">
	<div class="ui-tabs-bar">
		<Tabs.List class="ui-tabs-list" aria-label={label}>
			{#each tabs as t (t.value)}
				<Tabs.Trigger class="ui-tab" value={t.value}>{t.label}</Tabs.Trigger>
			{/each}
		</Tabs.List>
		{@render actions?.()}
	</div>
	{#each tabs as t (t.value)}
		<Tabs.Content class="ui-tab-panel" value={t.value}>
			{#if t.value === value}{@render content(t.value)}{/if}
		</Tabs.Content>
	{/each}
</Tabs.Root>

<style>
	:global(.ui-tabs) {
		display: flex;
		flex-direction: column;
		min-height: 0;
		height: 100%;
	}
	:global(.ui-tabs-bar) {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--sp-2);
		height: var(--bar-h);
		padding: 0 var(--sp-2) 0 var(--sp-3);
		border-bottom: 1px solid var(--border);
		flex: none;
	}
	:global(.ui-tabs-list) {
		display: flex;
		gap: var(--sp-1);
		height: 100%;
	}
	:global(.ui-tab) {
		height: 100%;
		padding: 0 var(--sp-2);
		border: 0;
		border-bottom: 2px solid transparent;
		background: none;
		color: var(--muted);
		font-size: var(--fs-2);
		font-weight: 500;
	}
	:global(.ui-tab:hover) {
		color: var(--text);
	}
	:global(.ui-tab[data-state='active']) {
		color: var(--text);
		border-bottom-color: var(--text);
	}
	:global(.ui-tab-panel) {
		flex: 1;
		min-height: 0;
		overflow-y: auto;
		outline: none;
	}
</style>
