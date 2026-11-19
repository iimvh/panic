<script lang="ts">
	import { onMount, tick, untrack } from 'svelte';
	import { chat } from '$lib/chat.svelte';
	import Composer from '$lib/components/Composer.svelte';
	import ChatList from '$lib/components/ChatList.svelte';
	import Header from '$lib/components/Header.svelte';
	import MapPane from '$lib/components/map/MapPane.svelte';
	import MapStrip from '$lib/components/map/MapStrip.svelte';
	import MessageView from '$lib/components/MessageView.svelte';
	import Settings from '$lib/components/Settings.svelte';
	import { actors } from '$lib/actors';
	import { liveThoughts, replayThoughts, scene } from '$lib/scene';
	import { view } from '$lib/view.svelte';
	import { load, save } from '$lib/storage';

	let settingsOpen = $state(false);
	let listOpen = $state(false);
	/** Wide screens: the chat list is hidden (kept in this browser); narrow ones show it as a drawer instead. */
	const COLLAPSED_KEY = 'panic.sidebar.collapsed';
	let collapsed = $state(load(COLLAPSED_KEY, (v) => v === 'true' || v === 'false', 'false') === 'true');

	/** The menu button and Ctrl/Cmd+B: hide or show the sidebar, or open the drawer on a narrow screen. */
	function toggleList() {
		if (narrow) {
			listOpen = !listOpen;
			return;
		}
		collapsed = !collapsed;
		save(COLLAPSED_KEY, String(collapsed));
	}
	let scroller: HTMLElement;
	let thread: HTMLElement;
	let stick = $state(true); // follow the answer while the user is at the bottom

	onMount(() => {
		void chat.load();
		// pick up keys added to panic.json, a provider switched in the CLI, ...
		const timer = setInterval(() => {
			if (!chat.busy && document.visibilityState === 'visible') void chat.refresh();
		}, 15000);
		return () => clearInterval(timer);
	});

	function onscroll() {
		stick = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
	}

	function toBottom() {
		scroller?.scrollTo({ top: scroller.scrollHeight });
	}

	$effect(() => {
		// once per layout change (streamed text, tool rows, approval cards...), not once per token
		const watch = new ResizeObserver(() => {
			if (stick) toBottom();
		});
		watch.observe(thread);
		return () => watch.disconnect();
	});

	$effect(() => {
		// a new message of the user's brings the view back to the bottom
		void chat.messages.length;
		stick = true;
		tick().then(toBottom);
	});

	const starters = ['Explain virtual threads in Java', 'Write a haiku about deadlines', '/help', '/models free'];

	// the agent map: one scene for the panel and the strip, ticking each second while the answer runs
	let now = $state(Date.now());
	$effect(() => {
		if (!view.streaming) return;
		const timer = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(timer);
	});
	const map = $derived(
		scene(view.turns, {
			model: chat.state?.model ?? null,
			connected: chat.state ? chat.state.connected : true,
			mode: view.mode,
			now,
			atStep: view.atStep
		})
	);

	// who stands where on the map: apart from the scene, which rebuilds every second for the clock
	const cast = $derived(actors(view.turns, { atStep: view.atStep }));

	// what is thought now (Panic and each sub-agent), or before the step replayed, for the narration and the bubbles:
	// apart from the scene and the cast, which must not rebuild with every frame of streamed thinking
	const thoughts = $derived(view.atStep === undefined ? liveThoughts(view.turns.at(-1)) : replayThoughts(view.turns, view.atStep));

	let width = $state(0);
	/** Under 860px the map is a sheet over the chat, with the strip always shown. */
	const narrow = $derived(width > 0 && width <= 860);
	const docked = $derived(!narrow && view.size !== 'mini');
	const floating = $derived(!docked && (narrow ? view.sheet || !!chat.inspected : !!chat.inspected));

	// a pick in the chat opens its detail; a pick on the rail keeps the tab (see view.pick)
	$effect(() => {
		const picked = chat.inspected;
		untrack(() => {
			if (!picked) {
				if (view.tab !== 'map') view.tab = 'map';
				return;
			}
			if (view.fromRail) {
				view.fromRail = false;
				return;
			}
			view.replay = undefined;
			view.playing = false;
			view.tab = picked.kind === 'trace' ? 'trace' : 'step';
		});
	});
	// another conversation, or a new answer in it: the replay stops and the map follows what happens now
	$effect(() => {
		void chat.session;
		void view.turns.length;
		untrack(() => {
			view.replay = undefined;
			view.playing = false;
		});
	});
	$effect(() => {
		if (!view.playing) return;
		const timer = setInterval(() => view.advance(), 900);
		return () => clearInterval(timer);
	});

	function closeFloating() {
		view.sheet = false;
		chat.inspected = null;
	}

	/** Map keys, unless the user is typing: M size, ← → steps, R replay, Space pause; Esc closes the detail. */
	function onkeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			if (chat.inspected || view.sheet) closeFloating();
			return;
		}
		// also while typing: a plain text box has no bold to lose
		if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'b' && !settingsOpen) {
			e.preventDefault();
			toggleList();
			return;
		}
		const t = e.target as HTMLElement | null;
		if (settingsOpen || e.ctrlKey || e.metaKey || e.altKey) return;
		if (t?.closest('input, textarea, select, [contenteditable="true"], [role="separator"]')) return;
		const usable = (docked || floating) && map.steps.length > 0;
		if ((e.key === 'm' || e.key === 'M') && !narrow) view.cycle();
		else if (e.key === 'ArrowLeft' && usable) view.step(-1);
		else if (e.key === 'ArrowRight' && usable) view.step(1);
		else if ((e.key === 'r' || e.key === 'R') && usable && !view.streaming) view.play(true);
		else if (e.key === ' ' && usable && !t?.closest('button')) {
			e.preventDefault();
			view.toggle();
		}
	}

	// the divider between the chat and the map: drag to resize, double-click for focus mode, arrows to nudge
	function drag(e: PointerEvent) {
		const bar = e.currentTarget as HTMLElement;
		bar.setPointerCapture(e.pointerId);
		const move = (ev: PointerEvent) => view.setWidth((window.innerWidth - ev.clientX) / window.innerWidth);
		const up = () => {
			bar.removeEventListener('pointermove', move);
			bar.removeEventListener('pointerup', up);
		};
		bar.addEventListener('pointermove', move);
		bar.addEventListener('pointerup', up);
	}

	function nudge(e: KeyboardEvent) {
		if (e.key === 'ArrowLeft') view.setWidth(view.width + 0.02);
		else if (e.key === 'ArrowRight') view.setWidth(view.width - 0.02);
		else return;
		e.preventDefault();
	}
