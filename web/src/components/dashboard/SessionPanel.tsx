import { useEffect, useRef } from "react";
import type { SessionLine, WorkStatus } from "../../lib/dashboard";
import { LiveBadge } from "./Panel";

const MARKER: Record<SessionLine["marker"], { sign: string; row: string; signClass: string }> = {
	task: { sign: "task", row: "text-amber", signClass: "text-amber" },
	llm: { sign: "llm", row: "text-fg-dim", signClass: "text-purple" },
	tool: { sign: "tool", row: "text-green", signClass: "text-green" },
	error: { sign: "error", row: "text-red", signClass: "text-red" },
};

const RUNNING_BG: Partial<Record<WorkStatus, string>> = {
	running: "bg-[rgba(83,196,140,0.08)]",
	error: "bg-[rgba(229,100,108,0.08)]",
};

/** One compact numbered work line: `01 task → ${summary}`, durations on the right. */
function SessionRow({ line, index }: { line: SessionLine; index: number }) {
	const style = MARKER[line.marker];
	return (
		<li className={`flex items-baseline gap-2 px-2.5 font-mono text-[11.5px] leading-[1.65] ${RUNNING_BG[line.status] ?? ""}`}>
			<span className="w-6 shrink-0 select-none text-right text-fg-faint">{String(index).padStart(2, "0")}</span>
			<span className={`w-8 shrink-0 select-none truncate ${style.signClass}`}>{style.sign}</span>
			<span className="shrink-0 select-none text-fg-faint">→</span>
			<span className={`min-w-0 flex-1 truncate ${style.row}`} title={line.text}>
				{line.text}
				{line.status === "running" && <span className="ml-1 animate-pulse-soft text-green">▌</span>}
			</span>
			{line.hint && (
				<span className={`shrink-0 text-[10px] ${line.status === "error" ? "text-red" : "text-fg-faint"}`}>{line.hint}</span>
			)}
		</li>
	);
}

/** Bordered sub-box with a micro header — the ANSWER / EXECUTION LOG frames inside the panel. */
function InnerBox({
	label,
	kind,
	boxClassName,
	children,
}: {
	label: string;
	kind: string;
	boxClassName?: string;
	children: React.ReactNode;
}) {
	return (
		<div className={`flex min-h-0 flex-col overflow-hidden border border-edge-strong bg-panel-raised ${boxClassName ?? ""}`}>
			<header className="flex shrink-0 items-center justify-between border-b border-edge px-2.5 py-1.5">
				<span className="micro-label text-fg-dim">{label}</span>
				<span className="micro-label text-fg-faint">{kind}</span>
			</header>
			{children}
		</div>
	);
}

/** Center screen: main session replay — answer box first (primary), execution log compact below. */
export default function SessionPanel({
	lines,
	agentId,
	turnNumber,
	live,
	answer,
	error,
}: {
	lines: SessionLine[];
	agentId: string | null;
	turnNumber: number | null;
	live: boolean;
	/** Live-streamed assistant answer while the model is speaking. */
	answer: string;
	error: string | null;
}) {
	const logRef = useRef<HTMLDivElement | null>(null);
	const answerRef = useRef<HTMLDivElement | null>(null);
	const count = lines.length;

	// Stick to the newest work line while the turn builds.
	useEffect(() => {
		logRef.current?.scrollTo({ top: logRef.current.scrollHeight });
	}, [count]);

	// Keep the answer pane pinned to the newest streamed characters.
	useEffect(() => {
		answerRef.current?.scrollTo({ top: answerRef.current.scrollHeight });
	}, [answer]);

	const mistake = lines.some((line) => line.status === "error");
	return (
		<section className="flex h-full min-h-0 flex-col border border-edge-strong bg-panel">
			<header className="flex shrink-0 items-center gap-2 border-b border-edge-strong bg-panel-head px-3 py-2">
				<span className={`micro-label ${mistake ? "text-red" : "text-amber"}`}>
					{(agentId ?? "agent").toUpperCase()} / MAIN SESSION
				</span>
				<span className="ml-auto flex items-center gap-3">
					{turnNumber != null && <span className="micro-label text-fg-faint">SESSION {String(turnNumber).padStart(2, "0")}</span>}
					<LiveBadge live={live} />
				</span>
			</header>

			<div className="flex shrink-0 items-center justify-between border-b border-edge px-3 py-1.5 font-mono text-[10.5px] text-fg-dim">
				<span>agents/main/workspace</span>
				{error && <span className="min-w-0 truncate text-red" title={error}>{error}</span>}
			</div>

			<div className="flex min-h-0 flex-1 flex-col gap-2.5 p-2.5">
				{/* ANSWER — the primary box takes the panel's bulk; body scrolls inside. */}
				<InnerBox label="answer" kind="primary" boxClassName="min-h-0 flex-1">
					<div
						ref={answerRef}
						className="scrollbar-thin min-h-0 flex-1 overflow-y-auto overscroll-contain px-2.5 py-2"
					>
						{answer ? (
							<p className="whitespace-pre-wrap break-words font-mono text-[11.5px] leading-[1.65] text-fg">
								{answer}
								{live && <span className="animate-pulse-soft text-blue"> ▌</span>}
							</p>
						) : (
							<p className="px-5 py-8 text-center font-mono text-[11.5px] text-fg-faint">
								answer pending — run a task to see the result
							</p>
						)}
					</div>
				</InnerBox>

				{/* EXECUTION LOG — hugs short content, capped at 10 rows; body scrolls inside. */}
				<InnerBox label="execution log" kind="compact" boxClassName="shrink-0">
					<div
						ref={logRef}
						className="scrollbar-thin min-h-0 max-h-[190px] overflow-y-auto overscroll-contain py-1.5 lg:max-h-[150px]"
					>
						{lines.length === 0 ? (
							<p className="px-5 py-6 text-center font-mono text-[11.5px] text-fg-faint">
								main session idle — send a task above
							</p>
						) : (
							<ol>
								{lines.map((line, index) => (
									<SessionRow key={`${line.id}-${index}`} line={line} index={index} />
								))}
							</ol>
						)}
					</div>
				</InnerBox>
			</div>

			<footer className="shrink-0 border-t border-edge px-3 py-2 text-[10.5px] text-fg-faint">
				evidence scoped to current task
			</footer>
		</section>
	);
}
