<script lang="ts">
	import { untrack } from 'svelte';
	import type { Model, SettingsChange } from '$lib/core/api';
	import { chat } from '$lib/state/chat.svelte';
	import { layout } from '$lib/state/layout.svelte';
	import { theme, type ThemePref } from '$lib/state/theme.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Dialog from '$lib/ui/Dialog.svelte';

	/** The model Panic uses, how much it thinks, and how the page looks. Changes apply when saved. */
	let provider = $state('');
	let model = $state('');
	/** "off" or a level; the server says null for off. */
	let effort = $state('off');
	let search = $state('');
	let freeOnly = $state(false);
	let models = $state<Model[] | null>(null);
	let loading = $state(false);
	let error = $state<string | null>(null);
	let saving = $state(false);

	// each opening starts from what runs now; the model's details are looked up after it starts, so ask again.
	// Only the opening runs this: a state refresh (the poll, or the one asked here) must not reset what is typed.
	$effect(() => {
		if (!layout.settings) return;
		untrack(() => {
			provider = chat.state?.provider ?? '';
			model = chat.state?.model ?? '';
			effort = chat.state?.reasoningEffort ?? 'off';
			models = null;
			error = null;
			void chat.refresh();
		});
	});

	const providers = $derived(chat.state?.providers ?? []);
	const savedEffort = $derived(chat.state?.reasoningEffort ?? 'off');
	// the usual levels, plus one panic.json names that is not among them (e.g. "xhigh"), so it is not lost
	const LEVELS = ['off', 'minimal', 'low', 'medium', 'high'];
	const levels = $derived(LEVELS.includes(savedEffort) ? LEVELS : [...LEVELS, savedEffort]);
	const changed = $derived(provider !== (chat.state?.provider ?? '') || model.trim() !== (chat.state?.model ?? '') || effort !== savedEffort);
	const picked = $derived(models?.find((m) => m.id === model.trim()));
	const takesEffort = $derived(
		picked ? picked.reasoning : model.trim() === (chat.state?.model ?? '') ? (chat.state?.effortSupported ?? null) : null
	);
	const shown = $derived(
		(models ?? []).filter((m) => (!freeOnly || m.free === true) && (!search || m.id.toLowerCase().includes(search.toLowerCase())))
	);
	// the model list belongs to the provider that is running now
	const canBrowse = $derived(!!chat.state?.connected && provider === chat.state?.provider);
	const k = (n: number) => `${Math.round(n / 1000)}k`;

	async function browse() {
		loading = true;
		error = null;
		try {
			models = await chat.models();
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
			layout.settings = false;
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			saving = false;
		}
	}

	const THEMES: { value: ThemePref; label: string }[] = [
		{ value: 'auto', label: 'System' },
		{ value: 'light', label: 'Light' },
		{ value: 'dark', label: 'Dark' }
	];
</script>

