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
