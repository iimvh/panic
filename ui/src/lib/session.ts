const KEY = 'panic.session';
const SAFE = /^[A-Za-z0-9_-]{1,100}$/;

export function newId(): string {
	const bytes = new Uint8Array(12);
	crypto.getRandomValues(bytes);
	return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** This browser's current session id (the server's conversation id). Survives reloads when storage works. */
export function sessionId(): string {
	try {
		const saved = localStorage.getItem(KEY);
		if (saved && SAFE.test(saved)) return saved;
		const id = newId();
		localStorage.setItem(KEY, id);
		return id;
	} catch {
		return memory;
	}
}

/** Makes `id` the current session (another chat opened), for this page and the next load. */
export function setSessionId(id: string): string {
	if (!SAFE.test(id)) throw new Error(`bad session id: ${id}`);
	memory = id;
	try {
		localStorage.setItem(KEY, id);
	} catch {
		// storage blocked: the id lives as long as the page
	}
	return id;
}

/** A fresh session (a new chat), made current. */
export function newSessionId(): string {
	return setSessionId(newId());
}

let memory = newId();
