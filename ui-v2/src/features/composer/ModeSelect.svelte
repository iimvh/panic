<script lang="ts">
	import type { Mode } from '$lib/core/summary';
	import { chat } from '$lib/state/chat.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Dialog from '$lib/ui/Dialog.svelte';
	import Select, { type Option } from '$lib/ui/Select.svelte';

	/**
	 * How this chat runs tools, beside the message box where the next message is decided. Auto-run is confirmed in a
	 * dialog: the agent then acts on the machine without asking. Shown in red while on, nowhere else.
	 */
	let problem = $state<string | null>(null);
	let confirming = $state(false);
	let switching = $state(false);

	const options = $derived(
		[
			{ value: 'ask', label: 'Ask first', hint: 'Tools ask before they write, run commands or use the network', shown: true },
			{ value: 'plan', label: 'Plan', hint: 'Research read-only, then propose a plan to approve', shown: !!chat.mode },
			{ value: 'auto', label: 'Auto-run', hint: 'Tools run without asking', shown: !!chat.permissions }
		].filter((o) => o.shown) satisfies Option[]
	);
	const mode = $derived(chat.runMode);

	function pick(to: string) {
		if (to === 'auto') confirming = true;
		else void apply(to as Mode);
	}

	async function apply(to: Mode) {
		confirming = false;
		switching = true;
		problem = await chat.setRunMode(to);
		switching = false;
	}
</script>

{#if mode && options.length > 1}
	<Select
		value={mode}
		{options}
		label="Mode of this chat"
		tone={mode === 'auto' ? 'danger' : undefined}
		disabled={!!chat.offline || chat.busy || switching}
		onChange={pick}
	/>
	{#if problem}<span class="problem" role="status">{problem}</span>{/if}
{/if}

<Dialog bind:open={confirming} title="Run tools without asking?" width={420}>
	<p>
		In this chat the agent will write files, run commands and use the network without stopping for you. Tools denied in the
		config stay denied. You can switch back at any time.
	</p>
	{#snippet footer()}
		<Button onclick={() => (confirming = false)}>Keep asking</Button>
		<Button variant="danger" onclick={() => apply('auto')}>Auto-run</Button>
	{/snippet}
</Dialog>

<style>
	p {
		margin: 0;
		color: var(--text-2);
	}
	.problem {
		color: var(--danger);
		font-size: var(--fs-1);
	}
</style>
