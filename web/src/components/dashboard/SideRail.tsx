import type { Checkpoint, DashboardModel, WorkStatus } from "../../lib/dashboard";
import { formatMs } from "../../lib/dashboard";
import { Panel, LiveBadge } from "./Panel";

const roundClass: Record<WorkStatus, string> = {
	idle: "text-fg-faint",
	running: "text-fg",
	ok: "text-fg-dim",
	error: "text-red",
};

const checkpointLabelClass: Record<Checkpoint["status"], string> = {
	ready: "text-green",
	running: "text-amber",
	pending: "text-fg-faint",
	failed: "text-red",
};

const checkpointBarClass: Record<Checkpoint["status"], string> = {
	ready: "bg-green/70",
	running: "bg-amber/70",
	pending: "bg-edge-strong",
	failed: "bg-red/70",
};

function checkpointLabel(status: Checkpoint["status"]): string {
	return status === "ready" ? "ready" : status === "running" ? "in flight" : status === "failed" ? "failed" : "pending";
}

/** Right rail: LLM rounds checklist, context box, checkpoints. */
export default function SideRail({
	llmRounds,
	checkpoints,
	task,
	workersSummary,
	live,
}: {
	llmRounds: DashboardModel["llmRounds"];
	checkpoints: Checkpoint[];
	task: string;
	/** Real one-liner about the workers scope (invoked agents + tool lanes). */
	workersSummary: string;
	live: boolean;
}) {
	return (
		<div className="flex min-h-0 flex-col gap-2.5">
			<Panel
				title="model / callback rounds"
				right={<LiveBadge live={live} />}
				className="flex-1"
				bodyClassName="scrollbar-thin overflow-y-auto px-3 py-2"
			>
				{llmRounds.length === 0 ? (
					<p className="py-6 text-center text-[11px] text-fg-faint">no model round yet</p>
				) : (
					<ol className="space-y-2.5">
						{llmRounds.map((round) => (
							<li key={round.index}>
								<div className="flex items-baseline gap-2 text-[11.5px]">
									<span className="shrink-0 text-fg-faint">{round.index}</span>
									<span className={`min-w-0 flex-1 truncate ${roundClass[round.status]}`} title={round.text}>
										{round.text}
									</span>
									<span className="shrink-0 text-[9.5px] text-fg-faint">{formatMs(round.durationMs)}</span>
								</div>
								<div className="mt-1.5 h-px w-full bg-edge-strong">
									<div
										className={`h-px ${round.status === "ok" ? "bg-purple/70" : round.status === "running" ? "bg-purple" : ""}`}
									/>
								</div>
							</li>
						))}
					</ol>
				)}
			</Panel>

			<Panel title="context" className="shrink-0" bodyClassName="px-3 py-2.5 space-y-2">
				<p className="text-[11px] text-fg-faint">
					main <span className="text-fg-faint">scope</span>
				</p>
				<p className="truncate text-[12.5px] text-fg" title={task}>
					{task || "no task"}
				</p>
				<p className="text-[11px] text-fg-faint">
					workers <span className="text-fg-faint">scope</span>
				</p>
				<p className="truncate text-[12.5px] text-fg" title={workersSummary}>
					{workersSummary}
				</p>
			</Panel>

			<Panel title="checkpoints" className="shrink-0" bodyClassName="px-3 py-2.5">
				<ul className="space-y-1.5">
					{checkpoints.map((checkpoint) => (
						<li key={checkpoint.key} className="flex items-center justify-between gap-2 text-[11.5px]">
							<span className="text-fg-dim">{checkpoint.label}</span>
							<span className={`text-[10.5px] ${checkpointLabelClass[checkpoint.status]}`}>
								{checkpointLabel(checkpoint.status)}
							</span>
						</li>
					))}
				</ul>
				<div className="mt-2 flex gap-1">
					{checkpoints.map((checkpoint) => (
						<span key={checkpoint.key} className={`h-[3px] flex-1 ${checkpointBarClass[checkpoint.status]}`} />
					))}
				</div>
			</Panel>
		</div>
	);
}
