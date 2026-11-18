<script lang="ts">
	/**
	 * A speech bubble over a character's head: what the model thinks while Panic stands at it (`think`), or that it
	 * waits for you (`wait`). Kept inside the stage `box`, with a trail of dots down to the head at (x, y). The only
	 * part of the map that follows the streamed thought, so a new word redraws this and nothing else.
	 */
	let {
		x,
		y,
		tone,
		label,
		text,
		box
	}: { x: number; y: number; tone: 'think' | 'wait'; label: string; text: string; box: { x: number; y: number; w: number; h: number } } =
		$props();

	const W = 190;
	const H = 56;
	const left = $derived(Math.max(box.x + 4, Math.min(x - 40, box.x + box.w - W - 4)));
	const top = $derived(Math.max(box.y + 4, y - H - 18));
</script>

<g class="bubble {tone}" aria-hidden="true">
	<circle class="dot" cx={x + 4} cy={y - 6} r="2.5" />
	<circle class="dot" cx={x + 8} cy={y - 13} r="3.5" />
	<foreignObject x={left} y={top} width={W} height={H}>
		<div class="box">
			<span>{label}</span>
			<p>{text}</p>
		</div>
	</foreignObject>
</g>

<style>
	.dot {
		fill: var(--surface);
		stroke: var(--edge);
		stroke-width: 1.2;
	}
	.box {
		box-sizing: border-box;
		height: 100%;
		padding: 5px 9px;
		border: 1.2px solid var(--edge);
		border-radius: 10px;
		background: var(--surface);
		box-shadow: 2px 2px 0 var(--edge);
		font-family: var(--mono);
		color: var(--text);
		overflow: hidden;
	}
	.wait .box {
		background: var(--amber);
	}
	.wait .dot {
		fill: var(--amber);
	}
	span {
		display: block;
		font-size: 8.5px;
		font-weight: 700;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--muted);
	}
	.wait span {
		color: var(--text);
	}
	p {
		margin: 2px 0 0;
		font-size: 10px;
		line-height: 1.35;
		font-style: italic;
		overflow: hidden;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow-wrap: anywhere;
	}
	.wait p {
		font-style: normal;
		font-weight: 600;
	}
</style>
