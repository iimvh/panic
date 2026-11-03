import { useCallback, useEffect, useRef, useState } from "react";
import { normalizePipeline, type PipelineDone, type PipelineEvent } from "../lib/pipeline";

export type StreamState = "idle" | "connecting" | "live" | "closed";

interface UsePipelineStream {
	state: StreamState;
	events: PipelineEvent[];
	done: PipelineDone | null;
	connect: (traceId: number) => void;
	/** Merges the final result from POST /chat (used when SSE misses frames or is unavailable). */
	merge: (events: PipelineEvent[], done?: PipelineDone | null) => void;
	reset: () => void;
}

function mergeEvents(
	current: PipelineEvent[],
	incoming: PipelineEvent[],
	overwrite: boolean
): PipelineEvent[] {
	const result = overwrite ? [] : current.slice();
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
 * Opens SSE to /api/agent/pipeline/stream?traceId=… to watch agents call each other in realtime.
 * `done`/`error` is the final frame the server emits before closing the stream.
 */
export function usePipelineStream(): UsePipelineStream {
	const [state, setState] = useState<StreamState>("idle");
	const [events, setEvents] = useState<PipelineEvent[]>([]);
	const [done, setDone] = useState<PipelineDone | null>(null);
	const sourceRef = useRef<EventSource | null>(null);

	const close = useCallback(() => {
		sourceRef.current?.close();
		sourceRef.current = null;
	}, []);

	const reset = useCallback(() => {
		close();
		setEvents([]);
		setDone(null);
		setState("idle");
	}, [close]);

	const connect = useCallback(
		(traceId: number) => {
			close();
			setEvents([]);
			setDone(null);
			setState("connecting");

			const source = new EventSource(`/api/agent/pipeline/stream?traceId=${traceId}`);
			sourceRef.current = source;

			source.addEventListener("ready", () => setState("live"));

			source.addEventListener("pipeline", (message) => {
				const event = JSON.parse((message as MessageEvent<string>).data) as PipelineEvent;
				setEvents((current) => mergeEvents(current, [event], false));
			});

			const finish = (message: MessageEvent<string>) => {
				const payload = JSON.parse(message.data) as {
					status: "ok" | "error";
					error?: string;
					durationMs: number;
					events?: unknown;
				};
				setEvents((current) => mergeEvents(current, normalizePipeline(payload.events), false));
				setDone({ status: payload.status, error: payload.error, durationMs: payload.durationMs });
				setState("closed");
				source.close();
			};

			source.addEventListener("done", finish as EventListener);
			source.addEventListener("error", (message) => {
				const raw = (message as MessageEvent<string>).data;
				if (raw) {
					finish(message as MessageEvent<string>);
					return;
				}
				// EventSource reports an error when the server closes the stream: not a business error.
				setState((current) => (current === "live" ? "closed" : current));
			});
		},
		[close]
	);

	const merge = useCallback((incoming: PipelineEvent[], summary?: PipelineDone | null) => {
		if (incoming.length === 0 && !summary) return;
		setEvents((current) => mergeEvents(current, incoming, false));
		if (summary) setDone(summary);
	}, []);

	useEffect(() => close, [close]);

	return { state, events, done, connect, merge, reset };
}