</script>

<svelte:window bind:innerWidth={width} {onkeydown} />

<div class="shell" class:focus={docked && view.size === 'focus'}>
<ChatList open={listOpen} {collapsed} onclose={() => (listOpen = false)} />
<div class="app">
	<Header onsettings={() => (settingsOpen = true)} onmenu={toggleList} expanded={narrow ? listOpen : !collapsed} />

	{#if chat.offline}
		<div class="banner danger">
			{chat.offline}
			<button class="btn" onclick={() => chat.load()}>Retry</button>
		</div>
	{:else if chat.state && !chat.state.connected}
		<div class="banner">
			No LLM connected: the selected provider is not connected, or no model is chosen.
			<button class="btn" onclick={() => (settingsOpen = true)}>Open settings</button>
		</div>
	{/if}

	<main bind:this={scroller} {onscroll}>
		<div class="thread" bind:this={thread}>
			{#if chat.messages.length === 0}
				<div class="empty">
					<img src="/panic.png" alt="" width="96" height="96" />
					<h1>What can I help with?</h1>
					<div class="starters">
						{#each starters as s (s)}
							<button class="btn" onclick={() => chat.send(s)} disabled={chat.busy || !!chat.offline}>{s}</button>
						{/each}
					</div>
				</div>
			{:else}
				{#each chat.messages as message (message.id)}
					<MessageView {message} />
				{/each}
			{/if}
		</div>
	</main>

	{#if !stick && chat.busy}
		<button class="btn jump" onclick={() => ((stick = true), toBottom())}>↓ Jump to latest</button>
	{/if}

	{#if !docked}
		<div class="strip">
			<MapStrip scene={map} onopen={() => (narrow ? (view.sheet = true) : view.setSize('side'))} />
		</div>
	{/if}
	<Composer />

	{#if settingsOpen}
		<Settings onclose={() => (settingsOpen = false)} />
	{/if}
</div>
{#if docked}
	{#if view.size === 'side'}
		<!-- a focusable separator is a control (it resizes with the arrow keys) -->
		<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
		<div
			class="divider"
			role="separator"
			aria-orientation="vertical"
			aria-label="Resize the map"
			aria-valuenow={Math.round(view.width * 100)}
			aria-valuemin={30}
			aria-valuemax={70}
			tabindex="0"
			title="Drag to resize · double-click for focus"
			onpointerdown={drag}
			ondblclick={() => view.setSize('focus')}
			onkeydown={nudge}
		></div>
	{/if}
	<div class="dock" style:--share="{view.width * 100}vw">
		<MapPane scene={map} {cast} {thoughts} />
	</div>
{:else if floating}
	<button class="scrim" aria-label="Close the map panel" onclick={closeFloating}></button>
	<div class="float" class:sheet={narrow}>
		<MapPane scene={map} {cast} {thoughts} floating {narrow} onclose={closeFloating} />
	</div>
{/if}
</div>

<style>
	.shell {
		display: flex;
		height: 100dvh;
	}
	.app {
		position: relative;
		flex: 1;
		min-width: 340px;
		display: flex;
		flex-direction: column;
		height: 100dvh;
	}
	.focus .app {
		flex: 0 0 380px;
	}
	@media (max-width: 860px) {
		.app {
			min-width: 0;
		}
	}
	main {
		flex: 1;
		overflow-y: auto;
	}
	.divider {
		flex: none;
		width: 7px;
		margin: 0 -3px;
		z-index: 2;
		cursor: col-resize;
		touch-action: none;
		background: linear-gradient(var(--border), var(--border)) center / 1px 100% no-repeat;
	}
	.divider:hover,
	.divider:focus-visible {
		background: linear-gradient(var(--edge), var(--edge)) center / 3px 100% no-repeat;
		outline: none;
	}
	.dock {
		flex: 0 1 clamp(440px, var(--share), 70vw);
		min-width: 380px;
		height: 100dvh;
		border-left: 1px solid var(--border);
	}
	.focus .dock {
		flex: 1;
	}
	.strip {
		width: 100%;
		max-width: 760px;
		margin: 0 auto 8px;
		padding: 0 16px;
	}
	.scrim {
		position: fixed;
		inset: 0;
		z-index: 19;
		border: 0;
		background: rgb(29 27 24 / 0.3);
	}
	.float {
		position: fixed;
		inset: 0 0 0 auto;
		z-index: 20;
		width: min(560px, 92vw);
	}
	.float.sheet {
		inset: auto 0 0 0;
		width: auto;
		height: 88dvh;
	}
	.jump {
		position: absolute;
		left: 50%;
		bottom: 130px;
		transform: translateX(-50%);
		border-radius: 999px;
	}
	.thread {
		max-width: 760px;
		margin: 0 auto;
		padding: 24px 16px 8px;
	}
	.banner {
		display: flex;
		align-items: center;
		justify-content: center;
		flex-wrap: wrap;
		gap: 12px;
		padding: 8px 16px;
		font-size: 13px;
		background: var(--amber);
		border-bottom: 1px solid var(--edge);
	}
	.banner.danger {
		background: var(--rose);
	}
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		padding: 12vh 0 24px;
	}
	.empty h1 {
		margin: 14px 0 20px;
		font-size: 22px;
		font-weight: 700;
		letter-spacing: -0.02em;
	}
	.starters {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: 8px;
	}
	.starters .btn {
		font-size: 13px;
		color: var(--muted);
		border-radius: 999px;
	}
</style>
