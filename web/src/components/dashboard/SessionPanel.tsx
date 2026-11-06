import { useEffect, useRef } from "react";
import type { SessionLine, WorkStatus } from "../../lib/dashboard";
import { LiveBadge } from "./Panel";

const MARKER: Record<SessionLine["marker"], { sign: string; row: string; signClass: string }> = {
	task: { sign: "»", row: "text-amber", signClass: "text-amber" },
	llm: { sign: "·", row: "text-fg-dim", signClass: "text-purple" },
	tool: { sign: "+", row: "text-green", signClass: "text-green" },
	error: { sign: "x", row: "text-red", signClass: "text-red" },
};

const RUNNING_BG: Partial<Record<WorkStatus, string>> = {
	running: "bg-[rgba(83,196,140,0.08)]",
	error: "bg-[rgba(229,100,108,0.08)]",
};

/** One numbered work line; tool calls render as green diff-style "+" rows. */
function SessionRow({ line, index }: { line: SessionLine; index: number }) {	const style = MARKER[line.marker];
	return (
		<li className={`flex items-baseline gap-2 px-3 font-mono text-[11.5px] leading-[1.65] ${RUNNING_BG[line.status] ?? ""}`}>
			<span className="w-7 shrink-0 select-none text-right text-fg-faint">{String(index).padStart(2, "0")}</span>
			<span className={`w-3 shrink-0 select-none ${style.signClass}`}>{style.sign}</span>
			<span className={`min-w-0 flex-1 truncate ${style.row}`} title={line.text}>
				{line.text}
				{line.status === "running" && <span className="ml-1 animate-pulse-soft text-green">▌</span>}
			</span>
			{line.hint && (
				<span
					className={`shrink-0 text-[10px] ${line.status === "error" ? "text-red" : "text-fg-faint"}`}
				>
					{line.hint}
				</span>
			)}
		</li>
	);
}

/** Center screen: the main agent's session replayed as a numbered work log. */
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
	const logRef = useRef<HTMLOListElement | null>(null);
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
					{turnNumber != null && <span className="micro-label text-fg-faint">TURN {String(turnNumber).padStart(2, "0")}</span>}
					<LiveBadge live={live} />
				</span>
			</header>

			<div className="flex shrink-0 items-center justify-between border-b border-edge px-3 py-1.5 font-mono text-[10.5px] text-fg-dim">
				<span>agents/main/workspace</span>
				{error && <span className="text-red">{error}</span>}
			</div>

			<div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto py-2">
				{lines.length === 0 ? (
					<p className="px-5 py-10 text-center font-mono text-[11.5px] text-fg-faint">
						main session idle — send a task above
					</p>
				) : (
					<ol ref={logRef}>
						{lines.map((line, index) => (
							<SessionRow key={`${line.id}-${index}`} line={line} index={index} />
						))}
					</ol>
				)}
			</div>

			{answer && (
				<div
					ref={answerRef}
					className="scrollbar-thin max-h-[45%] min-h-0 shrink-0 overflow-y-auto border-t border-edge bg-[rgba(111,168,255,0.07)] px-3 py-2.5"
				>
					<span className="micro-label text-blue">answer</span>
					<p className="mt-1.5 whitespace-pre-wrap break-words font-mono text-[11.5px] leading-[1.65] text-blue/90">
						{answer}
						{live && <span className="animate-pulse-soft"> ▌</span>}
					</p>
				</div>
			)}

			<footer className="shrink-0 border-t border-edge px-3 py-2 text-[10.5px] text-fg-faint">
				evidence scoped to current task
			</footer>
		</section>
	);
}
