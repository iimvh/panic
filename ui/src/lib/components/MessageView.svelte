<script lang="ts">
	import type { Message } from '$lib/chat.svelte';
	import { renderMarkdown } from '$lib/markdown';
	import ApprovalCard from './ApprovalCard.svelte';

	let { message }: { message: Message } = $props();

	const html = $derived(message.role === 'assistant' && !message.error ? renderMarkdown(message.text) : '');

	async function onclick(e: MouseEvent) {
		const button = (e.target as HTMLElement).closest('[data-copy]') as HTMLButtonElement | null;
		if (!button) return;
		const code = button.parentElement?.querySelector('code')?.textContent ?? '';
		try {
			await navigator.clipboard.writeText(code);
			button.textContent = 'Copied';
		} catch {
			button.textContent = 'Failed';
		}
		setTimeout(() => (button.textContent = 'Copy'), 1500);
	}
</script>

<article class={message.role} class:error={message.error}>
	{#if message.role === 'user'}
		<div class="bubble">{message.text}</div>
	{:else if message.role === 'command'}
		<div class="command">
			<div class="line">{message.meta}</div>
			{#if message.text}<pre>{message.text.replace(/\n$/, '')}</pre>{/if}
			<ApprovalCard {message} />
		</div>
	{:else}
		<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
		<div class="answer" {onclick}>
			{#if message.tools?.length}
				<ul class="tools">
					{#each message.tools as tool, i (i)}
						<li class={tool.status} title={tool.content ?? tool.arguments}>
							<span class="name">{tool.name}</span>
							<span class="args">{tool.arguments}</span>
							<span class="status">{tool.status === 'running' ? '…' : tool.status}</span>
						</li>
					{/each}
				</ul>
			{/if}
			<ApprovalCard {message} />
			{#if message.error}
				<p class="err">{message.text}</p>
			{:else if message.text}
				<div class="md">{@html html}</div>
			{/if}
			{#if message.streaming && !message.text}
				<span class="typing" aria-label="Thinking"><i></i><i></i><i></i></span>
			{/if}
			{#if message.meta && !message.streaming}<div class="meta">{message.meta}</div>{/if}
		</div>
	{/if}
</article>

<style>
	article {
		display: flex;
		margin: 0 0 18px;
	}
	.user {
		justify-content: flex-end;
	}
	.bubble {
		max-width: min(80%, 640px);
		background: var(--surface-2);
		border: 1px solid var(--border);
		border-radius: 18px 18px 4px 18px;
		padding: 8px 14px;
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.answer {
		min-width: 0;
		width: 100%;
		overflow-wrap: anywhere;
	}
	.tools {
		list-style: none;
		margin: 0 0 8px;
		padding: 0;
		display: grid;
		gap: 4px;
	}
	.tools li {
		display: flex;
		gap: 8px;
		align-items: baseline;
		min-width: 0;
		font: 12px var(--mono);
		color: var(--muted);
		border-left: 2px solid var(--border);
		padding-left: 8px;
	}
	.tools .name {
		color: var(--text);
	}
	.tools .args {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.tools .error {
		border-left-color: var(--danger);
	}
	.tools .error .status {
		color: var(--danger);
	}
	.meta {
		margin-top: 6px;
		font-size: 12px;
		color: var(--muted);
	}
	.err {
		margin: 0;
		color: var(--danger);
		white-space: pre-wrap;
	}
	.command {
		width: 100%;
		border: 1px dashed var(--border);
		border-radius: var(--radius);
		padding: 8px 12px;
		background: var(--surface);
	}
	.command .line {
		font: 13px var(--mono);
		color: var(--muted);
	}
	.command pre {
		margin: 6px 0 0;
		font: 13px/1.5 var(--mono);
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.error .command pre,
	.error .command {
		border-color: color-mix(in srgb, var(--danger) 40%, var(--border));
	}
	.error .command pre {
		color: var(--danger);
	}
	.typing {
		display: inline-flex;
		gap: 4px;
		padding: 8px 0;
	}
	.typing i {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--muted);
		animation: blink 1.2s infinite ease-in-out;
	}
	.typing i:nth-child(2) {
		animation-delay: 0.15s;
	}
	.typing i:nth-child(3) {
		animation-delay: 0.3s;
	}
	@keyframes blink {
		0%,
		80%,
		100% {
			opacity: 0.25;
		}
		40% {
			opacity: 1;
		}
	}
</style>
