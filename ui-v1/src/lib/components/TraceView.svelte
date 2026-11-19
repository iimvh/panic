<script lang="ts">
	import { getTrace, type Trace } from '$lib/api';
	import { layout, ms } from '$lib/trace';

	let { session, traceId }: { session: string; traceId: string } = $props();

	let trace = $state<Trace | null>(null);
	let failure = $state('');
	let open = $state<Record<string, boolean>>({});

	$effect(() => {
		const id = traceId;
		trace = null;
		failure = '';
		load(session, id).then(
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

<script module lang="ts">
	/** Finished traces never change, so each is fetched once per page; failures are tried again. */
	const cache = new Map<string, Promise<Trace>>();

	function load(session: string, id: string): Promise<Trace> {
		const key = `${session}/${id}`;
		let p = cache.get(key);
		if (!p) {
			p = getTrace(session, id);
			cache.set(key, p);
			p.catch(() => cache.delete(key));
		}
		return p;
	}
</script>

<section class="trace" aria-label="Trace of this answer">
	{#if failure}
		<p class="note">{failure === 'tracing is off' ? 'Tracing is off: enable the tracing plugin to see where the time went.' : `No trace: ${failure}`}</p>
	{:else if !trace}
		<p class="note">Loading trace…</p>
	{:else}
		<ol>
			{#each rows as row (row.id)}
				<li class={row.status}>
					<button
						type="button"
						class="row"
						aria-expanded={!!open[row.id]}
						onclick={() => (open[row.id] = !open[row.id])}
						title={row.detail}
					>
						<span class="label" style:padding-left="{row.depth * 12}px">{row.label}</span>
						<span class="track" aria-hidden="true">
							<span class="bar" style:left="{row.offset}%" style:width="{row.width}%"></span>
						</span>
						<span class="dur">{ms(row.durationMs)}</span>
					</button>
					{#if open[row.id]}
						<div class="facts" style:margin-left="{row.depth * 12}px">
							<div class="line">
								starts +{ms(row.startMs)}{row.detail ? ` · ${row.detail}` : ''}
							</div>
							{#if row.error}<div class="line err">{row.status}: {row.error}</div>{/if}
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
</section>

<style>
	.trace {
		margin-top: 8px;
		padding: 8px 10px;
		border: 1px solid var(--border);
		border-radius: 8px;
		background: var(--surface);
		font: 12px var(--mono);
	}
	.note {
		margin: 0;
		color: var(--muted);
		font-family: var(--font);
		font-size: 13px;
	}
	ol {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.row {
		display: grid;
		grid-template-columns: minmax(0, 11rem) minmax(0, 1fr) 4.5rem;
		gap: 8px;
		align-items: center;
		width: 100%;
		padding: 3px 0;
		border: 0;
		background: none;
		color: var(--text);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.row:hover .track {
		background: var(--surface-2);
	}
	.row:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: 1px;
		border-radius: 4px;
	}
	.label {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.track {
		position: relative;
		height: 10px;
		border-radius: 3px;
	}
	.bar {
		position: absolute;
		top: 0;
		bottom: 0;
		min-width: 2px;
		border-radius: 3px;
		background: color-mix(in srgb, var(--accent) 70%, transparent);
	}
	li:first-child .bar {
		background: color-mix(in srgb, var(--muted) 45%, transparent);
	}
	.error .bar {
		background: var(--danger);
	}
	.cancelled .bar {
		background: var(--warn);
	}
	.error .label {
		color: var(--danger);
	}
	.dur {
		text-align: right;
		color: var(--muted);
		font-variant-numeric: tabular-nums;
	}
	.facts {
		margin: 2px 0 6px;
		padding: 6px 8px;
		border-left: 2px solid var(--border);
		color: var(--muted);
	}
	.facts .line {
		overflow-wrap: anywhere;
	}
	.facts .err {
		color: var(--danger);
	}
	dl {
		display: grid;
		grid-template-columns: max-content minmax(0, 1fr);
		gap: 2px 10px;
		margin: 6px 0 0;
	}
	dt {
		color: var(--muted);
	}
	dd {
		margin: 0;
		color: var(--text);
		overflow-wrap: anywhere;
	}
	@media (max-width: 480px) {
		.row {
			grid-template-columns: minmax(0, 7.5rem) minmax(0, 1fr) 3.75rem;
		}
	}
</style>
