<script lang="ts">
	import { chat, type Message } from '$lib/chat.svelte';

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
	<div class="question" role="group" aria-label="The agent asks">
		{#each q.questions as item, i (i)}
			<fieldset disabled={q.answering}>
				<legend>
					{#if item.header}<span class="chip">{item.header}</span>{/if}
					<span class="text">{item.question}</span>
					{#if item.multiSelect}<span class="hint">pick any</span>{/if}
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
							<span class="label">{o.label}</span>
							{#if o.description}<span class="desc">{o.description}</span>{/if}
						</span>
					</label>
				{/each}
				<label class="option">
					<input
						type={item.multiSelect ? 'checkbox' : 'radio'}
						name="q-{q.id}-{i}"
						checked={choices[i].other}
						onchange={() => pickOther(i, item.multiSelect)}
					/>
					<span class="label">Other…</span>
				</label>
				{#if choices[i].other}
					<input
						class="other"
						type="text"
						placeholder="Your answer"
						bind:value={choices[i].text}
						onkeydown={(e) => {
							if (e.key === 'Enter') submit();
						}}
					/>
				{/if}
			</fieldset>
		{/each}
		<div class="actions">
			<button disabled={q.answering} onclick={skip}>Skip</button>
			<button class="primary" disabled={q.answering || !ready} onclick={submit}>Submit</button>
		</div>
	</div>
{/if}

<style>
	.question {
		margin: 0 0 8px;
		border: 1.5px solid var(--text);
		border-top: 6px solid var(--violet);
		border-radius: var(--radius);
		background: var(--surface);
		padding: 10px 12px;
		box-shadow: var(--pop);
	}
	fieldset {
		border: none;
		margin: 0 0 10px;
		padding: 0;
		min-width: 0;
	}
	legend {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		align-items: baseline;
		padding: 0;
		margin-bottom: 6px;
	}
	.chip {
		font: 11px var(--mono);
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--accent);
		border: 1px solid currentColor;
		border-radius: 999px;
		padding: 0 6px;
	}
	.text {
		font-weight: 600;
		font-size: 14px;
	}
	.hint,
	.desc {
		color: var(--muted);
		font-size: 12px;
	}
	.option {
		display: flex;
		gap: 8px;
		align-items: flex-start;
		padding: 4px 6px;
		border-radius: 8px;
		cursor: pointer;
		font-size: 14px;
	}
	.option:hover {
		background: var(--surface-2);
	}
	.option input {
		margin-top: 3px;
	}
	.option > span {
		display: flex;
		flex-direction: column;
	}
	.other {
		display: block;
		width: 100%;
		box-sizing: border-box;
		margin: 4px 0 0;
		font: inherit;
		font-size: 14px;
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 6px 8px;
		background: var(--surface-2);
		color: var(--text);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 8px;
	}
	button {
		font: inherit;
		font-size: 13px;
		border-radius: 8px;
		padding: 5px 14px;
		cursor: pointer;
		border: 1px solid var(--border);
		background: var(--surface-2);
		color: var(--text);
	}
	button.primary {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	button:disabled {
		opacity: 0.6;
		cursor: default;
	}
</style>
