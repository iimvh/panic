<script lang="ts">
	import { untrack } from 'svelte';
	import { chat } from '$lib/state/chat.svelte';

	// `/` is a new conversation; it gets its own route once its first answer starts. Runs once per visit:
	// what newChat reads must not make this effect run again (sending would start yet another chat).
	$effect(() => {
		untrack(() => void chat.newChat());
	});
</script>

