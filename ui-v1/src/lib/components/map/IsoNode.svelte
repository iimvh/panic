<script lang="ts" module>
	const FILL: Record<string, string> = {
		home: 'var(--blue)',
		model: 'var(--orange)',
		gate: 'var(--amber)',
		workspace: 'var(--violet)',
		shell: 'var(--text)',
		mcp: 'var(--green)',
		agent: 'var(--violet)',
		memory: 'var(--amber)',
		skills: 'var(--amber)',
		todo: 'var(--amber)',
		reviewer: 'var(--green)',
		you: 'var(--surface)',
		tool: 'var(--surface-2)'
	};
	const GLYPH: Record<string, string> = {
		model: '✦',
		shell: '>_',
		mcp: '⇄',
		agent: '◇',
		memory: 'M',
		skills: 'S',
		todo: '✓',
		reviewer: '✓',
		tool: '⚙'
	};
</script>

<script lang="ts">
	import { dims, ROLES, type SceneNode } from '$lib/scene';

	/**
	 * One block of the agent map, drawn in isometric: a cube for most parts, a cylinder for the workspace (a store),
	 * a screen on a plinth for you, a flat charging dock for Panic's home. Its state shows on it: faint when unused, a pulse when working, a red ✕ when it
	 * failed, a pill with what it does now and a badge with how often it was used.
	 */
	let { node, current = false }: { node: SceneNode; current?: boolean } = $props();

	const d = $derived(dims(node.small));
	const fill = $derived(FILL[node.kind] ?? 'var(--surface-2)');
	const x = $derived(node.x);
	const y = $derived(node.y);
	const top = $derived(`M${x},${y - d.b} L${x + d.a},${y} L${x},${y + d.b} L${x - d.a},${y} Z`);
	const left = $derived(`M${x - d.a},${y} L${x},${y + d.b} L${x},${y + d.b + d.d} L${x - d.a},${y + d.d} Z`);
	const right = $derived(`M${x},${y + d.b} L${x + d.a},${y} L${x + d.a},${y + d.d} L${x},${y + d.b + d.d} Z`);
	const floor = $derived(`M${x},${y - d.b + d.d} L${x + d.a + 10},${y + d.d} L${x},${y + d.b + d.d + 6} L${x - d.a - 10},${y + d.d} Z`);
	const pillWidth = $derived(node.pill ? node.pill.text.length * 6.4 + 16 : 0);
	const badgeWidth = $derived(node.badge ? node.badge.length * 6 + 10 : 0);
	const labelY = $derived(y + d.b + d.d + 16);

</script>

