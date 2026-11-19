<script lang="ts">
	import { goto } from '$app/navigation';
	import { ago, group, type ConversationInfo, type Project } from '$lib/core/conversations';
	import { chat } from '$lib/state/chat.svelte';
	import { layout } from '$lib/state/layout.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Dialog from '$lib/ui/Dialog.svelte';
	import Icon from '$lib/ui/Icon.svelte';
	import IconButton from '$lib/ui/IconButton.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';

	/**
	 * The stored chats, newest first, grouped by project when the server has projects. Each chat is a link
	 * (`/c/<id>`), so it opens in a new tab too. What is done less often (open the folder, copy its path, remove,
	 * delete) waits in a menu; what destroys something is confirmed.
	 */
	let { onnavigate }: { onnavigate?: () => void } = $props();

	let problem = $state<string | null>(null);
	let now = $state(Date.now());
	const groups = $derived(group(chat.conversations, chat.workspaces, chat.session, now));
	let folded = $state<Record<string, boolean>>({});

	$effect(() => {
		const timer = setInterval(() => (now = Date.now()), 60_000);
		return () => clearInterval(timer);
	});

	const href = (c: ConversationInfo) => (c.id === chat.session && chat.fresh ? '/' : `/c/${c.id}`);

	// --- delete a chat
	let deleting = $state<ConversationInfo | null>(null);
	async function remove() {
		const c = deleting;
		if (!c) return;
		deleting = null;
		const current = c.id === chat.session;
		problem = await chat.remove(c.id);
		if (!problem && current) await goto('/');
	}

	// --- projects
	let adding = $state(false);
	let picking = $state(false);
	let typed = $state('');
	let addProblem = $state<string | null>(null);
	let removing = $state<Project | null>(null);

	async function addProject(path?: string) {
		picking = !path;
		addProblem = null;
		const r = await chat.addProject(path);
		picking = false;
		if (r.error) addProblem = r.text;
		else if (r.needsPath) addProblem = path ? r.text : 'No folder dialog could open there. Type the path instead.';
		else {
			typed = '';
			adding = false;
		}
	}

	async function newChatIn(path: string) {
		problem = await chat.newChat(path);
		onnavigate?.();
		await goto('/');
	}

	async function copyPath(path: string) {
		try {
			await navigator.clipboard.writeText(path);
		} catch {
			problem = path; // no clipboard (an insecure origin): show the path, to select by hand
		}
	}

	async function removeProject() {
		const p = removing;
		removing = null;
		if (p) problem = await chat.removeProject(p.path);
	}

	function projectMenu(p: Project): MenuItem[] {
		return [
			{ label: 'New chat here', icon: 'plus', onSelect: () => void newChatIn(p.path) },
			{ label: 'Open folder', icon: 'external', onSelect: async () => (problem = await chat.openProject(p.path)) },
			{ label: 'Copy path', icon: 'copy', onSelect: () => void copyPath(p.path) },
			'separator',
			{ label: 'Remove project…', icon: 'x', danger: true, onSelect: () => (removing = p) }
		];
	}
</script>

