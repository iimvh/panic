<script lang="ts">
	import { duration } from '$lib/core/reply';
	import { compact, summary } from '$lib/core/summary';
	import { chat } from '$lib/state/chat.svelte';
	import Icon from '$lib/ui/Icon.svelte';

	/** The conversation at a glance: what the agent did, what it cost, what is left on its checklist. */
	let now = $state(Date.now());
	$effect(() => {
		if (!chat.busy) return;
		const timer = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(timer);
	});

	const s = $derived(summary(chat.messages, now));
	const MODES = { ask: 'Ask first', plan: 'Plan', auto: 'Auto-run' } as const;
	const facts = $derived(
		[
			['Mode', chat.runMode ? MODES[chat.runMode] : null],
			['Model', chat.state?.model ?? null],
			['Time', s.elapsed !== null ? duration(s.elapsed) : null],
			['Tokens', s.tokens !== null ? compact(s.tokens) : null],
			['Tool calls', s.steps ? `${s.steps}${s.failed ? ` · ${s.failed} failed` : ''}` : null],
			['Files read', s.reads ? String(s.reads) : null],
			['Edits', s.writes ? `${s.writes} · +${s.added} −${s.removed}` : null],
			['Commands', s.commands ? String(s.commands) : null],
			['MCP calls', s.network ? String(s.network) : null],
			['Sub-agents', s.agents ? String(s.agents) : null]
		].filter((f): f is [string, string] => f[1] !== null)
	);
	const done = $derived(s.todo.filter((t) => t.status === 'done').length);
</script>

<div class="overview">
	<dl>
		{#each facts as [k, v] (k)}
			<dt>{k}</dt>
			<dd class:danger={k === 'Mode' && chat.runMode === 'auto'}>{v}</dd>
		{/each}
	</dl>

	{#if s.todo.length}
		<section>
			<h3>Checklist <span class="muted">{done} of {s.todo.length}</span></h3>
			<ul class="todo">
				{#each s.todo as t, i (i)}
					<li class={t.status}>
						<span class="box" aria-hidden="true">{#if t.status === 'done'}<Icon name="check" size={12} />{/if}</span>
						<span>{t.text}</span>
						<span class="sr-only">({t.status.replace('_', ' ')})</span>
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	{#if s.files.length}
		<section>
			<h3>Files <span class="muted">{s.files.length}</span></h3>
			<ul class="files">
				{#each s.files.slice(0, 50) as f (f.path)}
					<li title={f.path}><span class="path">{f.path}</span>{#if f.write}<span class="muted">edited</span>{/if}</li>
				{/each}
			</ul>
		</section>
	{/if}
</div>

<style>
	.overview {
		padding: var(--sp-4);
	}
	dl {
		display: grid;
		grid-template-columns: max-content 1fr;
		gap: var(--sp-2) var(--sp-4);
		margin: 0;
		font-size: var(--fs-2);
	}
	dt {
		color: var(--muted);
	}
	dd {
		margin: 0;
		min-width: 0;
		overflow-wrap: anywhere;
		font-variant-numeric: tabular-nums;
	}
	dd.danger {
		color: var(--danger);
	}
	section {
		margin-top: var(--sp-5);
	}
	h3 {
		margin: 0 0 var(--sp-2);
		font-size: var(--fs-2);
		font-weight: 600;
	}
	ul {
		margin: 0;
		padding: 0;
		list-style: none;
		font-size: var(--fs-2);
	}
	.todo li {
		display: flex;
		gap: var(--sp-2);
		padding: 2px 0;
	}
	.box {
		display: inline-grid;
		place-items: center;
		flex: none;
		width: 14px;
		height: 14px;
		margin-top: 3px;
		border: 1px solid var(--border-strong);
		border-radius: var(--radius-sm);
	}
	.todo .in_progress .box {
		border-color: var(--accent);
	}
	.todo .done {
		color: var(--muted);
	}
	.todo .done span:not(.box) {
		text-decoration: line-through;
	}
	.files li {
		display: flex;
		gap: var(--sp-2);
		padding: 2px 0;
	}
	.path {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		direction: rtl;
		text-align: left;
		font-family: var(--mono);
		font-size: var(--fs-1);
	}
</style>
