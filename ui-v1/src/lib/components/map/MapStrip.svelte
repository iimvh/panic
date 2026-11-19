<script lang="ts">
	import type { Scene } from '$lib/scene';

	/**
	 * The map folded to one line above the message box: the blocks at work as chips, what happens now, and the steps
	 * as dots. Clicking it opens the map.
	 */
	let { scene, onopen }: { scene: Scene; onopen: () => void } = $props();

	const shown = $derived(scene.nodes.filter((n) => n.state !== 'ghost' && n.kind !== 'you' && n.kind !== 'home'));
	const busy = $derived(shown.some((n) => n.state === 'active' || n.state === 'waiting'));
</script>

<button class="strip" onclick={onopen} title="Open the map (M)">
	<span class="label-caps">Map</span>
	<span class="chips">
		{#each shown as n (n.id)}
			<span class="chip {n.kind} {n.state}">{n.label}{#if n.state === 'error'} ✕{/if}</span>
		{/each}
	</span>
	<span class="caption" class:busy>{scene.caption}</span>
	{#if scene.steps.length}
		<span class="dots" aria-hidden="true">
			{#each scene.steps.slice(-12) as s, i (i)}<i class={s.status}></i>{/each}
		</span>
	{/if}
	<span class="open" aria-hidden="true">⤢</span>
</button>

<style>
	.strip {
		display: flex;
		align-items: center;
		gap: 10px;
		width: 100%;
		height: 44px;
		padding: 0 12px;
		border: 1px solid var(--edge);
		border-radius: 10px;
		background: var(--surface);
		box-shadow: var(--lift);
		text-align: left;
		overflow: hidden;
	}
	.strip:hover {
		background: var(--surface-2);
	}
	.chips {
		display: flex;
		gap: 4px;
		flex: none;
		max-width: 45%;
		overflow: hidden;
	}
	.chip {
		flex: none;
		font-size: 10.5px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		padding: 1px 7px;
		border: 1px solid var(--edge);
		border-radius: 5px;
		background: var(--surface-2);
	}
	.chip.model {
		background: var(--orange);
	}
	.chip.gate {
		background: var(--amber);
	}
	.chip.workspace {
		background: var(--violet);
	}
	.chip.mcp {
		background: var(--green);
	}
	.chip.shell {
		background: var(--text);
		color: var(--bg);
	}
	.chip.idle {
		opacity: 0.55;
	}
	.chip.active {
		outline: 2px solid var(--rust);
		outline-offset: 1px;
	}
	.chip.waiting {
		outline: 2px solid var(--warn);
	}
	.chip.error {
		background: var(--danger);
		border-color: var(--danger);
		color: #fff;
		opacity: 1;
	}
	.caption {
		flex: 1;
		min-width: 0;
		font-size: 12px;
		color: var(--muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.caption.busy {
		color: var(--text);
	}
	.dots {
		display: flex;
		gap: 3px;
		flex: none;
	}
	.dots i {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		border: 1px solid var(--edge);
		background: var(--surface);
	}
	.dots .ok {
		background: var(--green);
	}
	.dots .error {
		background: var(--danger);
	}
	.dots .running,
	.dots .drafting {
		background: var(--amber);
	}
	.open {
		flex: none;
		color: var(--muted);
	}
	@media (max-width: 520px) {
		.chips {
			max-width: 40%;
		}
		.dots {
			display: none;
		}
	}
</style>
