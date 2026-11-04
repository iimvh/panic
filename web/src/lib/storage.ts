import type { PipelineEvent } from "./pipeline";

export type Role = "user" | "assistant" | "error";

export interface Message {
	id: string;
	role: Role;
	text: string;
	at: number;
	/** Pipeline of this turn (assistant/error messages only). Kept so older turns are not lost. */
	traceId?: number;
	pipeline?: PipelineEvent[];
}

export const MAX_MESSAGES = 200;

const STORAGE_KEY = "panic-chat:v2";
const CONVERSATION_KEY = "panic-conversation";

export function newId(): string {
	return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function normalizePipeline(value: unknown): PipelineEvent[] | undefined {
	if (!Array.isArray(value)) return undefined;
	const events = value.filter(
		(event): event is PipelineEvent =>
			typeof event === "object" &&
			event !== null &&
			typeof (event as PipelineEvent).id === "number" &&
			typeof (event as PipelineEvent).title === "string"
	);
	return events.length > 0 ? events : undefined;
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
				traceId: typeof m.traceId === "number" ? m.traceId : undefined,
				pipeline: normalizePipeline(m.pipeline),
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

export function loadConversationId(): string | undefined {
	try {
		return localStorage.getItem(CONVERSATION_KEY) ?? undefined;
	} catch {
		return undefined;
	}
}

export function saveConversationId(id: string | undefined): void {
	try {
		if (id) localStorage.setItem(CONVERSATION_KEY, id);
		else localStorage.removeItem(CONVERSATION_KEY);
	} catch {
		/* storage unavailable: ignore */
	}
}
