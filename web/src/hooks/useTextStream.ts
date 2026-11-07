import { useCallback, useEffect, useRef, useState } from "react";

export type TextStreamStatus = "idle" | "loading" | "streaming" | "done";

export interface TextStream {
	status: TextStreamStatus;
	/** Characters revealed so far — the typewriter may trail behind received tokens. */
	text: string;
	/** Received characters not yet revealed. */
	pending: number;
	/** Marks the start of a request: skeleton phase until the first token arrives. */
	begin: () => void;
	/** Appends one streamed chunk incrementally, without rebuilding the message. */
	append: (chunk: string) => void;
	/** Closes the stream; the buffered tail finishes typing, then the cursor retires. */
	end: () => void;
	/** Drops the stream back to idle. */
	reset: () => void;
}

/** Share of the backlog revealed per frame: token-paced typing that catches up on bursts. */
const CATCHUP_RATIO = 0.14;
/** Once the sender closed, drain the tail faster so the message lands promptly. */
const CLOSED_DRAIN_BOOST = 3;

function prefersReducedMotion(): boolean {
	return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * Headless stream engine for streaming text UI. Chunks land in a buffer and a requestAnimationFrame
 * pump reveals them character-by-character, so the reveal rate follows the real token arrival speed
 * (a trickle types gently, a burst catches up) instead of snapping whole chunks onto the screen.
 */
export function useTextStream(): TextStream {
	const [status, setStatus] = useState<TextStreamStatus>("idle");
	const [text, setText] = useState("");
	const [pending, setPending] = useState(0);

	const statusRef = useRef(status);
	statusRef.current = status;
	const textRef = useRef("");
	const bufferRef = useRef("");
	const endedRef = useRef(false);

	// Reveal pump: drains the buffer while streaming and retires once the sender closed
	// and every buffered character reached the screen.
	useEffect(() => {
		if (status !== "streaming") return;
		let active = true;
		let frame = 0;
		const tick = () => {
			if (!active) return;
			const buffer = bufferRef.current;
			if (buffer.length > 0) {
				const ratio = endedRef.current ? CATCHUP_RATIO * CLOSED_DRAIN_BOOST : CATCHUP_RATIO;
				const take = Math.max(1, Math.round(buffer.length * ratio));
				textRef.current += buffer.slice(0, take);
				bufferRef.current = buffer.slice(take);
				setText(textRef.current);
				setPending(bufferRef.current.length);
			} else if (endedRef.current) {
				active = false;
				setPending(0);
				setStatus("done");
				return;
			}
			frame = requestAnimationFrame(tick);
		};
		frame = requestAnimationFrame(tick);
		return () => {
			active = false;
			cancelAnimationFrame(frame);
		};
	}, [status]);

	const begin = useCallback(() => {
		textRef.current = "";
		bufferRef.current = "";
		endedRef.current = false;
		setText("");
		setPending(0);
		setStatus("loading");
	}, []);

	const append = useCallback((chunk: string) => {
		if (!chunk || statusRef.current === "done") return;
		if (prefersReducedMotion()) {
			textRef.current += chunk;
			setText(textRef.current);
		} else {
			bufferRef.current += chunk;
			setPending(bufferRef.current.length);
		}
		setStatus((current) => (current === "done" ? current : "streaming"));
	}, []);

	const end = useCallback(() => {
		if (statusRef.current === "idle") return;
		endedRef.current = true;
		// A stream that closed before producing anything jumps straight to done (skeleton lifts).
		if (statusRef.current === "loading") setStatus("done");
	}, []);

	const reset = useCallback(() => {
		textRef.current = "";
		bufferRef.current = "";
		endedRef.current = false;
		setText("");
		setPending(0);
		setStatus("idle");
	}, []);

	return { status, text, pending, begin, append, end, reset };
}
