<script lang="ts" module>
	export type Option = { value: string; label: string; hint?: string };
</script>

<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import Icon from './Icon.svelte';

	/**
	 * A compact choice among a few options, as a menu of radio items (it shares the menu's code, so it costs nothing
	 * extra). `tone` colours the trigger's text, e.g. danger for a risky mode.
	 */
	let {
		value,
		options,
		label,
		tone,
		disabled = false,
		onChange
	}: { value: string; options: Option[]; label: string; tone?: 'danger'; disabled?: boolean; onChange: (value: string) => void } = $props();
	const current = $derived(options.find((o) => o.value === value));
</script>

<DropdownMenu.Root>
	<DropdownMenu.Trigger class="ui-select {tone ?? ''}" aria-label="{label}: {current?.label ?? ''}" {disabled}>
		<span>{current?.label ?? label}</span>
		<Icon name="chevron-down" size={14} />
	</DropdownMenu.Trigger>
	<DropdownMenu.Portal>
		<DropdownMenu.Content class="ui-menu ui-select-content" side="top" align="start" sideOffset={4}>
			<DropdownMenu.RadioGroup {value} onValueChange={(v) => v && v !== value && onChange(v)} aria-label={label}>
				{#each options as o (o.value)}
					<DropdownMenu.RadioItem class="ui-menu-item ui-select-item" value={o.value}>
						{#snippet children({ checked })}
							<span class="check">{#if checked}<Icon name="check" size={14} />{/if}</span>
							<span class="text">
								<span>{o.label}</span>
								{#if o.hint}<span class="hint">{o.hint}</span>{/if}
							</span>
						{/snippet}
					</DropdownMenu.RadioItem>
				{/each}
			</DropdownMenu.RadioGroup>
		</DropdownMenu.Content>
	</DropdownMenu.Portal>
</DropdownMenu.Root>

<style>
	:global(.ui-select) {
		display: inline-flex;
		align-items: center;
		gap: var(--sp-1);
		height: 28px;
		padding: 0 var(--sp-2);
		border: 0;
		border-radius: var(--radius);
		background: transparent;
		color: var(--text-2);
		font-size: var(--fs-2);
	}
	:global(.ui-select:hover:not(:disabled)),
	:global(.ui-select[data-state='open']) {
		background: var(--surface-2);
	}
	:global(.ui-select:disabled) {
		opacity: 0.5;
	}
	:global(.ui-select.danger) {
		color: var(--danger);
	}
	:global(.ui-select-content) {
		min-width: 240px;
	}
	:global(.ui-select-item) {
		align-items: flex-start;
	}
	:global(.ui-select-item) .check {
		width: 14px;
		padding-top: 2px;
		color: var(--accent);
	}
	:global(.ui-select-item) .text {
		display: flex;
		flex-direction: column;
	}
	:global(.ui-select-item) .hint {
		color: var(--muted);
		font-size: var(--fs-1);
	}
</style>
