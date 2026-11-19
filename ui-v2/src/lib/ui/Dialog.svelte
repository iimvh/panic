<script lang="ts">
	import { Dialog } from 'bits-ui';
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';

	/** A modal: focus is trapped inside, Escape and the backdrop close it, focus returns where it was. */
	let {
		open = $bindable(false),
		title,
		description,
		width = 480,
		children,
		footer,
		onclose
	}: {
		open?: boolean;
		title: string;
		description?: string;
		width?: number;
		children: Snippet;
		footer?: Snippet;
		/** Called when the dialog closes itself (Escape, the backdrop, the close button). */
		onclose?: () => void;
	} = $props();
</script>

<Dialog.Root bind:open onOpenChange={(o) => !o && onclose?.()}>
	<Dialog.Portal>
		<Dialog.Overlay class="ui-dialog-overlay" />
		<Dialog.Content class="ui-dialog" style="width: min({width}px, calc(100vw - 32px))">
			<header>
				<Dialog.Title class="ui-dialog-title">{title}</Dialog.Title>
				<Dialog.Close class="ui-icon-btn" aria-label="Close"><Icon name="x" /></Dialog.Close>
			</header>
			{#if description}<Dialog.Description class="ui-dialog-desc">{description}</Dialog.Description>{/if}
			<div class="ui-dialog-body">{@render children()}</div>
			{#if footer}<footer class="ui-dialog-footer">{@render footer()}</footer>{/if}
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>

<style>
	:global(.ui-dialog-overlay) {
		position: fixed;
		inset: 0;
		z-index: 40;
		background: var(--scrim);
	}
	:global(.ui-dialog) {
		position: fixed;
		z-index: 50;
		top: 12vh;
		left: 50%;
		transform: translateX(-50%);
		max-height: 76vh;
		display: flex;
		flex-direction: column;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: var(--radius-lg);
		box-shadow: var(--shadow-pop);
		outline: none;
	}
	:global(.ui-dialog) header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: var(--sp-4) var(--sp-4) var(--sp-2) var(--sp-5);
	}
	:global(.ui-dialog-title) {
		margin: 0;
		font-size: var(--fs-4);
		font-weight: 600;
	}
	:global(.ui-dialog-desc) {
		margin: 0;
		padding: 0 var(--sp-5);
		color: var(--muted);
		font-size: var(--fs-2);
	}
	:global(.ui-dialog-body) {
		padding: var(--sp-3) var(--sp-5) var(--sp-5);
		overflow-y: auto;
	}
	:global(.ui-dialog-footer) {
		display: flex;
		justify-content: flex-end;
		gap: var(--sp-2);
		padding: var(--sp-3) var(--sp-5);
		border-top: 1px solid var(--border);
	}
</style>
