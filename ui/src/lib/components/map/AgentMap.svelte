<script lang="ts" module>
	import type { PacketKind } from '$lib/scene';

	/** The colours of the packets Panic carries, shared with the legend. */
	export const PACKET: Record<PacketKind, { color: string; label: string }> = {
		prompt: { color: 'var(--blue)', label: 'prompt' },
		read: { color: 'var(--violet)', label: 'read' },
		write: { color: 'var(--green)', label: 'write' },
		command: { color: 'var(--amber)', label: 'command' },
		network: { color: '#9fd4c9', label: 'network' },
		waiting: { color: 'var(--warn)', label: 'waiting for you' }
	};
	/** Clones drawn at most; more show as +N on the dock. */
	export const MAX_CLONES = 6;
</script>

<script lang="ts">
	import { layout, type Actor } from '$lib/actors';
	import { Motion, type Body } from '$lib/motion';
	import { dims, type Scene, type SceneNode, type Thought } from '$lib/scene';
	import ActorView from './Actor.svelte';
	import Bubble from './Bubble.svelte';
	import IsoNode from './IsoNode.svelte';

	/**
	 * The stage: one SVG in layers (floor, links, blocks, characters, bubbles), its view box fitted to the blocks
	 * shown so it reads at any size. The blocks follow `scene` and stay put; Panic and its clones (`cast`) walk
	 * between them, moved by one animation frame loop that only runs while someone walks and only changes the
	 * characters layer. `epoch` names what is shown (an answer, live or replayed): when it changes, everyone starts
	 * over at home instead of walking from the last answer. Below a scale where pills would not be legible, pills and
	 * badges are hidden and only names stay.
	 */
	let {
		scene,
		label,
		cast,
		thought,
		epoch
	}: { scene: Scene; label: string; cast: Actor[]; thought?: Thought; epoch: string } = $props();

	const PAD = 40;
	const box = $derived.by(() => {
		let x0 = Infinity,
			y0 = Infinity,
			x1 = -Infinity,
			y1 = -Infinity;
		for (const n of scene.nodes) {
			const d = dims(n.small);
			const w = Math.max(d.a, n.pill ? n.pill.text.length * 3.2 + 8 : 0);
			x0 = Math.min(x0, n.x - w);
			x1 = Math.max(x1, n.x + w);
			y0 = Math.min(y0, n.y - d.b - (n.kind === 'you' ? 78 : n.pill ? 34 : 10));
			y1 = Math.max(y1, n.y + d.b + d.d + 22);
		}
		return { x: x0 - PAD, y: y0 - PAD / 2, w: x1 - x0 + 2 * PAD, h: y1 - y0 + PAD };
	});

	const byId = $derived(new Map(scene.nodes.map((n) => [n.id, n])));
	/** Blocks further down are drawn last, so they cover the ones behind them. */
	const ordered = $derived([...scene.nodes].sort((p, q) => p.y - q.y || p.x - q.x));
	const current = $derived(scene.current >= 0 ? scene.steps[scene.current]?.node : undefined);

	/** Where links meet a block: the middle of its body. */
	function anchor(n: SceneNode) {
		const d = dims(n.small);
		return { x: n.x, y: n.y + (n.kind === 'you' || n.kind === 'home' ? 4 : d.d / 2) };
	}

	let reduced = $state(false);
	$effect(() => {
		const q = matchMedia('(prefers-reduced-motion: reduce)');
		reduced = q.matches;
		const on = () => (reduced = q.matches);
		q.addEventListener('change', on);
		return () => q.removeEventListener('change', on);
	});
	// the characters: motion keeps where they are; the loop runs while someone walks, then stops
	const motion = new Motion();
	const ways = $derived(layout(scene));
	let bodies = $state.raw<Body[]>([]);
	let frame = 0;
	let shownEpoch: string | undefined;
	function loop(now: number) {
		frame = 0;
		const busy = motion.tick(now);
		bodies = motion.bodies();
		if (busy) frame = requestAnimationFrame(loop);
	}
	$effect(() => {
		if (epoch !== shownEpoch) {
			motion.reset();
			shownEpoch = epoch;
		}
		motion.jump = reduced || document.hidden;
		motion.update(cast, ways, performance.now());
		bodies = motion.bodies();
		if (!frame) frame = requestAnimationFrame(loop);
	});
	$effect(() => () => cancelAnimationFrame(frame));

	/** Panic and up to MAX_CLONES clones; the rest are counted on the dock. */
	const shown = $derived.by(() => {
		let clones = 0;
		return bodies.filter((b) => b.kind === 'main' || ++clones <= MAX_CLONES);
	});
	const extra = $derived(bodies.length - shown.length);
	const home = $derived(byId.get('home'));
	/** Thought bubbles go to whoever thinks: Panic, or the sub-agent named in the thought. */
	const thinks = (b: Body) => !b.moving && !!b.thinking && (thought?.agent ? b.name === thought.agent : b.kind === 'main');

	// pills need about half scale to be read; under that only names stay
	let width = $state(0);
	let height = $state(0);
	const compact = $derived(width > 0 && Math.min(width / box.w, height / box.h) < 0.55);
