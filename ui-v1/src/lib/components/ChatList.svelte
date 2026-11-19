<script lang="ts">
	import { chat } from '$lib/chat.svelte';
	import { ago, group, type ConversationInfo } from '$lib/conversations';

	/**
	 * The stored chats, newest first, grouped by project (workspace) when the server has projects: open a chat,
	 * start a new one (in a project with its "+"), delete one (asked again before it goes), add or remove a
	 * project. A sidebar on wide screens, hidden while `collapsed`; on narrow ones a drawer, shown while `open`.
	 */
	let {
		open = false,
		collapsed = false,
		onclose
	}: { open?: boolean; collapsed?: boolean; onclose: () => void } = $props();

	let confirming = $state<string | null>(null);
	let problem = $state<string | null>(null);
	let now = $state(Date.now());
	const groups = $derived(group(chat.conversations, chat.workspaces, chat.session, now));

	/** The folder to add by typing: when no dialog could pick one, or the user prefers to type. */
	let typing = $state(false);
	let typed = $state('');
	let adding = $state(false);
	let removing = $state<string | null>(null);

	$effect(() => {
		const timer = setInterval(() => (now = Date.now()), 60_000);
		return () => clearInterval(timer);
	});

	async function pick(id: string) {
		confirming = null;
		problem = null;
		onclose();
		await chat.open(id);
	}

	async function fresh() {
		confirming = null;
		problem = null;
		onclose();
		await chat.newChat();
	}

	async function freshIn(path: string) {
		confirming = null;
		problem = null;
		onclose();
		problem = await chat.newChat(path);
	}

	async function addProject(path?: string) {
		adding = true;
		problem = null;
		const r = await chat.addProject(path);
		adding = false;
		if (r.error) problem = r.text;
		else if (r.needsPath) typing = true;
		else if (path) {
			typed = '';
			typing = false;
		}
	}

	/** The project whose path was just copied, for a moment. */
	let copied = $state<string | null>(null);

	async function copyPath(path: string) {
		try {
			await navigator.clipboard.writeText(path);
			copied = path;
			setTimeout(() => {
				if (copied === path) copied = null;
			}, 1500);
		} catch {
			// no clipboard (an insecure origin): show the path, to select by hand
			problem = path;
		}
	}

	async function openProject(path: string) {
		problem = await chat.openProject(path);
	}

	async function removeProject(path: string) {
		problem = await chat.removeProject(path);
		removing = null;
	}

	async function remove(id: string) {
		problem = await chat.remove(id);
		confirming = null;
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			if (confirming) confirming = null;
			else if (removing) removing = null;
			else onclose();
		}
	}
</script>

<svelte:window {onkeydown} />

