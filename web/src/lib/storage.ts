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

/**
 * Everything the workspace remembers across reloads. localStorage is only an accelerant:
 * the server owns the conversations, and a selection always refetches from it.
 */
export interface WorkspaceStore {
	/** Conversation the dashboard follows; undefined = next send starts a new chat. */
	activeId?: string;
	/** Cached turns per conversation id, newest last (accelerant for replay + offline). */
	sessions: Record<string, Turn[]>;
}

const STORAGE_KEY = "panic-workspace:v3";
const SIDEBAR_KEY = "panic-sidebar-collapsed";
const SIDEBAR_WIDTH_KEY = "panic-sidebar-width";
const THEME_KEY = "panic-workspace-theme";

export type WorkspaceTheme = "light" | "dark";

export function loadTheme(): WorkspaceTheme | undefined {
	try {
		const raw = localStorage.getItem(THEME_KEY);
		return raw === "dark" ? "dark" : raw === "light" ? "light" : undefined;
	} catch {
		return undefined;
	}
}

export function saveTheme(theme: WorkspaceTheme): void {
	try {
		localStorage.setItem(THEME_KEY, theme);
	} catch {
		/* storage unavailable: the toggle still works within the page */
	}
}

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

function normalizeTurns(value: unknown): Turn[] {
	if (!Array.isArray(value)) return [];
	return value
		.filter(
			(turn): turn is Turn =>
				typeof turn === "object" &&
				turn !== null &&
				typeof (turn as Turn).id === "string" &&
				typeof (turn as Turn).task === "string"
		)
		.map((turn) => {
			const status: TurnStatus =
				turn.status === "ok" || turn.status === "error" ? turn.status : "running";
			return {
				...turn,
				traceId: typeof turn.traceId === "number" ? turn.traceId : null,
				pipeline: normalizePipeline(turn.pipeline),
				status,
				answer: typeof turn.answer === "string" ? turn.answer : "",
				at: typeof turn.at === "number" ? turn.at : Date.now(),
			};
		})
		.filter((turn) => turn.status !== "running");
}

export function loadWorkspace(): WorkspaceStore {
	try {
		const raw = localStorage.getItem(STORAGE_KEY);
		if (!raw) return { sessions: {} };
		const parsed: unknown = JSON.parse(raw);
		if (typeof parsed !== "object" || parsed === null) return { sessions: {} };
		const store = parsed as { activeId?: unknown; sessions?: unknown };
		const sessions: Record<string, Turn[]> = {};
		if (typeof store.sessions === "object" && store.sessions !== null) {
			for (const [id, turns] of Object.entries(store.sessions as Record<string, unknown>)) {
				const normalized = normalizeTurns(turns).slice(-MAX_TURNS);
				if (normalized.length > 0) sessions[id] = normalized;
			}
		}
		return {
			activeId: typeof store.activeId === "string" ? store.activeId : undefined,
			sessions,
		};
	} catch {
		return { sessions: {} };
	}
}

export function saveWorkspace(store: WorkspaceStore): void {
	try {
		const sessions: Record<string, Turn[]> = {};
		for (const [id, turns] of Object.entries(store.sessions)) {
			sessions[id] = turns.slice(-MAX_TURNS);
		}
		localStorage.setItem(
			STORAGE_KEY,
			JSON.stringify({ activeId: store.activeId, sessions })
		);
	} catch {
		/* storage unavailable: the server remains the source of truth */
	}
}

/** Sidebar preference; undefined = none saved yet (the window width decides). */
export function loadSidebarCollapsed(): boolean | undefined {
	try {
		const raw = localStorage.getItem(SIDEBAR_KEY);
		return raw === "collapsed" ? true : raw === "open" ? false : undefined;
	} catch {
		return undefined;
	}
}

export function saveSidebarCollapsed(collapsed: boolean): void {
	try {
		localStorage.setItem(SIDEBAR_KEY, collapsed ? "collapsed" : "open");
	} catch {
		/* storage unavailable: preference simply is not remembered */
	}
}

/** Sidebar width in px; undefined = none saved yet (the default wins). */
export function loadSidebarWidth(): number | undefined {
	try {
		const raw = localStorage.getItem(SIDEBAR_WIDTH_KEY);
		const width = raw === null ? Number.NaN : Number(raw);
		return Number.isFinite(width) && width >= MIN_SIDEBAR_WIDTH && width <= MAX_SIDEBAR_WIDTH
			? width
			: undefined;
	} catch {
		return undefined;
	}
}

export function saveSidebarWidth(width: number): void {
	try {
		localStorage.setItem(SIDEBAR_WIDTH_KEY, String(Math.round(width)));
	} catch {
		/* storage unavailable: the width simply is not remembered */
	}
}

export const MIN_SIDEBAR_WIDTH = 208;
export const MAX_SIDEBAR_WIDTH = 360;
export const DEFAULT_SIDEBAR_WIDTH = 256;
