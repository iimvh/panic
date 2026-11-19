import { load, save } from './storage';

/** What is open around the conversation. Per browser where it is a preference (the sidebar), else per page. */
class Layout {
	sidebar = $state(load('panic.sidebar', (v) => v === 'open' || v === 'closed', 'open') === 'open');
	settings = $state(false);
	search = $state(false);

	toggleSidebar() {
		this.sidebar = !this.sidebar;
		save('panic.sidebar', this.sidebar ? 'open' : 'closed');
	}
}

export const layout = new Layout();