<g class="node {node.kind} {node.state}" class:current style:--fill={fill}>
	<title>{ROLES[node.kind].title}: {node.label}{node.pill ? ` · ${node.pill.text}` : ''}</title>
	{#if (node.state === 'active' || node.state === 'waiting') && node.kind !== 'home'}
		<path class="pulse" d={floor} />
	{/if}
	{#if node.kind === 'workspace'}
		{@const rx = d.a * 0.82}
		{@const ry = d.b * 0.78}
		{@const h = d.d + 10}
		<path class="face side" d="M{x - rx},{y} L{x - rx},{y + h} A{rx} {ry} 0 0 0 {x + rx},{y + h} L{x + rx},{y} Z" />
		<path class="seam" d="M{x - rx},{y + h / 2} A{rx} {ry} 0 0 0 {x + rx},{y + h / 2}" />
		<ellipse class="face top" cx={x} cy={y} {rx} {ry} />
		<path class="glyph-line" d="M{x - 12},{y - 2} h24 M{x - 12},{y + 4} h16" />
	{:else if node.kind === 'home'}
		<!-- a low pad Panic stands on, a charging pad on it, a post with a lamp that turns while Panic is out -->
		{@const a = d.a * 0.92}
		{@const b = d.b * 0.92}
		{@const px = x + a - 12}
		<path class="face left" d="M{x - a},{y} L{x},{y + b} L{x},{y + b + 8} L{x - a},{y + 8} Z" />
		<path class="face right" d="M{x},{y + b} L{x + a},{y} L{x + a},{y + 8} L{x},{y + b + 8} Z" />
		<path class="face top" d="M{x},{y - b} L{x + a},{y} L{x},{y + b} L{x - a},{y} Z" />
		<ellipse class="pad" cx={x} cy={y} rx={a * 0.56} ry={b * 0.56} />
		<path class="bolt" d="M{x + a / 2 + 2},{y + b / 2 - 2} l-5,7 h4 l-2,6 l6,-8 h-4 Z" />
		<path class="post" d="M{px},{y - 4} V{y - 30}" />
		<circle class="lamp" cx={px} cy={y - 35} r="5" />
	{:else if node.kind === 'you'}
		<!-- a low plinth, then the screen on it -->
		<path class="face left" d="M{x - d.a},{y} L{x},{y + d.b} L{x},{y + d.b + 8} L{x - d.a},{y + 8} Z" />
		<path class="face right" d="M{x},{y + d.b} L{x + d.a},{y} L{x + d.a},{y + 8} L{x},{y + d.b + 8} Z" />
		<path class="face top" d={top} />
		<rect class="screen" x={x - 26} y={y - 50} width="52" height="36" rx="4" />
		<rect class="screen-in" x={x - 20} y={y - 44} width="40" height="22" rx="2" />
		<path class="stand" d="M{x},{y - 14} L{x},{y - 2} M{x - 10},{y - 2} h20" />
	{:else}
		<path class="face left" d={left} />
		<path class="face right" d={right} />
		<path class="face top" d={top} />
		{#if node.kind === 'gate'}
			<path
				class="shield"
				d="M{x},{y - 14} l11,4 v7 c0,7 -5,11 -11,13 c-6,-2 -11,-6 -11,-13 v-7 Z"
			/>
		{:else if GLYPH[node.kind]}
			<text class="glyph" x={x} y={y + 4} text-anchor="middle">{GLYPH[node.kind]}</text>
		{/if}
	{/if}
	{#if node.state === 'error'}
		<path class="cross" d="M{x - 10},{y - 8} l20,16 M{x + 10},{y - 8} l-20,16" />
	{/if}
	<text class="name" x={x} y={labelY} text-anchor="middle">{node.label}</text>
	{#if node.badge}
		<g class="badge">
			<rect x={x + d.a - badgeWidth / 2} y={y - d.b - 6} width={badgeWidth} height="15" rx="7.5" />
			<text x={x + d.a} y={y - d.b + 5} text-anchor="middle">{node.badge}</text>
		</g>
	{/if}
	{#if node.pill}
		{@const py = node.kind === 'you' ? y - 74 : y - d.b - 30}
		<g class="pill {node.pill.tone}">
			<rect x={x - pillWidth / 2} y={py} width={pillWidth} height="18" rx="9" />
			<text x={x} y={py + 12.5} text-anchor="middle">{node.pill.text}</text>
		</g>
	{/if}
</g>

<style>
	.node {
		transition: opacity 0.25s;
	}
	.face {
		fill: var(--fill);
		stroke: var(--edge);
		stroke-width: 1.2;
		stroke-linejoin: round;
	}
	.face.left,
	.face.side {
		fill: color-mix(in srgb, var(--fill) 78%, var(--edge));
	}
	.face.right {
		fill: color-mix(in srgb, var(--fill) 88%, var(--edge));
	}
	.seam {
		fill: none;
		stroke: var(--edge);
		stroke-width: 1;
		opacity: 0.5;
	}
	.glyph {
		font: 700 14px var(--mono);
		fill: var(--text);
		pointer-events: none;
	}
	.shell .glyph {
		fill: var(--green);
	}
	.glyph-line,
	.stand {
		fill: none;
		stroke: var(--edge);
		stroke-width: 2;
		stroke-linecap: round;
	}
	.shield {
		fill: var(--surface);
		stroke: var(--edge);
		stroke-width: 1.4;
		stroke-linejoin: round;
	}
	.screen {
		fill: var(--surface);
		stroke: var(--edge);
		stroke-width: 1.4;
	}
	.screen-in {
		fill: var(--blue);
	}
	.name {
		font: 700 11px var(--mono);
		letter-spacing: 0.06em;
		text-transform: uppercase;
		fill: var(--text);
	}
	.badge rect {
		fill: var(--surface);
		stroke: var(--edge);
	}
	.badge text {
		font: 600 9.5px var(--mono);
		fill: var(--text);
	}
	.pill rect {
		stroke: var(--edge);
		stroke-width: 1;
	}
	.pill text {
		font: 600 10px var(--mono);
		letter-spacing: 0.03em;
		fill: var(--text);
	}
	.pill.ok rect {
		fill: var(--green);
	}
	.pill.warn rect {
		fill: var(--amber);
	}
	.pill.error rect {
		fill: var(--danger);
		stroke: var(--danger);
	}
	.pill.error text {
		fill: #fff;
	}
	.pill.info rect {
		fill: var(--surface);
	}
	.pill.muted rect {
		fill: var(--bg);
		stroke: var(--border);
	}
	.pill.muted text {
		fill: var(--muted);
	}
	.cross {
		stroke: var(--danger);
		stroke-width: 4;
		stroke-linecap: round;
	}
	.pulse {
		fill: none;
		stroke: var(--rust);
		stroke-width: 2;
		transform-box: fill-box;
		transform-origin: center;
		animation: pulse 1.4s ease-out infinite;
	}
	.waiting .pulse {
		stroke: var(--warn);
	}
	@keyframes pulse {
		0% {
			opacity: 0.9;
			transform: scale(0.92);
		}
		100% {
			opacity: 0;
			transform: scale(1.25);
		}
	}
	/* not used in this answer: a faint outline of where it would be */
	.ghost .face,
	.ghost .screen {
		fill: var(--bg);
		stroke: var(--border);
		stroke-dasharray: 4 3;
	}
	.ghost .glyph,
	.ghost .name,
	.ghost .shield,
	.ghost .glyph-line,
	.ghost .screen-in {
		opacity: 0.4;
	}
	.dim {
		opacity: 0.4;
	}
	.error .face {
		stroke: var(--danger);
		stroke-width: 1.8;
	}
	.current .face.top,
	.active .face.top {
		stroke: var(--text);
		stroke-width: 2;
	}
	.pad {
		fill: var(--green);
		stroke: var(--edge);
		stroke-width: 1.2;
	}
	/* Panic is out: the pad waits empty and the lamp turns */
	.home.active .pad {
		fill: var(--bg);
		stroke-dasharray: 4 3;
	}
	.bolt {
		fill: var(--amber);
		stroke: var(--edge);
		stroke-width: 1;
		stroke-linejoin: round;
	}
	.post {
		stroke: var(--edge);
		stroke-width: 2;
		stroke-linecap: round;
	}
	.lamp {
		fill: var(--surface);
		stroke: var(--edge);
		stroke-width: 1.2;
	}
	.home.active .lamp {
		fill: var(--danger);
		animation: lamp 0.9s steps(2) infinite;
	}
	@keyframes lamp {
		50% {
			fill: var(--surface);
		}
	}
	.ghost .pad,
	.ghost .bolt,
	.ghost .lamp {
		opacity: 0.4;
	}
	@media (prefers-reduced-motion: reduce) {
		.pulse {
			animation: none;
			opacity: 0.8;
		}
		.home.active .lamp {
			animation: none;
		}
	}
</style>
