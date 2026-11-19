/** Conversation ids: the server's session ids, also the `/c/[id]` route parameter. */
const SAFE = /^[A-Za-z0-9_-]{1,100}$/;

export function newId(): string {
	const bytes = new Uint8Array(12);
	crypto.getRandomValues(bytes);
	return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** True for an id the server accepts (a route parameter is checked before it is used). */
export const validId = (id: string) => SAFE.test(id);
