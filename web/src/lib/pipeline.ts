export type PipelinePhase = "trace" | "llm" | "tool" | "parse" | "error" | "message";
export type PipelineStatus = "running" | "ok" | "error";

/** CUSTOM event name on the AG-UI run stream; value = verbatim PipelineEvent JSON. */
export const PIPELINE_EVENT_NAME = "panic.pipeline";

export interface PipelineEvent {
	id: number;
	traceId: number;
	phase: PipelinePhase;
	actor: string;
	status: PipelineStatus;
	title: string;
	detail: string | null;
	startedAt: number;
	durationMs: number | null;
	parentId: number | null;
	agentId?: string | null;
	depth?: number | null;
	/** Conversation role for the trajectory view (null/undefined = derived from the phase). */
	role?: string | null;
}

export interface PipelineDone {
	status: "ok" | "error";
	error?: string;
	durationMs: number;
}

export function normalizePipeline(value: unknown): PipelineEvent[] {
	if (!Array.isArray(value)) return [];
	return value.filter(
		(event): event is PipelineEvent =>
			typeof event === "object" &&
			event !== null &&
			typeof (event as PipelineEvent).id === "number" &&
			typeof (event as PipelineEvent).title === "string"
	);
}

/** Narrows one streamed CUSTOM value into a PipelineEvent, or drops the junk frame. */
export function normalizePipelineEvent(value: unknown): PipelineEvent | null {
	if (typeof value !== "object" || value === null) return null;
	return normalizePipeline([value])[0] ?? null;
}


export interface TreeNode {
	event: PipelineEvent;
	children: TreeNode[];
}

/**
 * The backend records each tool call twice with the same id (2nd = finished, parentId = that id).
 * Last write wins, and parentId === id counts as root so the tree does not nest into itself.
 */
export function buildTree(events: PipelineEvent[]): TreeNode[] {
	const byId = new Map<number, PipelineEvent>();
	for (const event of events) {
		const existing = byId.get(event.id);
		if (!existing || (existing.status === "running" && event.status !== "running")) {
			byId.set(event.id, event);
		}
	}

	const nodes = new Map<number, TreeNode>();
	for (const event of byId.values()) {
		nodes.set(event.id, { event, children: [] });
	}

	const roots: TreeNode[] = [];
	for (const node of nodes.values()) {
		const parentId = node.event.parentId;
		const parent = parentId != null && parentId !== node.event.id ? nodes.get(parentId) : undefined;
		if (parent) {
			parent.children.push(node);
		} else {
			roots.push(node);
		}
	}

	const sort = (list: TreeNode[]) => {
		list.sort((a, b) => a.event.id - b.event.id);
		list.forEach((node) => sort(node.children));
	};
	sort(roots);
	return roots;
}

/** Number of running steps — used for the "live" badge. */
export function countRunning(events: PipelineEvent[]): number {
	return events.filter((event) => event.status === "running").length;
}
