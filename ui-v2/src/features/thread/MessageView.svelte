<script lang="ts">
	import { activity, canContinue, duration } from '$lib/core/reply';
	import { blocks } from '$lib/core/summary';
	import { chat, type Message } from '$lib/state/chat.svelte';
	import { inspector } from '$lib/state/inspector.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Icon from '$lib/ui/Icon.svelte';
	import Spinner from '$lib/ui/Spinner.svelte';
	import ApprovalCard from './ApprovalCard.svelte';
	import PlanCard from './PlanCard.svelte';
	import QuestionCard from './QuestionCard.svelte';
	import ReasoningPart from './ReasoningPart.svelte';
	import TextPart from './TextPart.svelte';
	import ToolGroup from './ToolGroup.svelte';

	let { message, last = false }: { message: Message; last?: boolean } = $props();

	const parts = $derived(blocks(message.parts ?? []));

	// the one live counter of the interface: seconds while an answer runs
	let now = $state(Date.now());
	$effect(() => {
		if (!message.streaming) return;
		now = Date.now();
		const timer = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(timer);
	});

	/** Model, tokens, tools, time: what the answer cost and did, quiet but always there. */
	const meta = $derived.by(() => {
		const items = message.meta ? message.meta.split(' · ') : [];
		if (message.startedAt !== undefined && message.endedAt !== undefined && !items.some((i) => /^\d+(m \d+)?s$/.test(i)))
			items.push(duration(message.endedAt - message.startedAt));
		return items.join(' · ');
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
		{#if message.text}<div class="bubble">{message.text}</div>{/if}
	{:else if message.role === 'command'}
		<div class="command">
			<code class="line">{message.meta}</code>
			{#if message.text}<pre>{message.text.replace(/\n$/, '')}</pre>{/if}
			<ApprovalCard {message} />
			<PlanCard {message} />
			<QuestionCard {message} />
		</div>
	{:else}
		<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
		<div class="answer" {onclick}>
			{#each parts as b (b.index)}
				{#if b.kind === 'tools'}
					<ToolGroup steps={b.steps} />
				{:else if b.part.kind === 'reasoning'}
					<ReasoningPart part={b.part} />
				{:else if b.part.kind === 'text'}
					<TextPart text={b.part.text} />
				{:else if b.part.kind === 'review'}
					{#if b.part.error !== undefined}
						<p class="note warn">Review failed: {b.part.error}</p>
					{:else if !b.part.pass}
						<p class="note warn">Review sent the answer back: {b.part.reason}</p>
					{/if}
				{:else if b.part.kind === 'error'}
					<p class="note danger"><Icon name="alert" size={14} />{b.part.text}</p>
				{/if}
			{/each}
			<ApprovalCard {message} />
			<PlanCard {message} />
			<QuestionCard {message} />
			{#if message.streaming}
				<div class="progress" aria-live="polite">
					<Spinner />
					<span>{activity(message)}</span>
					{#if message.startedAt}<span class="time">{duration(now - message.startedAt)}</span>{/if}
				</div>
			{:else if meta}
				{#if message.traceId}
					<button type="button" class="meta" title="Show the trace" onclick={() => inspector.open('trace', { trace: message.traceId })}>{meta}</button>
				{:else}
					<div class="meta">{meta}</div>
				{/if}
			{/if}
			{#if last && canContinue(message)}
				<div class="continue"><Button size="sm" disabled={chat.busy} onclick={() => chat.continueAnswer()}>Continue</Button></div>
			{/if}
		</div>
	{/if}
</article>

<style>
	article {
		margin: 0 0 var(--sp-5);
		content-visibility: auto;
		contain-intrinsic-size: auto 120px;
	}
	.bubble {
		margin-left: auto;
		width: fit-content;
		max-width: 85%;
		padding: var(--sp-2) var(--sp-3);
		border-radius: var(--radius-lg);
		background: var(--surface-2);
		white-space: pre-wrap;
		overflow-wrap: anywhere;
	}
	.command .line {
		font-size: var(--fs-2);
		color: var(--muted);
	}
	.command pre {
		margin: var(--sp-1) 0 0;
		padding: var(--sp-2) var(--sp-3);
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		font-size: var(--fs-2);
		white-space: pre-wrap;
		overflow-wrap: anywhere;
		max-height: 480px;
		overflow-y: auto;
	}
	.command.error pre {
		color: var(--danger);
	}
	.answer {
		overflow-wrap: anywhere;
	}
	.note {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		margin: var(--sp-2) 0;
		font-size: var(--fs-2);
	}
	.note.warn {
		color: var(--warn);
	}
	.note.danger {
		color: var(--danger);
	}
	.progress {
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		margin-top: var(--sp-2);
		color: var(--muted);
		font-size: var(--fs-2);
	}
	.time {
		font-variant-numeric: tabular-nums;
	}
	.meta {
		display: block;
		margin-top: var(--sp-2);
		padding: 0;
		border: 0;
		background: none;
		color: var(--muted);
		font-size: var(--fs-1);
		text-align: left;
	}
	button.meta:hover {
		color: var(--text-2);
		text-decoration: underline;
		text-underline-offset: 2px;
	}
	.continue {
		margin-top: var(--sp-3);
	}
</style>
