/**
 * Per-browser preferences (sizes, collapsed panels). Storage can be missing or refuse (a private window, blocked
 * site data): reading then gives the fallback, and a choice lasts until the page closes.
 */
export function load(key: string, ok: (v: string) => boolean, fallback: string): string {
	try {
		const v = localStorage.getItem(key);
		return v !== null && ok(v) ? v : fallback;
	} catch {
		return fallback;
	}
}

export function save(key: string, value: string) {
	try {
		localStorage.setItem(key, value);
	} catch {
		// private window or blocked storage: the choice lasts until the page closes
	}
}
