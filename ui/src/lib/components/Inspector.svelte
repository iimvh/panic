<script lang="ts">
	import { tick } from 'svelte';
	import { chat } from '$lib/chat.svelte';
	import { findStep, reasoningLabel, shellCommand, size } from '$lib/reply';
	import TraceView from './TraceView.svelte';

	/**
	 * The detail of what the user picked in the thread, beside it: the whole thinking of a step (following it while it
	 * streams), a tool call with its arguments and result, or an answer's trace. The thread keeps one line per step,
	 * in the order things happened; this panel is where they are read in full. Closed by default.
	 */
	const what = $derived(chat.inspected);
	const part = $derived(what?.kind === 'part' ? what.part : null);
	const step = $derived(what?.kind === 'tool' ? findStep(chat.messages, what.messageId, what.id) : undefined);

	let now = $state(Date.now());
	$effect(() => {
		if (part?.kind !== 'reasoning' || part.endedAt !== undefined) return;
		const timer = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(timer);
	});

	// follow streamed thinking while the reader is at the bottom, as the thread does
	let body = $state<HTMLElement>();
	let stick = true;
	function onscroll() {
		if (body) stick = body.scrollHeight - body.scrollTop - body.clientHeight < 40;
	}
	$effect(() => {
		void (part?.kind === 'reasoning' ? part.text.length : 0);
		if (stick) tick().then(() => body?.scrollTo({ top: body.scrollHeight }));
	});
	$effect(() => {
		void what;
		stick = true;
	});

	const title = $derived.by(() => {
		if (!what) return '';
		if (what.kind === 'trace') return 'Trace';
		const who = what.agent ? `${what.agent} · ` : '';
		if (part?.kind === 'reasoning') return `${who}${reasoningLabel(part, now)}`;
		if (what.kind === 'tool') return `${who}${step?.name || 'tool call'}`;
		return who + 'Detail';
	});

	/** JSON arguments, indented when they parse. */
	function pretty(args: string): string {
		try {
			return JSON.stringify(JSON.parse(args), null, 2);
		} catch {
			return args;
		}
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.key === 'Escape' && chat.inspected) chat.inspected = null;
	}
</script>

<svelte:window {onkeydown} />

{#if what}
	<button class="scrim" aria-label="Close details" onclick={() => (chat.inspected = null)}></button>
{/if}
<aside class:open={!!what} aria-label="Details" aria-hidden={!what}>
	{#if what}
		<header>
			<span class="title">{title}</span>
			<button class="close" onclick={() => (chat.inspected = null)} aria-label="Close details">
				<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"
					><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg
				>
			</button>
		</header>
		<div class="body" bind:this={body} {onscroll}>
			{#if what.kind === 'trace'}
				<TraceView session={chat.session} traceId={what.traceId} />
			{:else if part?.kind === 'reasoning'}
				<p class="note">What the model thought before it wrote or acted. Shown here only: it is not saved with the chat.</p>
				<div class="thinking">{part.text.trim()}</div>
			{:else if what.kind === 'tool' && !step}
				<p class="note">This call is no longer in the chat.</p>
			{:else if step}
				<dl>
					<dt>Status</dt>
					<dd class="status {step.status}">{step.status}</dd>
					<dt>{shellCommand(step.arguments) ? 'Command' : 'Arguments'} <span class="size">{size(step.arguments)}</span></dt>
					<dd><pre>{shellCommand(step.arguments) ?? pretty(step.arguments)}</pre></dd>
					{#if step.content !== undefined}
						<dt>Result <span class="size">{size(step.content)}</span></dt>
						<dd><pre>{step.content}</pre></dd>
					{/if}
					{#if step.agent}
						<dt>Sub-agent {step.agent.name}</dt>
						<dd>
							{step.agent.parts.filter((p) => p.kind === 'tool').length} tool calls{#if step.agent.text}; it wrote:{/if}
							{#if step.agent.text}<pre>{step.agent.text}</pre>{/if}
						</dd>
					{/if}
				</dl>
			{/if}
		</div>
	{/if}
</aside>

<style>
	aside {
		flex: none;
		display: none;
		flex-direction: column;
		width: 400px;
		height: 100dvh;
		border-left: 1px solid var(--border);
		background: var(--surface);
	}
	aside.open {
		display: flex;
	}
	header {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 10px 12px 10px 16px;
		border-bottom: 1px solid var(--border);
	}
	.title {
		flex: 1;
		min-width: 0;
		font-weight: 600;
		font-size: 14px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.close {
		display: grid;
		place-items: center;
		width: 28px;
		height: 28px;
		border: 0;
		border-radius: 6px;
		background: none;
		color: var(--muted);
		cursor: pointer;
	}
	.close:hover {
		color: var(--text);
		background: var(--surface-2);
	}
	.close:focus-visible {
		outline: 2px solid var(--accent);
	}
	.body {
		flex: 1;
		overflow-y: auto;
		padding: 12px 16px 24px;
		font-size: 13px;
		overflow-wrap: anywhere;
	}
	.note {
		margin: 0 0 10px;
		color: var(--muted);
		font-size: 12px;
	}
	.thinking {
		white-space: pre-wrap;
		line-height: 1.55;
	}
	dl {
		margin: 0;
	}
	dt {
		margin: 12px 0 4px;
		font-weight: 600;
	}
	dt:first-child {
		margin-top: 0;
	}
	.size {
		font-weight: 400;
		color: var(--muted);
		font-size: 12px;
	}
	dd {
		margin: 0;
	}
	pre {
		margin: 4px 0 0;
		padding: 8px 10px;
		border-radius: 8px;
		background: var(--surface-2);
		font: 12px/1.5 var(--mono);
		white-space: pre-wrap;
	}
	.status.error {
		color: var(--danger);
	}
	.status.ok {
		color: var(--ok);
	}
	.scrim {
		display: none;
	}
	/* narrower screens: a drawer over the thread, as the chat list is */
	@media (max-width: 1199px) {
		aside {
			display: flex;
			position: fixed;
			inset: 0 0 0 auto;
			z-index: 20;
			width: min(420px, 92vw);
			transform: translateX(100%);
			visibility: hidden;
			transition:
				transform 0.18s ease,
				visibility 0.18s;
			box-shadow: 0 0 24px rgb(0 0 0 / 0.2);
		}
		aside.open {
			transform: none;
			visibility: visible;
		}
		.scrim {
			display: block;
			position: fixed;
			inset: 0;
			z-index: 19;
			border: 0;
			background: rgb(0 0 0 / 0.35);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		aside {
			transition: none;
		}
	}
</style>