<Dialog bind:open={layout.settings} title="Settings" width={560}>
	<div class="field">
		<label for="provider">Provider</label>
		<select id="provider" bind:value={provider}>
			{#if !provider}<option value="" disabled>Choose a provider</option>{/if}
			{#each providers as p (p.id)}
				<option value={p.id}>{p.id}{p.connected ? '' : ' (not connected)'}</option>
			{/each}
		</select>
		{#if provider && !providers.find((p) => p.id === provider)?.connected}
			<p class="note">Not connected: its API key is missing or it is disabled in <code>panic.json</code>. Changes there apply without a restart.</p>
		{/if}
	</div>

	<div class="field">
		<label for="model">Model</label>
		<div class="row">
			<input id="model" bind:value={model} placeholder="provider/model-id" spellcheck="false" />
			<Button onclick={browse} disabled={!canBrowse || loading}>{loading ? 'Loading…' : 'Browse'}</Button>
		</div>
		{#if !canBrowse}<p class="note">Save a connected provider first to browse its models.</p>{/if}
		{#if models}
			<div class="filters">
				<input type="search" bind:value={search} placeholder="Filter" aria-label="Filter models" />
				<label class="check"><input type="checkbox" bind:checked={freeOnly} /> Free only</label>
			</div>
			<ul class="models" aria-label="Models">
				{#each shown.slice(0, 200) as m (m.id)}
					<li>
						<button class:sel={m.id === model} aria-pressed={m.id === model} onclick={() => (model = m.id)}>
							<span class="id">{m.id}</span>
							<span class="tags">
								{[m.free ? 'free' : '', m.reasoning ? 'reasoning' : '', m.contextLength ? `${k(m.contextLength)} context` : '']
									.filter(Boolean)
									.join(' · ')}
							</span>
						</button>
					</li>
				{:else}
					<li class="note">No models match.</li>
				{/each}
			</ul>
		{/if}
	</div>

	<div class="field">
		<label for="effort">Reasoning effort</label>
		<select id="effort" bind:value={effort}>
			{#each levels as level (level)}<option value={level}>{level}</option>{/each}
		</select>
		{#if effort !== 'off' && takesEffort === false}
			<p class="note">This model takes no reasoning effort, so none is sent.</p>
		{/if}
	</div>

	<div class="field">
		<span class="label" id="theme-label">Theme</span>
		<div class="segmented" role="radiogroup" aria-labelledby="theme-label">
			{#each THEMES as t (t.value)}
				<button role="radio" aria-checked={theme.pref === t.value} onclick={() => theme.set(t.value)}>{t.label}</button>
			{/each}
		</div>
	</div>

	{#if error}<p class="error" role="alert">{error}</p>{/if}

	{#snippet footer()}
		<Button onclick={() => (layout.settings = false)}>Cancel</Button>
		<Button variant="primary" onclick={save} disabled={!changed || saving}>{saving ? 'Saving…' : 'Save'}</Button>
	{/snippet}
</Dialog>

<style>
	.field + .field {
		margin-top: var(--sp-5);
	}
	label,
	.label {
		display: block;
		margin-bottom: var(--sp-1);
		font-size: var(--fs-2);
		font-weight: 500;
	}
	select,
	input:not([type='checkbox']) {
		width: 100%;
		height: 32px;
		padding: 0 var(--sp-2);
		border: 1px solid var(--border-strong);
		border-radius: var(--radius);
		background: var(--bg);
		font-size: var(--fs-2);
	}
	#model {
		font-family: var(--mono);
	}
	.row,
	.filters {
		display: flex;
		gap: var(--sp-2);
		align-items: center;
	}
	.filters {
		margin-top: var(--sp-2);
	}
	.check {
		display: flex;
		align-items: center;
		gap: var(--sp-1);
		margin: 0;
		white-space: nowrap;
		font-weight: 400;
	}
	.models {
		margin: var(--sp-2) 0 0;
		padding: var(--sp-1);
		max-height: 240px;
		overflow-y: auto;
		list-style: none;
		border: 1px solid var(--border);
		border-radius: var(--radius);
	}
	.models button {
		display: flex;
		justify-content: space-between;
		gap: var(--sp-3);
		width: 100%;
		padding: var(--sp-1) var(--sp-2);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		text-align: left;
		font-size: var(--fs-2);
	}
	.models button:hover {
		background: var(--surface-2);
	}
	.models button.sel {
		background: var(--accent-soft);
	}
	.id {
		font-family: var(--mono);
		font-size: var(--fs-1);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.tags {
		flex: none;
		color: var(--muted);
		font-size: var(--fs-1);
	}
	.note {
		margin: var(--sp-1) 0 0;
		color: var(--muted);
		font-size: var(--fs-1);
	}
	.segmented {
		display: inline-flex;
		padding: 2px;
		border: 1px solid var(--border);
		border-radius: var(--radius);
		background: var(--surface);
	}
	.segmented button {
		height: 26px;
		padding: 0 var(--sp-3);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		color: var(--muted);
		font-size: var(--fs-2);
	}
	.segmented button[aria-checked='true'] {
		background: var(--bg);
		color: var(--text);
		box-shadow: inset 0 0 0 1px var(--border);
	}
	.error {
		margin: var(--sp-4) 0 0;
		color: var(--danger);
		font-size: var(--fs-2);
	}
</style>
