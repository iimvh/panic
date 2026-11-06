import { useEffect, useRef, useState } from "react";
import type { DashboardModel, Tint } from "../../lib/dashboard";
import { Panel, tintText } from "./Panel";

const STATUS_MARK: Record<string, string> = {
	running: "◌",
	ok: "✓",
	error: "✗",
};

/** One stamped line of the event log. */
function StreamLine({
	row,
}: {
	row: { id: number; stamp: string; actor: string; tint: Tint; text: string; status: string };
}) {
	return (
		<li className="flex items-baseline gap-3 px-3 font-mono text-[11px] leading-relaxed">
			<span className="w-10 shrink-0 select-none text-right text-fg-faint">{row.stamp}</span>
			<span className={`w-20 shrink-0 truncate ${tintText[row.tint]}`} title={row.actor}>
				{row.actor}
			</span>
			<span
				className={`min-w-0 flex-1 truncate ${
					row.status === "running"
						? "text-green"
						: row.status === "error"
							? "text-red"
							: row.tint === "green"
								? "text-green"
								: "text-fg"
				}`}
				title={row.text}
			>
				{STATUS_MARK[row.status] ?? ""} {row.text}
			</span>
		</li>
	);
}

/** Bottom log: every recorded step of the turn, stamped in seconds from trace start. */
export default function EventStream({ dashboard }: { dashboard: DashboardModel }) {
	const [stick, setStick] = useState(true);
	const scrollerRef = useRef<HTMLDivElement | null>(null);
	const stickRef = useRef(true);
	stickRef.current = stick;
	const count = dashboard.stream.length;

	useEffect(() => {
		if (stickRef.current) scrollerRef.current?.scrollTo({ top: scrollerRef.current.scrollHeight });
	}, [count]);

	const onScroll = () => {
		const el = scrollerRef.current;
		if (!el) return;
		setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 32);
	};

	const { done, total } = dashboard.progress;
	const footerText =
		dashboard.model != null
			? `${dashboard.model} leads  /  workers scan  /  tools verify`
			: "main writes  /  workers scan  /  tools verify";

	return (
		<Panel
			title="event stream / one task"
			right={<span className="micro-label text-fg-faint">{dashboard.stream.length} events</span>}
			className="shrink-0"
		>
			<div ref={scrollerRef} onScroll={onScroll} className="scrollbar-thin h-52 min-h-0 overflow-y-auto py-2">
				{dashboard.stream.length === 0 ? (
					<p className="py-8 text-center text-[11px] text-fg-faint">
						no events yet — the stream fills as the task runs
					</p>
				) : (
					<ul>
						{dashboard.stream.map((row) => (
							<StreamLine key={row.id} row={row} />
						))}
					</ul>
				)}
			</div>

			<footer className="shrink-0 border-t border-edge px-3 py-2.5">
				<div className="flex items-baseline justify-between gap-3 text-[11px]">
					<span className="text-fg-dim">{footerText}</span>
					<span className="font-mono text-green">
						{done} / {total}
					</span>
				</div>
				<div className="mt-2 h-[3px] w-full bg-edge-strong">
					<div
						className="h-[3px] bg-green/80 transition-all duration-300"
						style={{ width: total === 0 ? 0 : `${Math.round((done / total) * 100)}%` }}
					/>
				</div>
			</footer>
		</Panel>
	);
}
