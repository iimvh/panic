<script lang="ts">
	import { chat, type Message } from '$lib/state/chat.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Card from '$lib/ui/Card.svelte';

	let { message }: { message: Message } = $props();

	const q = $derived(message.question);

	/** Per question: the labels picked, whether "Other" is picked, and its text. */
	type Choice = { picked: string[]; other: boolean; text: string };
	let choices = $state<Choice[]>([]);
	let forId = '';

	// a new set of questions starts with nothing picked
	$effect(() => {
		if (q && q.id !== forId) {
			forId = q.id;
			choices = q.questions.map(() => ({ picked: [], other: false, text: '' }));
		}
	});

	function pick(i: number, label: string, multi: boolean) {
		const c = choices[i];
		if (!multi) {
			c.picked = [label];
			c.other = false;
		} else if (c.picked.includes(label)) c.picked = c.picked.filter((l) => l !== label);
		else c.picked = [...c.picked, label];
	}

	function pickOther(i: number, multi: boolean) {
		const c = choices[i];
		if (multi) c.other = !c.other;
		else {
			c.other = true;
			c.picked = [];
		}
	}

	const answered = (c: Choice | undefined) => !!c && (c.picked.length > 0 || (c.other && c.text.trim() !== ''));
	const ready = $derived(!!q && choices.length === q.questions.length && choices.every(answered));

	function submit() {
		if (!ready) return;
		void chat.answerQuestions(
			message,
			choices.map((c) => ({ selected: c.picked, other: c.other ? c.text.trim() : '' }))
		);
	}

	function skip() {
		if (!q) return;
		void chat.answerQuestions(
			message,
			q.questions.map(() => ({ selected: [], other: '' }))
		);
	}
</script>

{#if q && choices.length === q.questions.length}
	<Card label="Panic asks">
		{#snippet header()}
			<strong>Panic needs your input</strong>
		{/snippet}
		{#each q.questions as item, i (i)}
			<fieldset disabled={q.answering}>
				<legend>
					{item.question}
					{#if item.multiSelect}<span class="muted">· pick any</span>{/if}
				</legend>
				{#each item.options as o (o.label)}
					<label class="option">
						<input
							type={item.multiSelect ? 'checkbox' : 'radio'}
							name="q-{q.id}-{i}"
							checked={choices[i].picked.includes(o.label)}
							onchange={() => pick(i, o.label, item.multiSelect)}
						/>
						<span>
							{o.label}
							{#if o.description}<span class="desc">{o.description}</span>{/if}
						</span>
					</label>
				{/each}
				<label class="option">
					<input type={item.multiSelect ? 'checkbox' : 'radio'} name="q-{q.id}-{i}" checked={choices[i].other} onchange={() => pickOther(i, item.multiSelect)} />
					<span>Other</span>
				</label>
				{#if choices[i].other}
					<input
						class="other"
						type="text"
						aria-label="Your answer"
						placeholder="Your answer"
						bind:value={choices[i].text}
						onkeydown={(e) => {
							if (e.key === 'Enter') submit();
						}}
					/>
				{/if}
			</fieldset>
		{/each}
		{#snippet footer()}
			<Button size="sm" disabled={q.answering} onclick={skip}>Skip</Button>
			<Button size="sm" variant="primary" disabled={q.answering || !ready} onclick={submit}>Submit</Button>
		{/snippet}
	</Card>
{/if}

<style>
	fieldset {
		margin: 0 0 var(--sp-3);
		padding: 0;
		border: 0;
	}
	legend {
		padding: 0;
		margin-bottom: var(--sp-2);
		font-weight: 500;
	}
	.option {
		display: flex;
		align-items: flex-start;
		gap: var(--sp-2);
		padding: var(--sp-1) 0;
		cursor: pointer;
	}
	.option input {
		margin-top: 4px;
		accent-color: var(--accent);
	}
	.desc {
		display: block;
		color: var(--muted);
		font-size: var(--fs-2);
	}
	.other {
		width: 100%;
		margin-top: var(--sp-1);
		padding: var(--sp-1) var(--sp-2);
		border: 1px solid var(--border-strong);
		border-radius: var(--radius);
		background: var(--bg);
	}
</style>
