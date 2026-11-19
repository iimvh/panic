<script lang="ts">
	import type { Body } from '$lib/motion';

	/**
	 * Panic, or one of its clones, standing or walking on the map: the mascot over its shadow, leaning and bouncing as
	 * it walks, with the packet it carries over its head. Clones are smaller, on a coloured ring (violet: a sub-agent,
	 * amber: a call running beside Panic's), and a sub-agent's clone has its name under its feet.
	 */
	let { body, color }: { body: Body; color?: string } = $props();

	const size = $derived(body.kind === 'main' ? 44 : 30);
	const bob = $derived(body.moving ? Math.abs(Math.sin(body.walked / 9)) * 3.5 : 0);
	const tilt = $derived(body.moving ? 7 * body.facing : 0);
	const who = $derived(body.kind === 'main' ? 'Panic' : body.name ? `${body.name} (sub-agent)` : 'A clone of Panic');
	const where = $derived(body.merging ? 'going back to merge' : body.moving ? `walking to ${body.target}` : body.waiting ? `waiting for you at ${body.at}` : `at ${body.at}`);
	const tagWidth = $derived(body.name ? body.name.length * 6.2 + 12 : 0);
</script>

<g class="actor {body.kind}" class:merging={body.merging} transform="translate({body.x.toFixed(1)} {body.y.toFixed(1)})">
	<title>{who}, {where}</title>
	{#if body.kind !== 'main'}
		<ellipse class="ring" rx={size * 0.55} ry={size * 0.2} />
	{/if}
	<ellipse class="shadow" rx={size * 0.36} ry={size * 0.12} />
	<g transform="translate(0 {(-bob).toFixed(1)}) rotate({tilt}) scale({body.facing} 1)">
		<image href="/panic.png" x={-size / 2} y={-size + 2} width={size} height={size} />
	</g>
	{#if body.carry && color}
		<rect class="packet" x="-5" y={-size - 12 - bob} width="10" height="10" rx="2" style:fill={color} />
	{/if}
	{#if body.name}
		<g class="tag">
			<rect x={-tagWidth / 2} y="7" width={tagWidth} height="14" rx="7" />
			<text y="17.5" text-anchor="middle">{body.name}</text>
		</g>
	{/if}
</g>

<style>
	.actor {
		pointer-events: auto;
	}
	.shadow {
		fill: var(--text);
		opacity: 0.18;
	}
	.ring {
		fill: none;
		stroke: var(--violet);
		stroke-width: 3;
	}
	.parallel .ring {
		stroke: var(--amber);
	}
	.merging {
		opacity: 0.7;
	}
	.packet {
		stroke: var(--text);
		stroke-width: 1.2;
	}
	.tag rect {
		fill: var(--violet);
		stroke: var(--edge);
	}
	.tag text {
		font: 700 9.5px var(--mono);
		fill: var(--text);
	}
</style>
