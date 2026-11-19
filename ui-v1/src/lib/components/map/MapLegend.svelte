<script lang="ts">
	import { PACKET } from './AgentMap.svelte';

	/** What the colours of the packets Panic carries mean. */
	const items = (['prompt', 'read', 'write', 'command', 'network'] as const).map((k) => PACKET[k]);
</script>

<ul class="legend" aria-label="Packets Panic carries">
	<li class="head">Panic carries</li>
	{#each items as item (item.label)}
		<li><i style:background={item.color}></i>{item.label}</li>
	{/each}
</ul>

<style>
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: 4px 12px;
		margin: 0;
		padding: 0;
		list-style: none;
		font-size: 10.5px;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--muted);
	}
	li {
		display: flex;
		align-items: center;
		gap: 5px;
	}
	.head {
		font-weight: 700;
		color: var(--text);
	}
	i {
		width: 9px;
		height: 9px;
		border: 1px solid var(--text);
		border-radius: 2px;
	}
</style>
