<script lang="ts">
	import { page } from '$app/state';
</script>

<div class="error">
	<h1>{page.status}</h1>
	<p>{page.error?.message ?? 'Something went wrong.'}</p>
	<a href="/">Start a new chat</a>
</div>

<style>
	.error {
		margin: 20vh auto;
		max-width: 420px;
		padding: 0 var(--sp-4);
		text-align: center;
	}
	h1 {
		margin: 0;
		font-size: var(--fs-5);
	}
	p {
		color: var(--muted);
	}
	a {
		color: var(--accent);
	}
</style>