{#snippet row(c: ConversationInfo)}
	<li class:current={c.id === chat.session}>
		{#if confirming === c.id}
			<div class="confirm">
				<span>Delete this chat?</span>
				<button class="btn danger" onclick={() => remove(c.id)}>Delete</button>
				<button class="btn" onclick={() => (confirming = null)}>Cancel</button>
			</div>
		{:else}
			<button
				class="item"
				onclick={() => pick(c.id)}
				aria-current={c.id === chat.session ? 'page' : undefined}
				title={c.title}
			>
				<span class="title">{c.title || 'New chat'}</span>
				{#if c.running}
					<span class="running" title="Answering" aria-label="answering"></span>
				{:else}
					<span class="time">{ago(c.updatedAt, now)}</span>
				{/if}
			</button>
			<button
				class="delete"
				aria-label={`Delete "${c.title || 'New chat'}"`}
				onclick={() => (confirming = c.id)}
				disabled={c.running}
				title={c.running ? 'Stop the answer before deleting this chat' : undefined}
			>
				<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"
					><path
						fill="none"
						stroke="currentColor"
						stroke-width="1.8"
						stroke-linecap="round"
						d="M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12"
					/></svg
				>
			</button>
		{/if}
	</li>
{/snippet}

{#if open}
	<button class="scrim" aria-label="Close the chat list" onclick={onclose}></button>
{/if}
<nav class:open class:collapsed aria-label="Chats">
	<button class="btn new" onclick={fresh} disabled={!!chat.offline}>
		<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"
			><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 5v14M5 12h14" /></svg
		>
		New chat
	</button>
	{#if problem}<p class="problem" role="alert">{problem}</p>{/if}

	{#if chat.workspaces}
		<div class="head">
			<span>Projects</span>
			{#if adding}
				<button class="link" onclick={() => chat.cancelAddProject()}>Cancel</button>
			{:else}
				<button class="link" onclick={() => addProject()} disabled={!!chat.offline}>Add project</button>
			{/if}
		</div>
		{#if adding}
			<p class="note" role="status">
				A folder dialog is open on the computer that runs Panic. Can't see it? Cancel and type the path.
			</p>
		{/if}
		{#if typing}
			<form
				class="typing"
				onsubmit={(e) => {
					e.preventDefault();
					if (typed.trim()) void addProject(typed.trim());
				}}
			>
				<input bind:value={typed} placeholder="Folder path, e.g. D:\work\app" aria-label="Folder path" />
				<button class="btn" disabled={adding || !typed.trim()}>Add</button>
				<button class="btn" type="button" onclick={() => (typing = false)}>Cancel</button>
			</form>
		{:else}
			<button class="link small" onclick={() => (typing = true)}>or type a path</button>
		{/if}

		{#each groups.groups as g (g.project.path)}
			<section class="project" aria-label={g.project.name}>
				<div class="project-title">
					<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"
						><path
							fill="none"
							stroke="currentColor"
							stroke-width="1.8"
							stroke-linejoin="round"
							d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
						/></svg
					>
					<span class="name">{g.project.name}</span>
					{#if removing === g.project.path}
						<button class="btn danger" onclick={() => removeProject(g.project.path)}>Remove</button>
						<button class="btn" onclick={() => (removing = null)}>Cancel</button>
					{:else}
						<button
							class="icon"
							aria-label={`Open ${g.project.name} in the file manager`}
							title="Open folder"
							onclick={() => openProject(g.project.path)}
							disabled={!!chat.offline}
						>
							<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"
								><path
									fill="none"
									stroke="currentColor"
									stroke-width="1.8"
									stroke-linecap="round"
									stroke-linejoin="round"
									d="M14 4h6v6M20 4l-9 9M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4"
								/></svg
							>
						</button>
						<button
							class="icon"
							aria-label={`New chat in ${g.project.name}`}
							title="New chat in this project"
							onclick={() => freshIn(g.project.path)}
							disabled={!!chat.offline}
						>
							<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"
								><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 5v14M5 12h14" /></svg
							>
						</button>
						<button
							class="icon"
							aria-label={`Remove project ${g.project.name}`}
							title="Remove project (its chats are hidden)"
							onclick={() => (removing = g.project.path)}
						>
							<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"
								><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M6 6l12 12M18 6L6 18" /></svg
							>
						</button>
					{/if}
				</div>
				<button class="path" onclick={() => copyPath(g.project.path)} title="Copy the path: {g.project.path}">
					{#if copied === g.project.path}
						<span class="copied">Copied</span>
					{:else}
						<span class="dir"><bdi dir="ltr">{g.project.path}</bdi></span>
					{/if}
				</button>
				{#if removing === g.project.path}
					<p class="note">Its chats are hidden until you add the folder again.</p>
				{/if}
				<ul>
					{#each g.chats as c (c.id)}{@render row(c)}{:else}<li class="none">No chats yet</li>{/each}
				</ul>
			</section>
		{/each}
		{#if groups.groups.length > 0}<div class="head"><span>Chats</span></div>{/if}
	{/if}

	<ul>
		{#each groups.rest as c (c.id)}{@render row(c)}{:else}<li class="none">No chats yet</li>{/each}
	</ul>
</nav>

<style>
	nav {
		display: flex;
		flex-direction: column;
		gap: 8px;
		width: 264px;
		flex: none;
		padding: 12px 8px;
		border-right: 1px solid var(--border);
		background: var(--surface);
		overflow-y: auto;
	}
	.new {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	li {
		display: flex;
		align-items: center;
		border-radius: 8px;
	}
	li:hover,
	li:focus-within {
		background: var(--surface-2);
	}
	li.current {
		background: color-mix(in srgb, var(--accent) 12%, var(--surface-2));
	}
	.item {
		flex: 1;
		min-width: 0;
		display: flex;
		align-items: baseline;
		gap: 8px;
		padding: 7px 4px 7px 10px;
		border: 0;
		background: none;
		color: var(--text);
		font: inherit;
		font-size: 14px;
		text-align: left;
		cursor: pointer;
	}
	.title {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.time {
		flex: none;
		font-size: 12px;
		color: var(--muted);
	}
	.running {
		flex: none;
		align-self: center;
		width: 8px;
		height: 8px;
		margin-right: 4px;
		border-radius: 50%;
		background: var(--accent);
		animation: pulse 1.2s ease-in-out infinite;
	}
	@keyframes pulse {
		50% {
			opacity: 0.3;
		}
	}
	.delete:disabled {
		visibility: hidden;
	}
	.delete {
		flex: none;
		display: grid;
		place-items: center;
		width: 28px;
		height: 28px;
		margin-right: 4px;
		border: 0;
		border-radius: 6px;
		background: none;
		color: var(--muted);
		cursor: pointer;
		opacity: 0;
	}
	li:hover .delete,
	li:focus-within .delete,
	li.current .delete {
		opacity: 1;
	}
	.delete:hover {
		color: var(--danger);
		background: var(--surface);
	}
	.item:focus-visible,
	.delete:focus-visible {
		outline: 2px solid var(--accent);
		outline-offset: -2px;
		border-radius: 8px;
	}
	.confirm {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px;
		width: 100%;
		padding: 6px 8px;
		font-size: 13px;
	}
	.confirm span {
		flex-basis: 100%;
	}
	.confirm .btn,
	.project-title .btn {
		font-size: 13px;
		padding: 3px 10px;
	}
	.btn.danger {
		color: #fff;
		background: var(--danger);
		border-color: var(--danger);
	}
	.problem {
		margin: 0;
		padding: 6px 8px;
		font-size: 13px;
		color: var(--danger);
	}
	.note {
		margin: 0;
		padding: 0 10px;
		font-size: 12px;
		color: var(--muted);
	}
	.none {
		padding: 8px 10px;
		font-size: 13px;
		color: var(--muted);
	}
	.head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		padding: 6px 10px 0;
		font-size: 12px;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--muted);
	}
	.link {
		border: 0;
		background: none;
		padding: 0;
		font: inherit;
		font-size: 12px;
		text-transform: none;
		letter-spacing: 0;
		color: var(--accent);
		cursor: pointer;
	}
	.link.small {
		align-self: flex-start;
		margin: -4px 10px 0;
		color: var(--muted);
	}
	.link:disabled {
		opacity: 0.5;
		cursor: default;
	}
	.typing {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		padding: 0 8px;
	}
	.typing input {
		flex-basis: 100%;
		min-width: 0;
		padding: 6px 8px;
		border: 1px solid var(--border);
		border-radius: 6px;
		background: var(--surface-2);
		color: var(--text);
		font: inherit;
		font-size: 13px;
	}
	.project {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.project-title {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 4px 4px 4px 10px;
		font-size: 14px;
		font-weight: 600;
	}
	.project-title .name {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.path {
		display: flex;
		min-width: 0;
		margin: -4px 4px 2px 31px;
		padding: 1px 4px;
		border: 0;
		border-radius: 4px;
		background: none;
		color: var(--muted);
		font: 11px var(--mono);
		text-align: left;
		cursor: copy;
	}
	.path:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	/* the end of a path names the folder: cut at the start instead (rtl box, the path itself kept ltr) */
	.dir {
		overflow: hidden;
		white-space: nowrap;
		text-overflow: ellipsis;
		direction: rtl;
		text-align: left;
	}
	.copied {
		color: var(--ok);
	}
	.icon {
		flex: none;
		display: grid;
		place-items: center;
		width: 26px;
		height: 26px;
		border: 0;
		border-radius: 6px;
		background: none;
		color: var(--muted);
		cursor: pointer;
	}
	.icon:hover:not(:disabled) {
		color: var(--text);
		background: var(--surface-2);
	}
	.project ul {
		padding-left: 10px;
	}
	.scrim {
		display: none;
	}
	@media (min-width: 861px) {
		nav.collapsed {
			display: none;
		}
	}
	@media (max-width: 860px) {
		nav {
			position: fixed;
			inset: 0 auto 0 0;
			z-index: 20;
			width: min(300px, 85vw);
			transform: translateX(-100%);
			transition: transform 0.18s ease;
			box-shadow: 0 0 24px rgb(0 0 0 / 0.2);
		}
		nav.open {
			transform: none;
		}
		.scrim {
			display: block;
			position: fixed;
			inset: 0;
			z-index: 19;
			border: 0;
			background: rgb(0 0 0 / 0.35);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		nav {
			transition: none;
		}
		.running {
			animation: none;
		}
	}
</style>