</script>

<div class="stage" bind:clientWidth={width} bind:clientHeight={height}>
	<svg
		viewBox="{box.x} {box.y} {box.w} {box.h}"
		preserveAspectRatio="xMidYMid meet"
		role="img"
		aria-label={label}
		class:compact
	>
		<defs>
			<pattern id="floor-dots" width="40" height="23" patternUnits="userSpaceOnUse">
				<circle cx="0" cy="0" r="1.1" />
				<circle cx="20" cy="11.5" r="1.1" />
				<circle cx="40" cy="0" r="1.1" />
				<circle cx="0" cy="23" r="1.1" />
				<circle cx="40" cy="23" r="1.1" />
			</pattern>
		</defs>
		<g class="floor"><rect x={box.x} y={box.y} width={box.w} height={box.h} fill="url(#floor-dots)" /></g>
		<g class="links">
			{#each scene.links as l (l.id)}
				{@const a = byId.get(l.from)}
				{@const b = byId.get(l.to)}
				{#if a && b}
					{@const p = anchor(a)}
					{@const q = anchor(b)}
					<line class="link {l.state}" x1={p.x} y1={p.y} x2={q.x} y2={q.y} />
				{/if}
			{/each}
		</g>
		<g class="ents">
			{#each ordered as n (n.id)}
				<IsoNode node={n} current={n.id === current} />
			{/each}
		</g>
		<g class="actors">
			{#each shown as b (b.id)}
				<ActorView body={b} color={b.carry ? PACKET[b.carry].color : undefined} />
			{/each}
			{#if extra > 0 && home}
				<g class="more">
					<rect x={home.x - 46} y={home.y + 12} width="30" height="16" rx="8" />
					<text x={home.x - 31} y={home.y + 23.5} text-anchor="middle">+{extra}</text>
				</g>
			{/if}
		</g>
		<g class="bubbles">
			{#each shown as b (b.id)}
				{@const head = b.kind === 'main' ? 44 : 30}
				{#if b.waiting && !b.moving}
					<Bubble x={b.x} y={b.y - head} {box} tone="wait" label="Waiting for you" text={b.note ?? (b.at === 'you' ? 'answer in the chat' : 'approve in the chat')} />
				{:else if thinks(b)}
					<Bubble x={b.x} y={b.y - head} {box} tone="think" label={b.kind === 'main' ? 'Model is thinking' : `${b.name} is thinking`} text={thought?.text ?? '…'} />
				{/if}
			{/each}
		</g>
	</svg>
</div>

<style>
	.stage {
		position: relative;
		width: 100%;
		height: 100%;
		min-height: 0;
	}
	svg {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		display: block;
	}
	pattern circle {
		fill: var(--border);
	}
	.link {
		stroke: var(--edge);
		stroke-width: 1.4;
		opacity: 0.55;
	}
	.link.ghost {
		stroke: var(--border);
		stroke-dasharray: 5 5;
		opacity: 1;
	}
	.link.active {
		stroke: var(--text);
		stroke-width: 2.2;
		opacity: 1;
	}
	.link.waiting {
		stroke: var(--warn);
		stroke-width: 2.2;
		stroke-dasharray: 7 5;
		opacity: 1;
	}
	.link.error {
		stroke: var(--danger);
		stroke-width: 2;
		opacity: 1;
	}
	.more rect {
		fill: var(--surface);
		stroke: var(--edge);
	}
	.more text {
		font: 700 10px var(--mono);
		fill: var(--text);
	}
	.compact :global(.pill),
	.compact :global(.badge) {
		display: none;
	}
</style>
