<script lang="ts">
	/** The only moving thing in the interface: something is running. */
	let { size = 12, label }: { size?: number; label?: string } = $props();
</script>

<span class="spinner" style:width="{size}px" style:height="{size}px" role={label ? 'status' : undefined} aria-label={label}></span>

<style>
	.spinner {
		display: inline-block;
		flex: none;
		border: 1.5px solid var(--border-strong);
		border-top-color: var(--text-2);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(1turn);
		}
	}
</style>
