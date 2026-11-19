<script lang="ts">
	import { ROLES, type NodeKind, type Scene, type Thought } from '$lib/scene';
	import { view } from '$lib/view.svelte';

	/**
	 * Beside the map in focus mode: what happens at the step shown, in words, with the idea behind the block at work
	 * (KEY IDEA), what the model thinks while it thinks, the agent's checklist when it keeps one, and buttons to walk
	 * through the steps.
	 */
	let { scene, thoughts = [] }: { scene: Scene; thoughts?: Thought[] } = $props();

	const node = $derived(scene.nodes.find((n) => n.id === scene.focus));
	// a sub-agent has no block: the focus then names its kind
	const role = $derived(node ? ROLES[node.kind] : (ROLES[scene.focus as NodeKind] ?? ROLES.home));
	const n = $derived(scene.steps.length);
	const at = $derived(scene.current >= 0 ? scene.current + 1 : n);
</script>

<aside class="narration">
	<span class="label-caps">{view.atStep !== undefined ? `Step ${at} of ${n}` : 'Now'}</span>
	<h2>{scene.caption}</h2>
	<p class="who">{role.title}{node && node.label !== role.title.toLowerCase() ? ` · ${node.label}` : ''}</p>
	{#each thoughts as thought (thought.id)}
		<div class="thinking">
			<span class="label-caps">{`${thought.agent ?? 'Model'} ${thought.past ? 'thought' : 'is thinking'}`}</span>
			<p>{thought.text}</p>
		</div>
	{/each}
	<div class="idea">
		<span class="label-caps">Key idea</span>
		<p>{role.idea}</p>
	</div>
	{#if scene.todo.length}
		<div class="todo">
			<span class="label-caps">Agent's checklist</span>
			<ul>
				{#each scene.todo as item, i (i)}
					<li class={item.status}><i aria-hidden="true">{item.status === 'done' ? '✓' : item.status === 'in_progress' ? '▸' : ''}</i>{item.text}</li>
				{/each}
			</ul>
		</div>
	{/if}
	<div class="walk">
		<button class="btn" onclick={() => view.step(-1)} disabled={!n}>← Back</button>
		<button class="btn primary" onclick={() => view.step(1)} disabled={!n}>Next →</button>
	</div>
	<p class="keys"><kbd>←</kbd> <kbd>→</kbd> steps · <kbd>R</kbd> replay · <kbd>Space</kbd> pause · <kbd>M</kbd> size</p>
</aside>

<style>
	.narration {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: 16px;
		border-left: 1px solid var(--border);
		background: var(--surface);
		overflow-y: auto;
		min-height: 0;
	}
	h2 {
		margin: 0;
		font-size: 17px;
		line-height: 1.35;
		overflow-wrap: anywhere;
	}
	.who {
		margin: 0;
		font-size: 12px;
		color: var(--muted);
	}
	/* a fixed height, so the panel does not jump as lines come and go */
	.thinking {
		padding: 8px 12px;
		border: 1px solid var(--edge);
		border-radius: 10px;
		background: var(--orange);
	}
	.thinking p {
		margin: 4px 0 0;
		height: calc(3 * 1.5em);
		overflow: hidden;
		display: -webkit-box;
		-webkit-line-clamp: 3;
		line-clamp: 3;
		-webkit-box-orient: vertical;
		font-size: 12.5px;
		line-height: 1.5;
		font-style: italic;
		overflow-wrap: anywhere;
	}
	.idea {
		padding: 10px 12px;
		border: 1px solid var(--edge);
		border-radius: 10px;
		background: var(--violet);
		box-shadow: var(--pop);
	}
	.idea p {
		margin: 4px 0 0;
		font-size: 12.5px;
		line-height: 1.55;
	}
	.todo ul {
		margin: 6px 0 0;
		padding: 0;
		list-style: none;
		font-size: 12.5px;
	}
	.todo li {
		display: flex;
		gap: 8px;
		padding: 3px 0;
	}
	.todo i {
		flex: none;
		display: grid;
		place-items: center;
		width: 16px;
		height: 16px;
		border: 1.2px solid var(--edge);
		border-radius: 4px;
		font-style: normal;
		font-size: 11px;
	}
	.todo .done {
		color: var(--muted);
		text-decoration: line-through;
	}
	.todo .done i {
		background: var(--green);
	}
	.todo .in_progress i {
		background: var(--amber);
	}
	.walk {
		display: flex;
		gap: 8px;
		margin-top: auto;
	}
	.keys {
		margin: 0;
		font-size: 11px;
		color: var(--muted);
	}
	kbd {
		font: inherit;
		border: 1px solid var(--edge);
		border-radius: 4px;
		padding: 0 4px;
		background: var(--bg);
		box-shadow: var(--lift);
	}
</style>
