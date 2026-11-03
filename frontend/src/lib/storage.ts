export type Role = "user" | "assistant" | "error";

export interface Message {
	id: string;
	role: Role;
	text: string;
	at: number;
}

export const MAX_MESSAGES = 200;

const STORAGE_KEY = "meeting-room-chat:v2";

export function newId(): string {
	return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

export function loadMessages(): Message[] {
	try {
		const raw = localStorage.getItem(STORAGE_KEY);
		if (!raw) return [];
		const parsed: unknown = JSON.parse(raw);
		if (!Array.isArray(parsed)) return [];
		return parsed
			.filter(
				(m): m is Message =>
					typeof m === "object" &&
					m !== null &&
					typeof (m as Message).text === "string" &&
					["user", "assistant", "error"].includes((m as Message).role)
			)
			.map((m) => ({
				id: m.id ?? newId(),
				role: m.role,
				text: m.text,
				at: typeof m.at === "number" ? m.at : Date.now(),
			}))
			.slice(-MAX_MESSAGES);
	} catch {
		return [];
	}
}

export function saveMessages(messages: Message[]): void {
	try {
		localStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-MAX_MESSAGES)));
	} catch {
		/* storage unavailable: ignore */
	}
}
