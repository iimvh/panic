<script lang="ts" module>
	import type { Trace } from '$lib/core/api';

	/** Finished traces never change, so each is fetched once per page; failures are tried again. */
	const cache = new Map<string, Promise<Trace>>();
</script>

<script lang="ts">
	import { layout, ms } from '$lib/core/trace';
	import { chat } from '$lib/state/chat.svelte';
	import { inspector } from '$lib/state/inspector.svelte';

	/** Where an answer's time went: its spans as a waterfall. Without a trace picked, the latest answer's. */
	const answers = $derived(chat.messages.filter((m) => m.role === 'assistant' && m.traceId && !m.streaming));
	const traceId = $derived(inspector.trace ?? answers.at(-1)?.traceId ?? null);
	const which = $derived(answers.findIndex((m) => m.traceId === traceId));

	let trace = $state<Trace | null>(null);
	let failure = $state('');
	let open = $state<Record<string, boolean>>({});

	$effect(() => {
		const id = traceId;
		trace = null;
		failure = '';
		if (!id) return;
		const key = `${chat.session}/${id}`;
		let p = cache.get(key);
		if (!p) {
			p = chat.trace(id);
			cache.set(key, p);
			p.catch(() => cache.delete(key));
		}
		p.then(
			(t) => {
				if (id === traceId) trace = t;
			},
			(e) => {
				if (id === traceId) failure = e instanceof Error ? e.message : String(e);
			}
		);
	});

	const rows = $derived(trace ? layout(trace.spans) : []);

	/** Every attribute but those already shown in the row's detail line. */
	function facts(attrs: Record<string, unknown>): [string, string][] {
		return Object.entries(attrs)
			.filter(([k]) => k !== 'questionHash')
			.map(([k, v]) => [k, typeof v === 'number' && k.endsWith('Micros') ? ms(v / 1000) : String(v)]);
	}
</script>

<section class="trace" aria-label="Trace">
	{#if !traceId}
		<p class="note">No traces yet. Each finished answer has one when the tracing plugin runs.</p>
	{:else}
		{#if answers.length > 1}
			<p class="which">
				Answer {which + 1} of {answers.length}
				{#if which > 0}<button class="link" onclick={() => inspector.open('trace', { trace: answers[which - 1].traceId })}>Previous</button>{/if}
				{#if which >= 0 && which < answers.length - 1}<button class="link" onclick={() => inspector.open('trace', { trace: answers[which + 1].traceId })}
						>Next</button
					>{/if}
			</p>
		{/if}
		{#if failure}
			<p class="note">{failure === 'tracing is off' ? 'Tracing is off: enable the tracing plugin to see where the time went.' : `No trace: ${failure}`}</p>
		{:else if !trace}
			<p class="note">Loading trace…</p>
		{:else}
			<ol>
				{#each rows as row (row.id)}
					<li class={row.status}>
						<button type="button" class="row" aria-expanded={!!open[row.id]} onclick={() => (open[row.id] = !open[row.id])} title={row.detail}>
							<span class="label" style:padding-left="{row.depth * 12}px">{row.label}</span>
							<span class="track" aria-hidden="true"><span class="bar" style:left="{row.offset}%" style:width="{row.width}%"></span></span>
							<span class="dur">{ms(row.durationMs)}</span>
						</button>
						{#if open[row.id]}
							<div class="facts" style:margin-left="{row.depth * 12 + 8}px">
								<div>starts +{ms(row.startMs)}{row.detail ? ` · ${row.detail}` : ''}</div>
								{#if row.error}<div class="err">{row.status}: {row.error}</div>{/if}
								<dl>
									{#each facts(row.attrs) as [k, v] (k)}
										<dt>{k}</dt>
										<dd>{v}</dd>
									{/each}
								</dl>
							</div>
						{/if}
					</li>
				{/each}
			</ol>
		{/if}
	{/if}
</section>

<style>
	.trace {
		padding: var(--sp-2) var(--sp-2) var(--sp-5);
	}
	.note {
		margin: 0;
		padding: var(--sp-3) var(--sp-2);
		color: var(--muted);
		font-size: var(--fs-2);
	}
	.which {
		display: flex;
		gap: var(--sp-3);
		margin: 0;
		padding: var(--sp-1) var(--sp-2) var(--sp-2);
		color: var(--muted);
		font-size: var(--fs-1);
	}
	.link {
		padding: 0;
		border: 0;
		background: none;
		color: var(--accent);
		font-size: inherit;
	}
	ol {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.row {
		display: grid;
		grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr) 56px;
		align-items: center;
		gap: var(--sp-2);
		width: 100%;
		padding: 3px var(--sp-2);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		text-align: left;
		font-family: var(--mono);
		font-size: var(--fs-1);
		color: var(--text-2);
	}
	.row:hover {
		background: var(--surface-2);
	}
	.label {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.track {
		position: relative;
		height: 6px;
		border-radius: var(--radius-sm);
		background: var(--surface-2);
	}
	.bar {
		position: absolute;
		top: 0;
		bottom: 0;
		min-width: 2px;
		border-radius: var(--radius-sm);
		background: var(--accent);
	}
	.error .bar {
		background: var(--danger);
	}
	.cancelled .bar {
		background: var(--muted);
	}
	.dur {
		text-align: right;
		font-variant-numeric: tabular-nums;
		color: var(--muted);
	}
	.facts {
		padding: var(--sp-1) var(--sp-2) var(--sp-2);
		font-family: var(--mono);
		font-size: var(--fs-1);
		color: var(--muted);
	}
	.err {
		color: var(--danger);
	}
	dl {
		display: grid;
		grid-template-columns: max-content 1fr;
		gap: 2px var(--sp-3);
		margin: var(--sp-1) 0 0;
	}
	dd {
		margin: 0;
		color: var(--text-2);
		overflow-wrap: anywhere;
	}
</style>
