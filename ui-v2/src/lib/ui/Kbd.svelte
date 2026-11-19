<script lang="ts">
	let { keys }: { keys: string } = $props();
</script>

<kbd class="ui-kbd">{keys}</kbd>

<style>
	.ui-kbd {
		display: inline-block;
		padding: 0 var(--sp-1);
		border: 1px solid var(--border);
		border-radius: var(--radius-sm);
		background: var(--surface);
		color: var(--muted);
		font-size: var(--fs-1);
		line-height: 1.5;
	}
</style>
