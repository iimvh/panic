<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import ModeChips from './ModeChips.svelte';

	let text = $state('');
	let box: HTMLTextAreaElement;
	let selected = $state(0);

	// "/mo" → suggest commands, until the first space
	const suggestions = $derived.by(() => {
		if (!text.startsWith('/') || /\s/.test(text)) return [];
		const typed = text.slice(1).toLowerCase();
		return (chat.state?.commands ?? []).filter((c) => c.name.startsWith(typed) && c.name !== 'exit');
	});

	$effect(() => {
		void suggestions.length;
		selected = 0;
	});

	$effect(() => {
		// grow with the text, up to the max height in CSS
		void text;
		box.style.height = 'auto';
		box.style.height = `${box.scrollHeight}px`;
	});

	function complete(name: string) {
		text = `/${name} `;
		box.focus();
	}

	async function submit() {
		const value = text;
		if (!value.trim() || chat.busy) return;
		text = '';
		await chat.send(value);
		box?.focus();
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.isComposing) return; // IME (e.g. Vietnamese, Japanese) is still composing
		if (suggestions.length) {
			if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
				e.preventDefault();
				const n = suggestions.length;
				selected = (selected + (e.key === 'ArrowDown' ? 1 : n - 1)) % n;
				return;
			}
			if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey && text !== `/${suggestions[selected].name}`)) {
				e.preventDefault();
				complete(suggestions[selected].name);
				return;
			}
			if (e.key === 'Escape') {
				text = '';
				return;
			}
		}
		if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			void submit();
		}
	}
</script>

<div class="composer">
	{#if suggestions.length}
		<ul class="suggest" role="listbox">
			{#each suggestions as c, i (c.name)}
				<li role="option" aria-selected={i === selected}>
					<button type="button" class:sel={i === selected} onmousedown={(e) => (e.preventDefault(), complete(c.name))}>
						<code>/{c.usage}</code><span>{c.description}</span>
					</button>
				</li>
			{/each}
		</ul>
	{/if}
	<form class:danger={chat.permissions?.bypass} onsubmit={(e) => (e.preventDefault(), submit())}>
		<textarea
			bind:this={box}
			bind:value={text}
			{onkeydown}
			rows="1"
			placeholder={chat.state?.connected ? 'Message Panic, or type / for commands' : 'Type / for commands'}
			aria-label="Message"
			title="Enter to send · Shift+Enter for a new line"
		></textarea>
		{#if chat.busy}
			<button type="button" class="btn send" onclick={() => chat.stop()} aria-label="Stop">
				<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" /></svg>
			</button>
		{:else}
			<button type="submit" class="btn primary send" disabled={!text.trim()} aria-label="Send">
				<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"
					><path d="M12 19V5M5 12l7-7 7 7" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" /></svg
				>
			</button>
		{/if}
	</form>
	<div class="below">
		<ModeChips />
		<span class="hint">Shift+Enter for a new line</span>
	</div>
</div>

<style>
	.composer {
		position: relative;
		width: 100%;
		max-width: 760px;
		margin: 0 auto;
		padding: 0 16px 12px;
	}
	form {
		display: flex;
		align-items: flex-end;
		gap: 8px;
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: 16px;
		padding: 8px 8px 8px 14px;
		box-shadow: var(--shadow);
	}
	form:focus-within {
		border-color: color-mix(in srgb, var(--accent) 50%, var(--border));
	}
	/* bypass is on: the danger shows where the next message is written */
	form.danger,
	form.danger:focus-within {
		border-color: var(--danger);
	}
	textarea {
		flex: 1;
		resize: none;
		border: 0;
		outline: 0;
		background: none;
		max-height: 220px;
		padding: 6px 0;
		line-height: 1.5;
	}
	.send {
		flex: none;
		width: 36px;
		height: 36px;
		justify-content: center;
		padding: 0;
		border-radius: 10px;
	}
	.below {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		margin-top: 6px;
		min-height: 22px;
	}
	.hint {
		flex: none;
		font-size: 12px;
		color: var(--muted);
	}
	@media (max-width: 520px) {
		.hint {
			display: none;
		}
	}
	.suggest {
		position: absolute;
		left: 16px;
		right: 16px;
		bottom: calc(100% - 4px);
		margin: 0;
		padding: 6px;
		list-style: none;
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: 12px;
		box-shadow: var(--shadow);
		max-height: 280px;
		overflow-y: auto;
	}
	.suggest button {
		display: flex;
		gap: 12px;
		align-items: baseline;
		width: 100%;
		text-align: left;
		border: 0;
		background: none;
		border-radius: 8px;
		padding: 6px 10px;
	}
	.suggest button.sel,
	.suggest button:hover {
		background: var(--surface-2);
	}
	.suggest code {
		font: 13px var(--mono);
		white-space: nowrap;
	}
	.suggest span {
		font-size: 13px;
		color: var(--muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
