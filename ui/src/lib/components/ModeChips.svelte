<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import { view } from '$lib/view.svelte';
	import type { Mode } from '$lib/scene';

	/**
	 * The mode of this chat, under the message box where the next message is decided, as one switch: Ask first (tools
	 * ask before they write, run commands or use the network), Plan (plan mode: research read-only, then propose a
	 * plan) or Auto-run (bypass: tools run unasked). It lasts for the whole chat, not one message. Auto-run is asked
	 * again in a dialog, since the agent then acts on the machine without asking.
	 */
	let problem = $state<string | null>(null);
	let confirming = $state(false);
	let switching = $state(false);

	const MODES: { id: Mode; label: string; title: string; shown: () => boolean }[] = [
		{
			id: 'ask',
			label: 'Ask first',
			title: 'For this chat: tools ask before they write, run commands or use the network.',
			shown: () => !!chat.permissions || !!chat.mode
		},
		{
			id: 'plan',
			label: 'Plan',
			title: 'For this chat: the agent researches read-only and proposes a plan for you to approve.',
			shown: () => !!chat.mode
		},
		{
			id: 'auto',
			label: 'Auto-run',
			title: 'For this chat: tools write, run commands and use the network without asking.',
			shown: () => !!chat.permissions
		}
	];
	const shown = $derived(MODES.filter((m) => m.shown()));
	const locked = $derived(!!chat.offline || chat.busy || switching);

	function pick(mode: Mode) {
		if (mode === view.mode) return;
		if (mode === 'auto') confirming = true;
		else void apply(mode);
	}

	/** Plan mode and bypass are two server switches; each mode sets both, so they never fight. */
	async function apply(mode: Mode) {
		confirming = false;
		switching = true;
		problem = null;
		try {
			if (chat.mode && chat.mode.plan !== (mode === 'plan')) problem = await chat.setPlanMode(mode === 'plan');
			if (!problem && chat.permissions && chat.permissions.bypass !== (mode === 'auto')) problem = await chat.setBypass(mode === 'auto');
		} finally {
			switching = false;
		}
	}

	function onkeydown(e: KeyboardEvent) {
		if (confirming && e.key === 'Escape') {
			e.stopPropagation();
			confirming = false;
		}
	}
</script>

<svelte:window {onkeydown} />

<div class="row">
{#if shown.length > 1}
	<div class="modes" role="radiogroup" aria-label="Mode of this chat">
		{#each shown as m (m.id)}
			<button
				type="button"
				role="radio"
				class="seg {m.id}"
				class:on={view.mode === m.id}
				aria-checked={view.mode === m.id}
				disabled={locked}
				title={problem ?? (chat.busy ? 'Can be changed once the answer is done.' : m.title)}
				onclick={() => pick(m.id)}
			>
				{m.label}
			</button>
		{/each}
	</div>
{/if}
{#if problem}<span class="problem" role="status">{problem}</span>{/if}
</div>

{#if confirming}
	<div class="backdrop" role="presentation" onclick={() => (confirming = false)}></div>
	<div class="dialog" role="alertdialog" aria-modal="true" aria-labelledby="auto-title" aria-describedby="auto-text">
		<span class="label-caps">Auto-run</span>
		<h2 id="auto-title">Let tools run without asking in this chat?</h2>
		<p id="auto-text">
			The agent will write files, run commands and use the network without stopping for you. Tools denied in the config
			stay denied. You can switch back to Ask first at any time.
		</p>
		<div class="actions">
			<!-- svelte-ignore a11y_autofocus -->
			<button class="btn" autofocus onclick={() => (confirming = false)}>Keep asking</button>
			<button class="btn danger" onclick={() => apply('auto')}>Auto-run</button>
		</div>
	</div>
{/if}

<style>
	.row {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.modes {
		display: inline-flex;
		flex: none;
		border: 1px solid var(--edge);
		border-radius: 8px;
		overflow: hidden;
		box-shadow: var(--lift);
	}
	.seg {
		border: 0;
		background: var(--surface);
		color: var(--muted);
		padding: 2px 10px;
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
	}
	.seg + .seg {
		border-left: 1px solid var(--edge);
	}
	.seg:hover:not(:disabled):not(.on) {
		background: var(--surface-2);
		color: var(--text);
	}
	.seg.on {
		color: var(--text);
	}
	.ask.on {
		background: var(--green);
	}
	.plan.on {
		background: var(--blue);
	}
	.auto.on {
		background: var(--rust);
		color: #fff;
	}
	.seg:disabled {
		cursor: default;
	}
	.seg:disabled:not(.on) {
		opacity: 0.6;
	}
	.problem {
		font-size: 12px;
		color: var(--danger);
	}
	.backdrop {
		position: fixed;
		inset: 0;
		z-index: 40;
		background: rgb(29 27 24 / 0.35);
	}
	.dialog {
		position: fixed;
		z-index: 41;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: min(440px, calc(100vw - 32px));
		padding: 18px 20px;
		border: 1.5px solid var(--text);
		border-radius: 12px;
		background: var(--surface);
		box-shadow: 3px 4px 0 var(--text);
	}
	h2 {
		margin: 4px 0 8px;
		font-size: 16px;
	}
	p {
		margin: 0 0 16px;
		font-size: 13px;
		color: var(--muted);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 8px;
	}
	.btn.danger {
		background: var(--rust);
		border-color: var(--text);
		color: #fff;
		box-shadow: var(--pop);
	}
</style>
