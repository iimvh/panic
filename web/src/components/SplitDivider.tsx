import { useCallback, useRef, useState, type PointerEvent } from "react";

interface SplitDividerProps {
	width: number;
	min: number;
	max: number;
	onResize: (width: number) => void;
	/** Called when a drag ends, so the caller can persist the width. */
	onResizeEnd?: (width: number) => void;
}

export default function SplitDivider({ width, min, max, onResize, onResizeEnd }: SplitDividerProps) {
	const [dragging, setDragging] = useState(false);
	const latest = useRef(width);

	const handlePointerDown = useCallback(
		(event: PointerEvent<HTMLDivElement>) => {
			event.preventDefault();
			(event.currentTarget as HTMLDivElement).setPointerCapture(event.pointerId);
			latest.current = width;
			setDragging(true);
		},
		[width]
	);

	const handlePointerMove = useCallback(
		(event: PointerEvent<HTMLDivElement>) => {
			if (!dragging) return;
			const next = Math.min(max, Math.max(min, window.innerWidth - event.clientX));
			latest.current = next;
			onResize(next);
		},
		[dragging, max, min, onResize]
	);

	const stop = useCallback(
		(event: PointerEvent<HTMLDivElement>) => {
			if (!dragging) return;
			if ((event.currentTarget as HTMLDivElement).hasPointerCapture(event.pointerId)) {
				(event.currentTarget as HTMLDivElement).releasePointerCapture(event.pointerId);
			}
			setDragging(false);
			onResizeEnd?.(latest.current);
		},
		[dragging, onResizeEnd]
	);

	const handleKeyDown = useCallback(
		(event: React.KeyboardEvent<HTMLDivElement>) => {
			const step = event.shiftKey ? 48 : 16;
			let next: number | null = null;
			if (event.key === "ArrowLeft") next = Math.min(max, width + step);
			if (event.key === "ArrowRight") next = Math.max(min, width - step);
			if (next == null) return;
			event.preventDefault();
			onResize(next);
			onResizeEnd?.(next);
		},
		[max, min, onResize, onResizeEnd, width]
	);

	return (
		<div
			role="separator"
			aria-orientation="vertical"
			aria-label="Resize pipeline panel"
			aria-valuenow={Math.round(width)}
			aria-valuemin={min}
			aria-valuemax={max}
			tabIndex={0}
			onPointerDown={handlePointerDown}
			onPointerMove={handlePointerMove}
			onPointerUp={stop}
			onPointerCancel={stop}
			onKeyDown={handleKeyDown}
			className={`group relative hidden w-[9px] shrink-0 cursor-col-resize touch-none select-none min-[900px]:block ${
				dragging ? "bg-surface-alt" : "bg-transparent"
			}`}
		>
			<span
				className={`absolute inset-y-0 left-1/2 w-px -translate-x-1/2 transition-colors ${
					dragging ? "bg-white/40" : "bg-hairline group-hover:bg-hairline-strong"
				}`}
				aria-hidden="true"
			/>
			<span
				className={`absolute left-1/2 top-1/2 h-10 w-1.5 -translate-x-1/2 -translate-y-1/2 rounded-xs transition-colors ${
					dragging ? "bg-white/40" : "bg-hairline group-hover:bg-hairline-strong"
				}`}
				aria-hidden="true"
			/>
		</div>
	);
}
