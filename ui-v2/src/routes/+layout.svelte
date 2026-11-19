<script lang="ts">
	import '../app.css';
	import { Tooltip } from 'bits-ui';
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { Inspector } from '$features/inspector';
	import { Search } from '$features/search';
	import { Settings } from '$features/settings';
	import { Conversation, TopBar } from '$features/shell';
	import { page } from '$app/state';
	import { Sidebar } from '$features/sidebar';
	import { chat } from '$lib/state/chat.svelte';
	import { inspector } from '$lib/state/inspector.svelte';
	import { layout } from '$lib/state/layout.svelte';

	let { children } = $props();

	/** Under this width the sidebar and the inspector cover the conversation instead of sitting beside it. */
	let narrow = $state(false);
	$effect(() => {
		const q = matchMedia('(max-width: 900px)');
		narrow = q.matches;
		const on = () => (narrow = q.matches);
		q.addEventListener('change', on);
		return () => q.removeEventListener('change', on);
	});
	// on a narrow screen the sidebar starts closed (a drawer); the wide-screen preference is kept for later
	let drawer = $state(false);
	const sidebarShown = $derived(narrow ? drawer : layout.sidebar);

	// a new conversation gets its route once its first answer starts
	chat.onStarted = (id) => void goto(`/c/${id}`, { replaceState: true, keepFocus: true, noScroll: true });

	$effect(() => {
		// once per page: nothing chat.init reads may make this run again
		untrack(() => void chat.init());
		// what runs on the server can change from elsewhere (panic.json, the CLI): look again now and then
		const timer = setInterval(() => void chat.refresh(), 15_000);
		const visible = () => document.visibilityState === 'visible' && void chat.refresh();
		document.addEventListener('visibilitychange', visible);
		return () => {
			clearInterval(timer);
			document.removeEventListener('visibilitychange', visible);
		};
	});

	const title = $derived(chat.fresh ? 'New chat' : (chat.conversations.find((c) => c.id === chat.session)?.title || 'Chat'));

	function onkeydown(e: KeyboardEvent) {
		const mod = e.ctrlKey || e.metaKey;
		const key = e.key.toLowerCase();
		if (mod && key === 'b') {
			e.preventDefault();
			if (narrow) drawer = !drawer;
			else layout.toggleSidebar();
		} else if (mod && key === 'i') {
			e.preventDefault();
			void inspector.toggle();
		} else if (mod && key === 'k') {
			e.preventDefault();
			layout.search = true;
		} else if (mod && e.shiftKey && key === 'o') {
			e.preventDefault();
			void goto('/');
		} else if (e.key === 'Escape' && !e.defaultPrevented && !document.querySelector('[role="dialog"], [role="menu"], [role="listbox"]')) {
			if (drawer) drawer = false;
			else if (inspector.panel) void inspector.close();
		}
	}
</script>

<svelte:window {onkeydown} />
<svelte:head><title>{title} · Panic</title></svelte:head>

<Tooltip.Provider delayDuration={400}>
	<div class="app" class:narrow class:with-sidebar={sidebarShown && !narrow} class:with-inspector={!!inspector.panel && !narrow}>
		{#if sidebarShown}
			{#if narrow}<button class="scrim" aria-label="Close sidebar" onclick={() => (drawer = false)}></button>{/if}
			<div class="sidebar"><Sidebar onnavigate={() => (drawer = false)} /></div>
		{/if}
		<main>
			<TopBar {title} />
			<!-- one Conversation for every route: going from / to /c/<id> as the first answer starts must not
			     remount it (a draft, an open card or folded groups would be lost). Routes only pick the session. -->
			{#if page.error}{@render children()}{:else}<Conversation />{@render children()}{/if}
		</main>
		{#if inspector.panel}
			<div class="inspector"><Inspector /></div>
		{/if}
	</div>
	<Settings />
	<Search />
</Tooltip.Provider>

<style>
	.app {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		height: 100dvh;
		overflow: hidden;
	}
	.app.with-sidebar {
		grid-template-columns: var(--sidebar-w) minmax(0, 1fr);
	}
	.app.with-inspector {
		grid-template-columns: minmax(0, 1fr) var(--inspector-w);
	}
	.app.with-sidebar.with-inspector {
		grid-template-columns: var(--sidebar-w) minmax(0, 1fr) var(--inspector-w);
	}
	.sidebar {
		min-height: 0;
		background: var(--surface);
		border-right: 1px solid var(--border);
	}
	main {
		display: flex;
		flex-direction: column;
		min-width: 0;
		min-height: 0;
	}
	.inspector {
		min-height: 0;
		background: var(--bg);
		border-left: 1px solid var(--border);
	}
	.narrow .sidebar,
	.narrow .inspector {
		position: fixed;
		top: 0;
		bottom: 0;
		z-index: 30;
		box-shadow: var(--shadow-pop);
	}
	.narrow .sidebar {
		left: 0;
		width: min(var(--sidebar-w), 85vw);
	}
	.narrow .inspector {
		right: 0;
		width: min(var(--inspector-w), 100vw);
	}
	.scrim {
		position: fixed;
		inset: 0;
		z-index: 29;
		border: 0;
		background: var(--scrim);
	}
</style>
