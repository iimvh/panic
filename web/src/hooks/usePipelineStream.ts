import { useCallback, useState } from "react";
import type { PipelineDone, PipelineEvent } from "../lib/pipeline";

export type StreamState = "idle" | "connecting" | "live" | "closed";

interface UsePipelineStream {
	state: StreamState;
	events: PipelineEvent[];
	done: PipelineDone | null;
	/** Marks the start of a request (frames will arrive via `push`). */
	begin: () => void;
	/** Applies one realtime pipeline event pushed by the server. */
	push: (event: PipelineEvent) => void;
	/** Merges the final result (fallback if any realtime frame was missed). */
	merge: (events: PipelineEvent[], done?: PipelineDone | null) => void;
	fail: (error: string) => void;
	reset: () => void;
}

function mergeEvents(current: PipelineEvent[], incoming: PipelineEvent[]): PipelineEvent[] {
	const result = current.slice();
	for (const event of incoming) {
		const index = result.findIndex((item) => item.id === event.id);
		if (index === -1) {
			result.push(event);
			continue;
		}
		const existing = result[index];
		if (existing.status === "running" && event.status !== "running") {
			result[index] = event;
		}
	}
	return result.sort((a, b) => a.id - b.id);
}

/**
 * Accumulates the pipeline of the current chat turn. Events are pushed by the chat stream itself
 * (`askAgent` → `onPipeline`), so there is no second connection and no lost head-of-stream frames.
 */
export function usePipelineStream(): UsePipelineStream {
	const [state, setState] = useState<StreamState>("idle");
	const [events, setEvents] = useState<PipelineEvent[]>([]);
	const [done, setDone] = useState<PipelineDone | null>(null);

	const reset = useCallback(() => {
		setEvents([]);
		setDone(null);
		setState("idle");
	}, []);

	const begin = useCallback(() => {
		setEvents([]);
		setDone(null);
		setState("connecting");
	}, []);

	const push = useCallback((event: PipelineEvent) => {
		setState((current) => (current === "connecting" ? "live" : current));
		setEvents((current) => mergeEvents(current, [event]));
	}, []);

	const merge = useCallback((incoming: PipelineEvent[], summary?: PipelineDone | null) => {
		if (incoming.length === 0 && !summary) return;
		setEvents((current) => mergeEvents(current, incoming));
		if (summary) {
			setDone(summary);
			setState("closed");
		}
	}, []);

	const fail = useCallback((error: string) => {
		setDone({ status: "error", error, durationMs: 0 });
		setState("closed");
	}, []);

	return { state, events, done, begin, push, merge, fail, reset };
}
