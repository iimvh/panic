<script lang="ts">
	import { tick } from 'svelte';
	import { chat } from '$lib/chat.svelte';
	import { duration, findStep, reasoningLabel, shellCommand, size } from '$lib/reply';

	/**
	 * The Step tab of the map panel: the whole thinking of a step (following it while it streams), or a tool call with
	 * its arguments and result. The thread keeps one line per step, in the order things happened; this is where they
	 * are read in full.
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
</script>

<div class="body" bind:this={body} {onscroll}>
	{#if !what || what.kind === 'trace'}
		<p class="note">Pick a step: a tool call or a thought in the chat, or a dot on the map's rail. Its arguments and result show here.</p>
	{:else}
		<header>
			<span class="title">{title}</span>
			<button class="close" onclick={() => (chat.inspected = null)} aria-label="Close details" title="Close (Esc)">
				<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"
					><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg
				>
			</button>
		</header>
		{#if part?.kind === 'reasoning'}
			<p class="note">What the model thought before it wrote or acted. Shown here only: it is not saved with the chat.</p>
			<div class="thinking">{part.text.trim()}</div>
		{:else if what.kind === 'tool' && !step}
			<p class="note">This call is no longer in the chat.</p>
		{:else if step}
			<dl>
				<dt>Status</dt>
				<dd class="status {step.status}">
					{step.status}{#if step.startedAt !== undefined && step.endedAt !== undefined}<span class="size">
							· {duration(step.endedAt - step.startedAt)}</span
						>{/if}
				</dd>
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
	{/if}
</div>

<style>
	.body {
		height: 100%;
		overflow-y: auto;
		padding: 12px 16px 24px;
		font-size: 12.5px;
		overflow-wrap: anywhere;
	}
	header {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-bottom: 10px;
	}
	.title {
		flex: 1;
		min-width: 0;
		font-weight: 700;
		font-size: 13px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.close {
		display: grid;
		place-items: center;
		width: 26px;
		height: 26px;
		border: 1px solid var(--border);
		border-radius: 6px;
		background: none;
		color: var(--muted);
	}
	.close:hover {
		color: var(--text);
		background: var(--surface-2);
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
		font-size: 10.5px;
		font-weight: 700;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--muted);
	}
	dt:first-child {
		margin-top: 0;
	}
	.size {
		font-weight: 400;
		letter-spacing: 0;
		text-transform: none;
		color: var(--muted);
		font-size: 11.5px;
	}
	dd {
		margin: 0;
	}
	pre {
		margin: 4px 0 0;
		padding: 8px 10px;
		border: 1px solid var(--border);
		border-radius: 8px;
		background: var(--surface);
		font: 12px/1.5 var(--mono);
		white-space: pre-wrap;
	}
	.status {
		font-weight: 700;
	}
	.status.error {
		color: var(--danger);
	}
	.status.ok {
		color: var(--ok);
	}
</style>
