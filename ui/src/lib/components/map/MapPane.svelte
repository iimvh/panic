<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import type { Actor } from '$lib/actors';
	import type { Scene, Thought } from '$lib/scene';
	import { view, type MapSize, type PaneTab } from '$lib/view.svelte';
	import StepDetail from '../StepDetail.svelte';
	import TraceView from '../TraceView.svelte';
	import AgentMap from './AgentMap.svelte';
	import MapHud from './MapHud.svelte';
	import MapLegend from './MapLegend.svelte';
	import Narration from './Narration.svelte';
	import StepRail from './StepRail.svelte';

	/**
	 * The panel beside the chat: the agent map, the detail of the step picked (Step) and the answer's trace (Trace),
	 * as tabs. Docked beside the chat, or over it (`floating`: a drawer, or a sheet on narrow screens) with a close
	 * button. In focus mode the map gets the narration beside it.
	 */
	let {
		scene,
		cast,
		thoughts = [],
		floating = false,
		narrow = false,
		onclose
	}: { scene: Scene; cast: Actor[]; thoughts?: Thought[]; floating?: boolean; narrow?: boolean; onclose?: () => void } =
		$props();
	/** What the map shows: which conversation, live or replayed. Characters start over at home when it changes, not with each new answer. */
	const epoch = $derived(`${chat.session}:${view.atStep === undefined ? 'live' : 'replay'}`);

	const traceId = $derived(chat.inspected?.kind === 'trace' ? chat.inspected.traceId : view.subject?.traceId);
	const focus = $derived(view.size === 'focus' && !floating);
	const TABS: { id: PaneTab; label: string }[] = [
		{ id: 'map', label: 'Map' },
		{ id: 'step', label: 'Step' },
		{ id: 'trace', label: 'Trace' }
	];
	const SIZES: { id: MapSize; label: string; title: string }[] = [
		{ id: 'side', label: 'Side', title: 'Map beside the chat' },
		{ id: 'focus', label: 'Focus', title: 'Map in the middle, chat in a column' },
		{ id: 'mini', label: 'Mini', title: 'Map as a strip above the message box' }
	];
</script>

<section class="pane" class:floating class:narrow aria-label="Agent map">
	<header>
		<div class="tabs" role="tablist">
			{#each TABS as t (t.id)}
				<button role="tab" aria-selected={view.tab === t.id} class:on={view.tab === t.id} onclick={() => (view.tab = t.id)}>
					{t.label}
				</button>
			{/each}
		</div>
		{#if !narrow}
			<div class="sizes" role="group" aria-label="Map size">
				{#each SIZES as s (s.id)}
					<button class:on={view.size === s.id} aria-pressed={view.size === s.id} title="{s.title} (M)" onclick={() => view.setSize(s.id)}>
						{s.label}
					</button>
				{/each}
			</div>
		{/if}
		{#if floating}
			<button class="close" onclick={onclose} aria-label="Close the map panel" title="Close (Esc)">
				<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"
					><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg
				>
			</button>
		{/if}
	</header>

	<div class="content">
		{#if view.tab === 'map'}
			<div class="map" class:focus>
				<div class="board">
					<MapHud hud={scene.hud} />
					<div class="stage-wrap">
						<AgentMap {scene} {cast} {thoughts} {epoch} label="Agent map: {scene.caption}" />
						{#if view.atStep !== undefined}
							<span class="replay-tag">Replay · step {scene.current + 1}/{scene.steps.length}</span>
						{/if}
					</div>
					<StepRail {scene} />
					<MapLegend />
				</div>
				{#if focus}<Narration {scene} {thoughts} />{/if}
			</div>
		{:else if view.tab === 'step'}
			<StepDetail />
		{:else if traceId}
			{#key traceId}<div class="trace"><TraceView session={chat.session} {traceId} /></div>{/key}
		{:else}
			<p class="note">No trace for this answer yet. Traces come once an answer ends, when the server keeps them.</p>
		{/if}
	</div>
</section>

<style>
	.pane {
		display: flex;
		flex-direction: column;
		min-width: 0;
		min-height: 0;
		height: 100%;
		background: var(--bg);
	}
	header {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 12px;
		border-bottom: 1px solid var(--border);
		background: var(--surface);
	}
	.tabs,
	.sizes {
		display: flex;
		border: 1px solid var(--edge);
		border-radius: 8px;
		overflow: hidden;
		box-shadow: var(--lift);
	}
	.tabs button,
	.sizes button {
		border: 0;
		background: var(--surface);
		padding: 3px 11px;
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--muted);
	}
	.tabs button + button,
	.sizes button + button {
		border-left: 1px solid var(--edge);
	}
	.tabs button.on {
		background: var(--text);
		color: var(--bg);
	}
	.sizes {
		margin-left: auto;
	}
	.sizes button.on {
		background: var(--amber);
		color: var(--text);
	}
	.close {
		display: grid;
		place-items: center;
		width: 28px;
		height: 28px;
		margin-left: auto;
		border: 1px solid var(--edge);
		border-radius: 6px;
		background: var(--surface);
		color: var(--text);
	}
	.sizes + .close {
		margin-left: 0;
	}
	.content {
		flex: 1;
		min-height: 0;
		overflow: hidden;
	}
	.map {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		height: 100%;
	}
	.map.focus {
		grid-template-columns: minmax(0, 1fr) minmax(260px, 320px);
	}
	.board {
		display: flex;
		flex-direction: column;
		gap: 10px;
		min-height: 0;
		padding: 12px 14px;
	}
	.stage-wrap {
		position: relative;
		flex: 1;
		min-height: 180px;
		border: 1px solid var(--border);
		border-radius: 12px;
		background: var(--surface);
		overflow: hidden;
	}
	.replay-tag {
		position: absolute;
		top: 8px;
		left: 8px;
		font-size: 10.5px;
		font-weight: 700;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		padding: 2px 8px;
		border: 1px solid var(--edge);
		border-radius: 999px;
		background: var(--amber);
	}
	.trace {
		height: 100%;
		overflow-y: auto;
		padding: 12px 16px;
	}
	.note {
		margin: 0;
		padding: 16px;
		font-size: 12px;
		color: var(--muted);
	}
	.floating {
		border-left: 1px solid var(--edge);
		box-shadow: -4px 0 0 rgb(0 0 0 / 0.06);
	}
	.floating.narrow {
		border-left: 0;
		border-top: 1px solid var(--edge);
		border-radius: 16px 16px 0 0;
		overflow: hidden;
	}
	.narrow .board {
		padding: 10px;
	}
	.narrow .stage-wrap {
		min-height: 220px;
	}
</style>
