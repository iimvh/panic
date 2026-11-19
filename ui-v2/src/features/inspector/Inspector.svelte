<script lang="ts">
	import { inspector, type Panel } from '$lib/state/inspector.svelte';
	import IconButton from '$lib/ui/IconButton.svelte';
	import Tabs from '$lib/ui/Tabs.svelte';
	import Overview from './Overview.svelte';
	import StepView from './StepView.svelte';
	import TraceView from './TraceView.svelte';

	/** The details behind the conversation, one tab each; which tab and what it shows are in the URL. */
	const TABS = [
		{ value: 'overview', label: 'Overview' },
		{ value: 'step', label: 'Steps' },
		{ value: 'trace', label: 'Trace' }
	];
</script>

<aside aria-label="Inspector">
	<Tabs value={inspector.panel ?? 'overview'} tabs={TABS} label="Inspector" onChange={(v) => inspector.open(v as Panel)}>
		{#snippet actions()}
			<IconButton icon="x" label="Close inspector" shortcut="Esc" onclick={() => inspector.close()} />
		{/snippet}
		{#snippet content(tab)}
			{#if tab === 'overview'}<Overview />{:else if tab === 'step'}<StepView />{:else}<TraceView />{/if}
		{/snippet}
	</Tabs>
</aside>

<style>
	aside {
		display: flex;
		flex-direction: column;
		height: 100%;
		min-height: 0;
	}
</style>
