<script lang="ts">
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Conversation } from '$features/shell';
	import { chat } from '$lib/state/chat.svelte';
	import { validId } from '$lib/state/session';

	// `/c/<id>` shows a stored conversation (or one whose first answer just started); runs again only when the id
	// changes, not when the conversation does
	$effect(() => {
		const id = page.params.id ?? '';
		untrack(() => {
			if (validId(id)) void chat.open(id);
			else void goto('/', { replaceState: true });
		});
	});
</script>

<Conversation />
