import { normalizePipeline, type PipelineEvent } from "./pipeline";

/** Session row in the sidebar, straight from GET /api/conversations. */
export interface ConversationSummary {
	id: string;
	title: string | null;
	lastActivityAt: number;
}

/** One replayed turn: exactly the shape of GET /api/conversations/{id}. Its turns array. */
export interface ReplayTurn {
	task: string;
	answer: string;
	status: "ok" | "error";
	at: number;
	pipeline: PipelineEvent[];
}

export interface ConversationReplay {
	id: string;
	title: string | null;
	/** AG-UI conversation records (t<n>-u / t<n>-a pairs) for the assistant-ui Thread. */
	messages: AgUiReplayMessage[];
	/** Recorded turns for the ExecutionLedger; `turns` travels beside `messages`. */
	turns: ReplayTurn[];
}

/** One replayed record: HTTP shape of the backend's AgUiReplayMessage. */
export interface AgUiReplayMessage {
	id: string;
	role: "user" | "assistant";
	content: string;
}

const CONVERSATIONS_ENDPOINT = "/api/conversations";

async function readError(response: Response, fallback: string): Promise<Error> {
	try {
		const data = (await response.json()) as { error?: unknown };
		if (typeof data.error === "string" && data.error) return new Error(data.error);
	} catch {
		/* keep the default */
	}
	return new Error(fallback);
}

/** POST /api/conversations — new empty session ("＋ new chat" needs no id until the first send). */
export async function createConversation(): Promise<string> {
	const response = await fetch(CONVERSATIONS_ENDPOINT, {
		method: "POST",
		headers: { Accept: "application/json" },
	});
	if (!response.ok) throw await readError(response, `The server returned error ${response.status}.`);
	const json = (await response.json()) as { id?: unknown };
	if (typeof json.id !== "string" || !json.id) throw new Error("The server created a session without an id.");
	return json.id;
}

/** GET /api/conversations — all sessions, newest activity first. */
export async function listConversations(): Promise<ConversationSummary[]> {
	const response = await fetch(CONVERSATIONS_ENDPOINT, { headers: { Accept: "application/json" } });
	if (!response.ok) throw await readError(response, `The server returned error ${response.status}.`);
	const json = (await response.json()) as unknown;
	if (!Array.isArray(json)) return [];
	return json
		.filter((row): row is Record<string, unknown> => typeof row === "object" && row !== null)
		.map((row) => ({
			id: String(row.id ?? ""),
			title: typeof row.title === "string" ? row.title : null,
			lastActivityAt: typeof row.lastActivityAt === "number" ? row.lastActivityAt : 0,
		}))
		.filter((row) => row.id !== "");
}

/** GET /api/conversations/{id} — whole-session replay; null when the session no longer exists. */
export async function fetchConversation(id: string): Promise<ConversationReplay | null> {
	const response = await fetch(`${CONVERSATIONS_ENDPOINT}/${id}`, {
		headers: { Accept: "application/json" },
	});
	if (response.status === 404) return null;
	if (!response.ok) throw await readError(response, `The server returned error ${response.status}.`);
	const json = (await response.json()) as Record<string, unknown>;
	return {
		id: String(json.id ?? id),
		title: typeof json.title === "string" ? json.title : null,
		messages: replayMessages(json.messages),
		turns: replayTurns(json.turns),
	};
}

/** PATCH /api/conversations/{id} — inline rename from the sidebar. */
export async function renameConversation(id: string, title: string): Promise<ConversationSummary> {
	const response = await fetch(`${CONVERSATIONS_ENDPOINT}/${id}`, {
		method: "PATCH",
		headers: { "Content-Type": "application/json", Accept: "application/json" },
		body: JSON.stringify({ title }),
	});
	if (!response.ok) throw await readError(response, `The server returned error ${response.status}.`);
	const json = (await response.json()) as Record<string, unknown>;
	return {
		id: String(json.id ?? id),
		title: typeof json.title === "string" ? json.title : null,
		lastActivityAt: typeof json.lastActivityAt === "number" ? json.lastActivityAt : 0,
	};
}

/** DELETE /api/conversations/{id} — removes the session and its record for good. */
export async function deleteConversation(id: string): Promise<void> {
	const response = await fetch(`${CONVERSATIONS_ENDPOINT}/${id}`, { method: "DELETE" });
	if (!response.ok) throw await readError(response, `The server returned error ${response.status}.`);
}

function replayTurns(value: unknown): ReplayTurn[] {
	if (!Array.isArray(value)) return [];
	return value
		.filter((turn): turn is Record<string, unknown> => typeof turn === "object" && turn !== null)
		.map((turn) => {
			const status: ReplayTurn["status"] = turn.status === "error" ? "error" : "ok";
			return {
				task: typeof turn.task === "string" ? turn.task : "",
				answer: typeof turn.answer === "string" ? turn.answer : "",
				status,
				at: typeof turn.at === "number" ? turn.at : 0,
				pipeline: normalizePipeline(turn.pipeline),
			};
		})
		.filter((turn) => turn.task !== "");
}

function replayMessages(value: unknown): AgUiReplayMessage[] {
	if (!Array.isArray(value)) return [];
	return value
		.filter((message): message is Record<string, unknown> => typeof message === "object" && message !== null)
		.map((message) => ({
			id: String(message.id ?? ""),
			role: message.role === "assistant" ? ("assistant" as const) : ("user" as const),
			content: typeof message.content === "string" ? message.content : "",
		}))
		.filter((message) => message.id !== "");
}
