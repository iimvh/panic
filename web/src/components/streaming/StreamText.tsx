import { useEffect, useRef, useState } from "react";
import type { TextStream } from "../../hooks/useTextStream";

/** Skeleton rows imitate the paragraph about to load; widths read as text lines. */
const SKELETON_WIDTHS = ["96%", "100%", "88%", "56%"];
const SKELETON_FADE_MS = 200;
/** How close to the bottom the viewport must stay to keep following the stream. */
const STICK_THRESHOLD_PX = 24;

type SkeletonState = "off" | "solid" | "fading";

/** Text skeleton blocks: rounded bars with an infinite diagonal shimmer sweep. */
function StreamSkeleton({ fading }: { fading: boolean }) {
	return (
		<div
			aria-hidden="true"
			className={`absolute inset-0 space-y-[9px] px-0.5 py-0.5 transition-opacity duration-200 ${
				fading ? "opacity-0" : "opacity-100"
			}`}
		>
			{SKELETON_WIDTHS.map((width, index) => (
				<div key={index} style={{ width }} className="stream-shimmer h-3 animate-shimmer rounded-[3px] bg-edge" />
			))}
		</div>
	);
}

/**
 * Streaming text surface: shimmer skeleton while waiting, typewriter body fed by a TextStream,
 * and an inline blinking cursor that retires when the stream ends. The viewport follows the
 * newest character but yields as soon as the reader scrolls away from the bottom.
 */
export default function StreamText({
	stream,
	placeholder = "answer pending — run a task to see the result",
}: {
	stream: TextStream;
	placeholder?: string;
}) {
	const scrollerRef = useRef<HTMLDivElement | null>(null);
	const stickRef = useRef(true);
	const [skeleton, setSkeleton] = useState<SkeletonState>("off");

	const { status, text } = stream;

	// Skeleton lifecycle: solid while waiting for the first token, fading as soon as real
	// text arrives (or the stream ends empty), then dropped from the DOM after the fade.
	useEffect(() => {
		if (status === "loading") setSkeleton((s) => (s === "off" ? "solid" : s));
		else if (status === "streaming" && text.length === 0) return;
		else setSkeleton((s) => (s === "solid" ? "fading" : s));
	}, [status, text.length]);

	useEffect(() => {
		if (skeleton !== "fading") return;
		const timer = window.setTimeout(() => setSkeleton("off"), SKELETON_FADE_MS);
		return () => window.clearTimeout(timer);
	}, [skeleton]);

	// Smart auto-scroll: pin the newest character + cursor to the bottom while the reader
	// stays near it; scrolling up pauses the pinning until they return.
	useEffect(() => {
		if (!stickRef.current) return;
		scrollerRef.current?.scrollTo({ top: scrollerRef.current.scrollHeight });
	}, [text, skeleton]);

	const onScroll = () => {
		const el = scrollerRef.current;
		if (!el) return;
		stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD_PX;
	};

	const showCursor = text.length > 0 && status === "streaming";

	return (
		<div
			ref={scrollerRef}
			onScroll={onScroll}
			aria-live="polite"
			aria-busy={status === "streaming"}
			className="scrollbar-thin relative min-h-0 flex-1 overflow-y-auto overscroll-contain px-2.5 py-2"
		>
			{status === "idle" && text.length === 0 ? (
				<p className="px-5 py-8 text-center font-mono text-[11.5px] text-fg-faint">{placeholder}</p>
			) : text.length > 0 ? (
				<p className="whitespace-pre-wrap break-words font-mono text-[11.5px] leading-[1.65] text-fg">
					{text}
					{showCursor && (
						<span
							aria-hidden="true"
							className="ml-px inline-block h-[1.05em] w-[0.55ch] translate-y-[0.12em] animate-cursor-blink rounded-[1px] bg-blue align-[-0.1em]"
						/>
					)}
				</p>
			) : null}
			{skeleton !== "off" && <StreamSkeleton fading={skeleton === "fading"} />}
		</div>
	);
}
