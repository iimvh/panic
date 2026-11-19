<script lang="ts">
	import { getModels, type Model, type SettingsChange } from '$lib/api';
	import { chat } from '$lib/chat.svelte';

	let { onclose }: { onclose: () => void } = $props();

	let provider = $state(chat.state?.provider ?? '');
	let model = $state(chat.state?.model ?? '');
	/** "off" or a level; the server says null for off. */
	let effort = $state(chat.state?.reasoningEffort ?? 'off');
	let search = $state('');
	let freeOnly = $state(false);
	let models = $state<Model[] | null>(null);
	let loading = $state(false);
	let error = $state<string | null>(null);
	let saving = $state(false);

	const providers = $derived(chat.state?.providers ?? []);
	const savedEffort = $derived(chat.state?.reasoningEffort ?? 'off');
	// the usual levels, plus one panic.json names that is not among them (e.g. "xhigh"), so it is not lost
	const LEVELS = ['off', 'minimal', 'low', 'medium', 'high'];
	const levels = $derived(LEVELS.includes(savedEffort) ? LEVELS : [...LEVELS, savedEffort]);
	const changed = $derived(
		provider !== (chat.state?.provider ?? '') || model !== (chat.state?.model ?? '') || effort !== savedEffort
	);
	// what the provider lists for the model picked: from the browsed list, else what the server looked up
	const picked = $derived(models?.find((m) => m.id === model.trim()));
	const takesEffort = $derived(
		picked ? picked.reasoning : model.trim() === (chat.state?.model ?? '') ? (chat.state?.effortSupported ?? null) : null
	);

	// the model's details are looked up after it starts: ask again so the effort note is current
	$effect(() => {
		void chat.refresh();
	});
	const shown = $derived(
		(models ?? []).filter(
			(m) => (!freeOnly || m.free === true) && (!search || m.id.toLowerCase().includes(search.toLowerCase()))
		)
	);
	// the model list belongs to the provider that is running now
	const canBrowse = $derived(!!chat.state?.connected && provider === chat.state?.provider);

	async function browse() {
		loading = true;
		error = null;
		try {
			models = await getModels();
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	async function save() {
		saving = true;
		error = null;
		try {
			const change: SettingsChange = {};
			if (provider && provider !== chat.state?.provider) change.provider = provider;
			if (model.trim() && model.trim() !== chat.state?.model) change.model = model.trim();
			if (effort !== savedEffort) change.reasoningEffort = effort;
			await chat.settings(change);
			onclose();
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			saving = false;
		}
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') onclose();
	}
</script>

<svelte:window {onkeydown} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="backdrop" onclick={onclose}></div>
<div class="panel" role="dialog" aria-modal="true" aria-labelledby="settings-title">
	<header>
		<h2 id="settings-title">Settings</h2>
		<button class="btn" onclick={onclose}>Close</button>
	</header>

	<section>
		<label for="provider">Provider</label>
		<select id="provider" bind:value={provider}>
			{#if !provider}<option value="" disabled>Choose a provider</option>{/if}
			{#each providers as p (p.id)}
				<option value={p.id}>{p.id}{p.connected ? '' : ' (not connected)'}</option>
			{/each}
		</select>
		{#if provider && !providers.find((p) => p.id === provider)?.connected}
			<p class="note">
				This provider is not connected: its API key is missing or it is disabled in
				<code>panic.json</code>. Panic picks up the change without a restart.
			</p>
		{/if}
	</section>

	<section>
		<label for="model">Model</label>
		<div class="row">
			<input id="model" bind:value={model} placeholder="e.g. meta-llama/llama-3.3-70b-instruct:free" spellcheck="false" />
			<button class="btn" onclick={browse} disabled={!canBrowse || loading}>{loading ? 'Loading…' : 'Browse'}</button>
		</div>
		{#if !canBrowse}
			<p class="note">Save a connected provider first to browse its models.</p>
		{/if}
		{#if models}
			<div class="filters">
				<input type="search" bind:value={search} placeholder="Filter models" aria-label="Filter models" />
				<label class="check"><input type="checkbox" bind:checked={freeOnly} /> free only</label>
			</div>
			<ul class="models">
				{#each shown.slice(0, 200) as m (m.id)}
					<li>
						<button class:sel={m.id === model} onclick={() => (model = m.id)}>
							<span class="id">{m.id}</span>
							<span class="tags">
								{#if m.reasoning}<span title="Takes a reasoning effort">think</span>{/if}
								{#if m.maxOutput}<span title="Longest reply">out {Math.round(m.maxOutput / 1000)}k</span>{/if}
								{#if m.contextLength}<span title="Context window">{Math.round(m.contextLength / 1000)}k</span>{/if}
								{#if m.free}<span class="free">free</span>{/if}
							</span>
						</button>
					</li>
				{:else}
					<li class="empty">No models match.</li>
				{/each}
			</ul>
		{/if}
	</section>

	<section>
		<label for="effort">Reasoning effort</label>
		<select id="effort" bind:value={effort}>
			{#each levels as level (level)}
				<option value={level}>{level}</option>
			{/each}
		</select>
		{#if effort !== 'off' && takesEffort === false}
			<p class="note">This model takes no reasoning effort, so none is sent.</p>
		{:else}
			<p class="note">
				How much the model thinks before it answers. Thinking tokens count against the reply limit
				(<code>llm.maxTokens</code>).
			</p>
		{/if}
	</section>

	{#if error}<p class="error">{error}</p>{/if}

	<footer>
		<button class="btn" onclick={onclose}>Cancel</button>
		<button class="btn primary" onclick={save} disabled={!changed || saving}>{saving ? 'Saving…' : 'Save'}</button>
	</footer>
</div>

<style>
	.backdrop {
		position: fixed;
		inset: 0;
		background: rgb(29 27 24 / 0.3);
		z-index: 30;
	}
	.panel {
		position: fixed;
		top: 0;
		right: 0;
		bottom: 0;
		width: min(460px, 100%);
		z-index: 31;
		display: flex;
		flex-direction: column;
		gap: 18px;
		padding: 16px;
		background: var(--surface);
		border-left: 1.5px solid var(--edge);
		overflow-y: auto;
	}
	header,
	footer {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
	}
	footer {
		margin-top: auto;
		justify-content: flex-end;
	}
	h2 {
		margin: 0;
		font-size: 17px;
	}
	section {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	label {
		font-weight: 600;
		font-size: 13px;
	}
	select,
	input:not([type='checkbox']) {
		width: 100%;
		border: 1px solid var(--border);
		background: var(--bg);
		border-radius: 8px;
		padding: 7px 10px;
	}
	.row,
	.filters {
		display: flex;
		gap: 8px;
		align-items: center;
	}
	.check {
		display: flex;
		align-items: center;
		gap: 6px;
		white-space: nowrap;
		font-weight: 400;
	}
	.note {
		margin: 0;
		font-size: 13px;
		color: var(--muted);
	}
	.note code {
		font: 12px var(--mono);
	}
	.error {
		margin: 0;
		color: var(--danger);
		font-size: 14px;
	}
	.models {
		list-style: none;
		margin: 4px 0 0;
		padding: 4px;
		border: 1px solid var(--border);
		border-radius: 10px;
		max-height: 44vh;
		overflow-y: auto;
	}
	.models button {
		display: flex;
		justify-content: space-between;
		gap: 10px;
		width: 100%;
		border: 0;
		background: none;
		text-align: left;
		padding: 6px 8px;
		border-radius: 6px;
	}
	.models button:hover {
		background: var(--surface-2);
	}
	.models button.sel {
		background: color-mix(in srgb, var(--accent) 14%, transparent);
	}
	.id {
		font: 13px var(--mono);
		overflow-wrap: anywhere;
	}
	.tags {
		display: flex;
		gap: 6px;
		flex: none;
		font-size: 12px;
		color: var(--muted);
	}
	.free {
		color: var(--ok);
	}
	.empty {
		padding: 8px;
		color: var(--muted);
		font-size: 13px;
	}
</style>
