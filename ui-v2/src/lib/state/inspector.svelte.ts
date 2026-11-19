import { goto } from '$app/navigation';
import { page } from '$app/state';
import { chat } from './chat.svelte';

/**
 * The inspector lives in the URL, so a link, Back and a reload keep it:
 * `?panel=overview`, `?panel=step&step=<tool call id>`, `?panel=trace&trace=<trace id>`.
 */
export type Panel = 'overview' | 'step' | 'trace';
const PANELS: Panel[] = ['overview', 'step', 'trace'];

/**
 * The page's URL on the conversation's own path. Built from the chat, not from the address bar: as a new chat's
 * first answer starts, its move to /c/<id> may still be loading, and a navigation started now would cancel it and
 * leave the chat on `/` (a reload would then show a new, empty chat).
 */
function here(): URL {
	const url = new URL(page.url);
	url.pathname = chat.fresh ? '/' : `/c/${chat.session}`;
	return url;
}

export const inspector = {
	get panel(): Panel | null {
		const p = page.url.searchParams.get('panel');
		return PANELS.includes(p as Panel) ? (p as Panel) : null;
	},
	get step(): string | null {
		return page.url.searchParams.get('step');
	},
	get trace(): string | null {
		return page.url.searchParams.get('trace');
	},
	/**
	 * Opens `panel`. A step or trace given is shown; one given as '' is cleared; one not given is kept (switching
	 * tabs keeps both). Moving within the open inspector replaces the history entry, opening it adds one.
	 */
	open(panel: Panel, params: { step?: string; trace?: string } = {}) {
		const url = here();
		url.searchParams.set('panel', panel);
		for (const [k, v] of Object.entries(params)) {
			if (v) url.searchParams.set(k, v);
			else url.searchParams.delete(k);
		}
		return goto(url, { keepFocus: true, noScroll: true, replaceState: inspector.panel !== null });
	},
	close() {
		const url = here();
		for (const k of ['panel', 'step', 'trace']) url.searchParams.delete(k);
		return goto(url, { keepFocus: true, noScroll: true });
	},
	toggle() {
		return inspector.panel ? inspector.close() : inspector.open('overview');
	}
};
