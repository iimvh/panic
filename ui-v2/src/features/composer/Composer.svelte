<script lang="ts">
	import { chat } from '$lib/state/chat.svelte';
	import Icon from '$lib/ui/Icon.svelte';
	import ModeSelect from './ModeSelect.svelte';

	/** The message box: Enter sends, Shift+Enter breaks the line, "/" suggests the commands available now. */
	let { autofocus = false }: { autofocus?: boolean } = $props();

	let text = $state('');
	let box = $state<HTMLTextAreaElement | null>(null);
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
		if (!box) return;
		box.style.height = 'auto';
		box.style.height = `${box.scrollHeight}px`;
	});

	$effect(() => {
		if (autofocus) box?.focus();
	});

	function complete(name: string) {
		text = `/${name} `;
		box?.focus();
	}

	async function submit() {
		const value = text;
		if (!value.trim() || chat.busy) return;
		text = '';
		await chat.send(value);
		box?.focus();
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.isComposing) return; // an IME (Vietnamese, Japanese, ...) is still composing
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
				e.stopPropagation();
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
		<ul class="suggest" role="listbox" id="commands" aria-label="Commands">
			{#each suggestions as c, i (c.name)}
				<li role="option" id="cmd-{c.name}" aria-selected={i === selected}>
					<button type="button" tabindex="-1" class:sel={i === selected} onmousedown={(e) => (e.preventDefault(), complete(c.name))}>
						<code>/{c.usage}</code><span>{c.description}</span>
					</button>
				</li>
			{/each}
		</ul>
	{/if}
	<form onsubmit={(e) => (e.preventDefault(), submit())}>
		<textarea
			bind:this={box}
			bind:value={text}
			{onkeydown}
			rows="1"
			placeholder={chat.state?.connected === false ? 'Type / for commands' : 'Message Panic'}
			aria-label="Message"
			aria-autocomplete="list"
			aria-controls={suggestions.length ? 'commands' : undefined}
			aria-activedescendant={suggestions.length ? `cmd-${suggestions[selected]?.name}` : undefined}
		></textarea>
		<div class="bar">
			<ModeSelect />
			<span class="spacer"></span>
			{#if chat.busy}
				<button type="button" class="send stop" onclick={() => chat.stop()} aria-label="Stop"><Icon name="stop" size={14} /></button>
			{:else}
				<button type="submit" class="send" disabled={!text.trim()} aria-label="Send"><Icon name="arrow-up" /></button>
			{/if}
		</div>
	</form>
</div>

<style>
	.composer {
		position: relative;
		max-width: var(--thread-w);
		width: 100%;
		margin: 0 auto;
		padding: 0 var(--sp-5) var(--sp-4);
	}
	form {
		border: 1px solid var(--border-strong);
		border-radius: var(--radius-lg);
		background: var(--bg);
	}
	form:focus-within {
		border-color: var(--text-2);
	}
	textarea {
		display: block;
		width: 100%;
		max-height: 40vh;
		padding: var(--sp-3) var(--sp-3) var(--sp-1);
		border: 0;
		background: none;
		resize: none;
		outline: none;
		line-height: 1.5;
	}
	textarea::placeholder {
		color: var(--muted);
	}
	.bar {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		padding: var(--sp-1) var(--sp-2) var(--sp-2);
	}
	.spacer {
		flex: 1;
	}
	.send {
		display: inline-grid;
		place-items: center;
		width: 28px;
		height: 28px;
		border: 0;
		border-radius: var(--radius);
		background: var(--accent-bg);
		color: var(--accent-fg);
	}
	.send:disabled {
		background: var(--surface-2);
		color: var(--muted);
	}
	.send.stop {
		background: var(--text);
		color: var(--bg);
	}
	.suggest {
		position: absolute;
		left: var(--sp-5);
		right: var(--sp-5);
		bottom: 100%;
		margin: 0 0 var(--sp-1);
		padding: var(--sp-1);
		list-style: none;
		max-height: 280px;
		overflow-y: auto;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		box-shadow: var(--shadow-pop);
		z-index: 10;
	}
	.suggest button {
		display: flex;
		gap: var(--sp-3);
		width: 100%;
		padding: var(--sp-1) var(--sp-2);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		text-align: left;
		font-size: var(--fs-2);
	}
	.suggest button.sel {
		background: var(--surface-2);
	}
	.suggest code {
		flex: none;
		color: var(--text);
	}
	.suggest span {
		color: var(--muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
