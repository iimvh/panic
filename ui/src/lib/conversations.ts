/** A stored conversation, as `GET /api/conversations` lists it. */
export type ConversationInfo = { id: string; title: string; updatedAt: number };

/** "just now", "5m", "3h", "yesterday", "4d", then the date: how long ago `at` was, at `now` (both ms). */
export function ago(at: number, now: number): string {
	const s = Math.max(0, Math.floor((now - at) / 1000));
	if (s < 60) return 'just now';
	if (s < 3600) return `${Math.floor(s / 60)}m`;
	if (s < 86_400) return `${Math.floor(s / 3600)}h`;
	const days = Math.floor(s / 86_400);
	if (days === 1) return 'yesterday';
	if (days < 7) return `${days}d`;
	return new Date(at).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** The list once `id` is gone (deleted), keeping the order. */
export function without(list: ConversationInfo[], id: string): ConversationInfo[] {
	return list.filter((c) => c.id !== id);
}

/** A project (workspace) as `GET /api/workspaces` lists it: its chats are the conversations that work in it. */
export type Project = { path: string; name: string; conversations: string[] };
/** `hidden`: chats of removed projects, which the list does not show. */
export type Workspaces = { projects: Project[]; hidden: string[] };

export type Group = { project: Project; chats: ConversationInfo[] };

/**
 * The chat list as the sidebar shows it: one group per project (its chats, newest first) and the chats of no
 * project. Chats of removed projects are left out. The chat being used (`current`) is shown in its project
 * even before its first message makes it a stored conversation.
 */
export function group(
	chats: ConversationInfo[],
	workspaces: Workspaces | null,
	current: string,
	now: number
): { groups: Group[]; rest: ConversationInfo[] } {
	if (!workspaces) return { groups: [], rest: chats };
	const inProject = new Set(workspaces.projects.flatMap((p) => p.conversations));
	const hidden = new Set(workspaces.hidden);
	const groups = workspaces.projects.map((project) => {
		const own = new Set(project.conversations);
		const list = chats.filter((c) => own.has(c.id));
		if (own.has(current) && !list.some((c) => c.id === current)) {
			list.unshift({ id: current, title: '', updatedAt: now });
		}
		return { project, chats: list };
	});
	return { groups, rest: chats.filter((c) => !inProject.has(c.id) && !hidden.has(c.id)) };
}
