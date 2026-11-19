/**
 * The colour theme: the system's (auto), or light or dark as picked, kept in this browser. It is applied as
 * `data-theme` on <html>, which app.css reads; app.html applies the kept choice before the page shows, so it never
 * flashes the other theme.
 */
export type ThemePref = 'auto' | 'light' | 'dark';

const KEY = 'panic.theme';
const ORDER: ThemePref[] = ['auto', 'light', 'dark'];

class Theme {
	pref = $state<ThemePref>(load());

	/** Auto → light → dark → auto. */
	cycle() {
		this.set(ORDER[(ORDER.indexOf(this.pref) + 1) % ORDER.length]);
	}

	set(pref: ThemePref) {
		this.pref = pref;
		apply(pref);
		try {
			if (pref === 'auto') localStorage.removeItem(KEY);
			else localStorage.setItem(KEY, pref);
		} catch {
			// blocked storage: the choice lasts until the page closes
		}
	}
}

function load(): ThemePref {
	try {
		const v = localStorage.getItem(KEY);
		return v === 'light' || v === 'dark' ? v : 'auto';
	} catch {
		return 'auto';
	}
}

function apply(pref: ThemePref) {
	if (typeof document === 'undefined') return;
	if (pref === 'auto') delete document.documentElement.dataset.theme;
	else document.documentElement.dataset.theme = pref;
}

export const theme = new Theme();
