import type { ReactNode } from "react";
import type { Tint, WorkStatus } from "../../lib/dashboard";
import { dotClass } from "../../lib/dashboard";

/** Bordered dark card with a micro-label header — the shell of every workspace panel. */
export function Panel({
	title,
	right,
	children,
	className = "",
	bodyClassName = "",
}: {
	title: string;
	right?: ReactNode;
	children: ReactNode;
	className?: string;
	bodyClassName?: string;
}) {
	return (
		<section className={`flex min-h-0 flex-col overflow-hidden border border-edge bg-panel ${className}`}>
			<header className="flex shrink-0 items-center gap-2 border-b border-edge bg-panel-head px-3 py-2">
				<span className="micro-label text-fg-dim">{title}</span>
				{right && <span className="ml-auto flex items-center gap-2">{right}</span>}
			</header>
			<div className={`min-h-0 flex-1 ${bodyClassName}`}>{children}</div>
		</section>
	);
}

/** Status dot; a "running" status pulses. */
export function Dot({ status, size = "h-[7px] w-[7px]" }: { status: WorkStatus; size?: string }) {
	return (
		<span
			aria-hidden="true"
			className={`inline-block shrink-0 rounded-full ${size} ${dotClass(status)} ${
				status === "running" ? "animate-pulse-soft" : ""
			}`}
		/>
	);
}

/** Text tint for a pipeline actor phase. */
export const tintText: Record<Tint, string> = {
	amber: "text-amber",
	blue: "text-blue",
	purple: "text-purple",
	green: "text-green",
	cyan: "text-cyan",
	red: "text-red",
	dim: "text-fg-dim",
};

/** Fill tint for the same phases (dots, bars). */
export const tintFill: Record<Exclude<Tint, "dim">, string> = {
	amber: "bg-amber",
	blue: "bg-blue",
	purple: "bg-purple",
	green: "bg-green",
	cyan: "bg-cyan",
	red: "bg-red",
};

/** Row of small blocks: filled = done steps, dim = the remaining frame of the bar. */
export function SegmentBar({
	done,
	total = 10,
	tint = "blue",
}: {
	done: number;
	total?: number;
	tint?: Exclude<Tint, "dim">;
}) {
	const cells = Math.max(total, done);
	return (
		<span className="flex items-center gap-[3px]" aria-label={`${done} of ${cells} steps done`}>
			{Array.from({ length: cells }, (_, index) => (
				<span key={index} className={`h-[6px] w-[6px] ${index < done ? tintFill[tint] : "bg-edge-strong"}`} />
			))}
		</span>
	);
}

export function LiveBadge({ live }: { live: boolean }) {
	return (
		<span className={`micro-label ${live ? "animate-pulse-soft text-green" : "text-fg-faint"}`}>
			{live ? "live" : "ended"}
		</span>
	);
}
