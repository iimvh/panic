import type { PipelineEvent } from "./pipeline";

export type TurnStatus = "running" | "ok" | "error";

/** One task run in the workspace: the user task plus its recorded trace. */
export interface Turn {
	id: string;
	task: string;
	traceId: number | null;
	pipeline: PipelineEvent[];
	status: TurnStatus;
	/** Final assistant answer once the turn closes. */
	answer: string;
	at: number;
}

export const MAX_TURNS = 20;

const STORAGE_KEY = "panic-workspace:v2";
const CONVERSATION_KEY = "panic-conversation";

export function newId(): string {
	return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function normalizePipeline(value: unknown): PipelineEvent[] {
	if (!Array.isArray(value)) return [];
	return value.filter(
		(event): event is PipelineEvent =>
			typeof event === "object" &&
			event !== null &&
			typeof (event as PipelineEvent).id === "number" &&
			typeof (event as PipelineEvent).title === "string"
	);
}

export function loadTurns(): Turn[] {
	try {
		const raw = localStorage.getItem(STORAGE_KEY);
		if (!raw) return [];
		const parsed: unknown = JSON.parse(raw);
		if (!Array.isArray(parsed)) return [];
		const turns: Turn[] = parsed
			.filter(
				(turn): turn is Turn =>
					typeof turn === "object" &&
					turn !== null &&
					typeof (turn as Turn).id === "string" &&
					typeof (turn as Turn).task === "string"
			)
			.map((turn) => ({
				...turn,
				traceId: typeof turn.traceId === "number" ? turn.traceId : null,
				pipeline: normalizePipeline(turn.pipeline),
				status: turn.status === "ok" || turn.status === "error" ? turn.status : "running",
				answer: typeof turn.answer === "string" ? turn.answer : "",
				at: typeof turn.at === "number" ? turn.at : Date.now(),
			}));
		return turns
			.filter((turn) => turn.status !== "running")
			.slice(-MAX_TURNS);
	} catch {
		return [];
	}
}

export function saveTurns(turns: Turn[]): void {
	try {
		localStorage.setItem(STORAGE_KEY, JSON.stringify(turns.slice(-MAX_TURNS)));
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
