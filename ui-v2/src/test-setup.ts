import { vi } from 'vitest';

/**
 * SvelteKit's runtime modules for component tests: navigation is recorded, the page is a URL tests can set
 * (`setUrl`), as the inspector reads it.
 */
const state = vi.hoisted(() => ({ page: { url: new URL('http://panic.test/'), params: {} as Record<string, string> } }));

vi.mock('$app/state', () => ({ page: state.page }));
vi.mock('$app/navigation', () => ({
	goto: vi.fn(async (to: string | URL) => {
		state.page.url = new URL(to, state.page.url);
	})
}));

export function setUrl(url: string) {
	state.page.url = new URL(url, 'http://panic.test/');
}
