<script lang="ts">
	import { chat, type Message } from '$lib/chat.svelte';
	import { activity, duration } from '$lib/reply';
	import ApprovalCard from './ApprovalCard.svelte';
	import TextPart from './TextPart.svelte';
	import ToolRow from './ToolRow.svelte';
	import TraceView from './TraceView.svelte';

	let { message }: { message: Message } = $props();

	const parts = $derived(message.parts ?? []);
	const status = $derived(message.streaming ? activity(message) : '');
	let showTrace = $state(false);

	let now = $state(Date.now());
	$effect(() => {
		if (!message.streaming) return;
		now = Date.now();
		const timer = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(timer);
	});

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
			{#each parts as part, i (i)}
				{#if part.kind === 'text'}
					<TextPart text={part.text} live={message.streaming && i === parts.length - 1} />
				{:else if part.kind === 'tool'}
					<ToolRow step={part.step} />
				{:else if part.kind === 'review'}
					<div class="review" class:rejected={!part.pass}>
						{part.pass ? 'Review passed' : `Review rejected the answer: ${part.reason}`}
					</div>
				{:else}
					<p class="err">{part.text}</p>
				{/if}
			{/each}
			<ApprovalCard {message} />
			{#if message.streaming}
				<div class="progress" aria-live="polite">
					<span class="typing" aria-hidden="true"><i></i><i></i><i></i></span>
					{status}
					{#if message.startedAt}<span class="time">{duration(now - message.startedAt)}</span>{/if}
				</div>
			{/if}
			{#if !message.streaming && (message.meta || message.traceId)}
				<div class="meta">
					{#if message.meta}<span>{message.meta}</span>{/if}
					{#if message.traceId}
						<button type="button" class="trace-toggle" aria-expanded={showTrace} onclick={() => (showTrace = !showTrace)}>
							{showTrace ? 'Hide trace' : 'Trace'}
						</button>
					{/if}
				</div>
				{#if showTrace && message.traceId}<TraceView session={chat.session} traceId={message.traceId} />{/if}
			{/if}
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
	.answer > :global(.tool + .md),
	.answer > :global(details + .md),
	.review + :global(.md) {
		margin-top: 8px;
	}
	.review {
		margin: 8px 0;
		padding: 4px 8px;
		font-size: 13px;
		color: var(--muted);
		border-radius: 6px;
		background: var(--surface);
	}
	.review.rejected {
		color: var(--text);
		background: color-mix(in srgb, var(--warn) 14%, var(--surface));
	}
	.progress {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 13px;
		color: var(--muted);
	}
	.progress .time {
		font-variant-numeric: tabular-nums;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 4px 10px;
		margin-top: 6px;
		font-size: 12px;
		color: var(--muted);
	}
	.trace-toggle {
		padding: 0;
		border: 0;
		background: none;
		color: var(--muted);
		font: inherit;
		text-decoration: underline dotted;
		text-underline-offset: 3px;
		cursor: pointer;
	}
	.trace-toggle:hover,
	.trace-toggle[aria-expanded='true'] {
		color: var(--text);
	}
	.trace-toggle:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: 2px;
		border-radius: 3px;
	}
	.err {
		margin: 8px 0 0;
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
