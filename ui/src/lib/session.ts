const KEY = 'panic.session';

function newId(): string {
	const bytes = new Uint8Array(12);
	crypto.getRandomValues(bytes);
	return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** This browser's session id (the server's conversation id). Survives reloads when storage works. */
export function sessionId(): string {
	try {
		const saved = localStorage.getItem(KEY);
		if (saved && /^[A-Za-z0-9_-]{1,100}$/.test(saved)) return saved;
		const id = newId();
		localStorage.setItem(KEY, id);
		return id;
	} catch {
		return memory;
	}
}

const memory = newId();