{#snippet row(c: ConversationInfo)}
	{@const current = c.id === chat.session}
	<li class:current>
		<a href={href(c)} aria-current={current ? 'page' : undefined} title="{c.title || 'New chat'} · {ago(c.updatedAt, now)}" onclick={() => onnavigate?.()}>
			<span class="title">{c.title || 'New chat'}</span>
			{#if c.running}<span class="running" role="img" aria-label="answering"></span>{/if}
		</a>
		{#if !c.running && c.title}
			<button class="delete" aria-label={`Delete "${c.title}"`} onclick={() => (deleting = c)}><Icon name="trash" size={14} /></button>
		{/if}
	</li>
{/snippet}

<nav aria-label="Chats">
	<div class="top">
		<a class="new" href="/" onclick={() => onnavigate?.()} aria-keyshortcuts="Control+Shift+O">
			<Icon name="pencil" />New chat
		</a>
		<IconButton icon="search" label="Search chats" shortcut="Ctrl K" onclick={() => (layout.search = true)} />
	</div>

	{#if problem}
		<p class="problem" role="alert">
			<span>{problem}</span>
			<IconButton icon="x" label="Dismiss" tooltip={false} onclick={() => (problem = null)} />
		</p>
	{/if}

	<div class="lists">
		{#if chat.workspaces}
			<div class="head">
				<span>Projects</span>
				<IconButton icon="plus" label="Add project" disabled={!!chat.offline} onclick={() => ((adding = true), (addProblem = null))} />
			</div>
			{#each groups.groups as g (g.project.path)}
				{@const open = !folded[g.project.path]}
				<section aria-label={g.project.name}>
					<div class="project">
						<button class="fold" aria-expanded={open} title={g.project.path} onclick={() => (folded[g.project.path] = open)}>
							<Icon name="folder" size={14} />
							<span class="name">{g.project.name}</span>
						</button>
						<Menu label="Project actions for {g.project.name}" items={projectMenu(g.project)} />
					</div>
					{#if open}
						<ul class="nested">
							{#each g.chats as c (c.id)}{@render row(c)}{:else}<li class="none">No chats yet</li>{/each}
						</ul>
					{/if}
				</section>
			{/each}
			{#if groups.groups.length}<div class="head"><span>Chats</span></div>{/if}
		{/if}
		<ul>
			{#each groups.rest as c (c.id)}{@render row(c)}{:else}<li class="none">No chats yet</li>{/each}
		</ul>
	</div>
</nav>

<Dialog open={!!deleting} title="Delete chat?" width={400} onclose={() => (deleting = null)}>
	<p>“{deleting?.title}” and its plan and traces will be deleted. This cannot be undone.</p>
	{#snippet footer()}
		<Button onclick={() => (deleting = null)}>Cancel</Button>
		<Button variant="danger" onclick={remove}>Delete</Button>
	{/snippet}
</Dialog>

<Dialog open={!!removing} title="Remove project?" width={400} onclose={() => (removing = null)}>
	<p>“{removing?.name}” leaves the list and its chats are hidden until you add the folder again. No files are touched.</p>
	{#snippet footer()}
		<Button onclick={() => (removing = null)}>Cancel</Button>
		<Button variant="danger" onclick={removeProject}>Remove</Button>
	{/snippet}
</Dialog>

<Dialog bind:open={adding} title="Add project" description="A folder on the computer that runs Panic." width={460}>
	<form
		onsubmit={(e) => {
			e.preventDefault();
			if (typed.trim()) void addProject(typed.trim());
		}}
	>
		<!-- svelte-ignore a11y_autofocus -->
		<input bind:value={typed} autofocus placeholder="Folder path, e.g. D:\work\app" aria-label="Folder path" spellcheck="false" />
		<Button type="submit" variant="primary" disabled={!typed.trim() || picking}>Add</Button>
	</form>
	{#if picking}
		<p class="hint" role="status">
			A folder dialog is open on the computer that runs Panic.
			<button class="link" onclick={() => chat.cancelAddProject()}>Cancel</button>
		</p>
	{:else}
		<p class="hint"><button class="link" onclick={() => addProject()}>Choose a folder there instead…</button></p>
	{/if}
	{#if addProblem}<p class="error" role="alert">{addProblem}</p>{/if}
</Dialog>

<style>
	nav {
		display: flex;
		flex-direction: column;
		height: 100%;
		min-height: 0;
	}
	.top {
		display: flex;
		align-items: center;
		gap: var(--sp-1);
		height: var(--bar-h);
		padding: 0 var(--sp-2);
		flex: none;
	}
	.new {
		flex: 1;
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		height: 32px;
		padding: 0 var(--sp-2);
		border-radius: var(--radius);
		color: var(--text);
		text-decoration: none;
		font-weight: 500;
	}
	.new:hover {
		background: var(--surface-2);
	}
	.problem {
		display: flex;
		align-items: flex-start;
		gap: var(--sp-1);
		margin: 0 var(--sp-2) var(--sp-2);
		padding: var(--sp-1) var(--sp-1) var(--sp-1) var(--sp-2);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		color: var(--danger);
		font-size: var(--fs-1);
		overflow-wrap: anywhere;
	}
	.problem span {
		flex: 1;
		padding-top: var(--sp-1);
	}
	.lists {
		flex: 1;
		overflow-y: auto;
		padding: 0 var(--sp-2) var(--sp-4);
	}
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		height: 28px;
		margin-top: var(--sp-3);
		padding: 0 0 0 var(--sp-2);
		color: var(--muted);
		font-size: var(--fs-1);
		font-weight: 500;
	}
	ul {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	li {
		position: relative;
		display: flex;
		align-items: center;
		border-radius: var(--radius);
	}
	li:hover,
	li.current {
		background: var(--surface-2);
	}
	li a {
		flex: 1;
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		min-width: 0;
		height: 30px;
		padding: 0 var(--sp-2);
		color: var(--text-2);
		text-decoration: none;
		font-size: var(--fs-2);
	}
	li.current a {
		color: var(--text);
		font-weight: 500;
	}
	.title {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.running {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--accent);
		flex: none;
	}
	.delete {
		display: none;
		place-items: center;
		width: 24px;
		height: 24px;
		margin-right: var(--sp-1);
		border: 0;
		border-radius: var(--radius-sm);
		background: none;
		color: var(--muted);
	}
	li:hover .delete,
	.delete:focus-visible {
		display: grid;
	}
	.delete:hover {
		color: var(--danger);
	}
	.none {
		padding: var(--sp-1) var(--sp-2);
		color: var(--muted);
		font-size: var(--fs-1);
	}
	.project {
		display: flex;
		align-items: center;
		border-radius: var(--radius);
	}
	.project :global(.ui-menu-trigger) {
		visibility: hidden;
	}
	.project:hover :global(.ui-menu-trigger),
	.project :global(.ui-menu-trigger[data-state='open']),
	.project :global(.ui-menu-trigger:focus-visible) {
		visibility: visible;
	}
	.fold {
		flex: 1;
		display: flex;
		align-items: center;
		gap: var(--sp-2);
		min-width: 0;
		height: 30px;
		padding: 0 var(--sp-2);
		border: 0;
		border-radius: var(--radius);
		background: none;
		color: var(--text);
		font-size: var(--fs-2);
		font-weight: 500;
		text-align: left;
	}
	.fold:hover {
		background: var(--surface-2);
	}
	.name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.nested {
		margin-left: var(--sp-3);
	}
	p {
		margin: 0;
		color: var(--text-2);
	}
	form {
		display: flex;
		gap: var(--sp-2);
	}
	input {
		flex: 1;
		height: 32px;
		padding: 0 var(--sp-3);
		border: 1px solid var(--border-strong);
		border-radius: var(--radius);
		background: var(--bg);
		font-family: var(--mono);
		font-size: var(--fs-2);
	}
	.hint {
		margin-top: var(--sp-3);
		color: var(--muted);
		font-size: var(--fs-2);
	}
	.link {
		padding: 0;
		border: 0;
		background: none;
		color: var(--accent);
		font-size: inherit;
	}
	.error {
		margin-top: var(--sp-2);
		color: var(--danger);
		font-size: var(--fs-2);
	}
</style>
