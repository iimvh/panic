import { useMemo, useState } from "react";
import { buildTree, countRunning, type PipelineEvent, type TreeNode } from "../lib/pipeline";
import type { StreamState } from "../hooks/usePipelineStream";
import { CloseIcon, PipelineIcon } from "./Icons";

interface PipelinePanelProps {
	events: PipelineEvent[];
	state: StreamState;
	busy: boolean;
	traceId: number | null;
	error: string | null;
	onClose: () => void;
}

const PHASE_STYLE: Record<
	string,
	{ label: string; badge: string; dot: string; ring: string }
> = {
	trace: {
		label: "Trace",
		badge: "bg-slate-500/15 text-slate-300 ring-slate-400/25",
		dot: "bg-slate-300",
		ring: "ring-slate-400/30",
	},
	llm: {
		label: "LLM",
		badge: "bg-violet-500/15 text-violet-200 ring-violet-400/25",
		dot: "bg-violet-400",
		ring: "ring-violet-400/30",
	},
	tool: {
		label: "Tool",
		badge: "bg-sky-500/15 text-sky-200 ring-sky-400/25",
		dot: "bg-sky-400",
		ring: "ring-sky-400/30",
	},
	parse: {
		label: "Parse",
		badge: "bg-amber-500/15 text-amber-200 ring-amber-400/25",
		dot: "bg-amber-400",
		ring: "ring-amber-400/30",
	},
	error: {
		label: "Error",
		badge: "bg-rose-500/15 text-rose-200 ring-rose-400/25",
		dot: "bg-rose-400",
		ring: "ring-rose-400/30",
	},
};

const STATE_TEXT: Record<StreamState, string> = {
	idle: "Not started",
	connecting: "Connecting…",
	live: "Realtime",
	closed: "Finished",
};

function prettyDetail(detail: string | null): string {
	if (!detail) return "";
	try {
		return JSON.stringify(JSON.parse(detail), null, 2);
	} catch {
		return detail;
	}
}

function formatMs(value: number | null): string {
	if (value == null) return "";
	return value >= 1000 ? `${(value / 1000).toFixed(2)}s` : `${Math.round(value)}ms`;
}

function StatusDot({ status }: { status: PipelineEvent["status"] }) {
	if (status === "running") {
		return (
			<span className="relative flex h-2 w-2">
				<span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-accent-400 opacity-75" />
				<span className="relative inline-flex h-2 w-2 rounded-full bg-accent-400" />
			</span>
		);
	}
	return (
		<span
			className={`h-2 w-2 rounded-full ${status === "error" ? "bg-rose-400" : "bg-emerald-400"}`}
		/>
	);
}

function EventRow({ node, depth }: { node: TreeNode; depth: number }) {
	const [open, setOpen] = useState(false);
	const { event } = node;
	const style = PHASE_STYLE[event.phase] ?? PHASE_STYLE.trace;
	const detail = prettyDetail(event.detail);
	const hasDetail = detail.trim().length > 0;

	return (
		<li className="relative list-none">
			{depth > 0 && (
				<span
					className="absolute left-0 top-0 h-[22px] w-3 rounded-bl-lg border-b border-l border-white/10"
					aria-hidden="true"
				/>
			)}
			<div
				className={`ml-1 rounded-xl border px-2.5 py-2 transition ${
					event.status === "running"
						? "border-accent-500/40 bg-accent-500/[0.08]"
						: event.status === "error"
							? "border-rose-500/30 bg-rose-500/[0.05]"
							: "border-white/[0.06] bg-white/[0.025] hover:bg-white/[0.05]"
				}`}
			>
				<button
					type="button"
					onClick={() => hasDetail && setOpen((value) => !value)}
					className="flex w-full items-start gap-2 text-left"
					aria-expanded={open}
				>
					<span className="mt-1.5">
						<StatusDot status={event.status} />
					</span>
					<span className="min-w-0 flex-1">
						<span className="flex flex-wrap items-center gap-1.5">
							<span
								className={`rounded px-1.5 py-px text-[9.5px] font-bold uppercase tracking-wide ring-1 ring-inset ${style.badge}`}
							>
								{style.label}
							</span>
							<span className="truncate text-[11px] font-medium text-slate-400">
								{event.actor}
							</span>
							{event.durationMs != null && event.durationMs > 0 && (
								<span className="ml-auto shrink-0 font-mono text-[10px] text-slate-500">
									{formatMs(event.durationMs)}
								</span>
							)}
						</span>
						<span className="mt-0.5 block text-[12.5px] leading-snug text-slate-100">
							{event.title}
						</span>
						{!open && hasDetail && (
							<span className="mt-0.5 block truncate font-mono text-[10.5px] text-slate-500">
								{detail.replace(/\s+/g, " ")}
							</span>
						)}
					</span>
				</button>
				{open && hasDetail && (
					<pre className="scrollbar-thin mt-2 max-h-56 overflow-auto rounded-lg bg-slate-950/70 p-2.5 font-mono text-[10.5px] leading-relaxed text-slate-300 ring-1 ring-inset ring-white/[0.06]">
						{detail}
					</pre>
				)}
			</div>
			{node.children.length > 0 && (
				<ul className="ml-3 mt-1.5 space-y-1.5 border-l border-white/[0.08] pl-2.5">
					{node.children.map((child) => (
						<EventRow key={child.event.id} node={child} depth={depth + 1} />
					))}
				</ul>
			)}
		</li>
	);
}

export default function PipelinePanel({ events, state, busy, traceId, error, onClose }: PipelinePanelProps) {
	const tree = useMemo(() => buildTree(events), [events]);
	const running = countRunning(events);

	return (
		<aside className="flex h-full w-full flex-col border-l border-white/[0.06] bg-[#0b1020]/95">
			<header className="flex items-center gap-2 border-b border-white/[0.06] px-4 py-3">
				<span className="grid h-7 w-7 place-items-center rounded-lg bg-accent-500/15 text-accent-300">
					<PipelineIcon className="h-4 w-4" />
				</span>
				<div className="min-w-0 flex-1">
					<h2 className="text-[12.5px] font-bold text-white">Agent pipeline</h2>
					<p className="flex items-center gap-1.5 text-[10.5px] text-slate-500">
						<span
							className={`h-1.5 w-1.5 rounded-full ${
								state === "live" || busy
									? "animate-pulse bg-emerald-400"
									: state === "closed"
										? "bg-emerald-400"
										: "bg-slate-500"
							}`}
						/>
						{STATE_TEXT[state]}
						{running > 0 && <span className="text-accent-300">· {running} running</span>}
						{traceId != null && (
							<span className="ml-auto truncate font-mono text-[10px] text-slate-600">
								#{String(traceId).slice(-6)}
							</span>
						)}
					</p>
				</div>
				<button
					type="button"
					onClick={onClose}
					aria-label="Hide pipeline"
					className="grid h-7 w-7 place-items-center rounded-lg text-slate-500 transition hover:bg-white/[0.06] hover:text-white"
				>
					<CloseIcon className="h-4 w-4" />
				</button>
			</header>

			<div className="scrollbar-thin flex-1 overflow-y-auto px-2.5 py-3">
				{events.length === 0 ? (
					<div className="mt-10 px-4 text-center">
						<p className="text-[12.5px] leading-relaxed text-slate-400">
							No chat turn has run yet.
						</p>
						<p className="mt-1.5 text-[11.5px] leading-relaxed text-slate-500">
							Send a message to watch the coordinator call tools, domain agents work and services return
							data — updated step by step.
						</p>
					</div>
				) : (
					<ul className="space-y-1.5">
						{tree.map((node) => (
							<EventRow key={node.event.id} node={node} depth={0} />
						))}
					</ul>
				)}
				{error && (
					<div className="mt-3 rounded-xl border border-rose-500/25 bg-rose-500/[0.08] p-2.5 text-[11.5px] leading-relaxed text-rose-200">
						{error}
					</div>
				)}
			</div>

			<footer className="border-t border-white/[0.06] px-4 py-2.5 text-[10.5px] text-slate-500">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1">
					<span className="inline-flex items-center gap-1">
						<i className="h-1.5 w-1.5 rounded-full bg-slate-300" /> trace
					</span>
					<span className="inline-flex items-center gap-1">
						<i className="h-1.5 w-1.5 rounded-full bg-violet-400" /> LLM
					</span>
					<span className="inline-flex items-center gap-1">
						<i className="h-1.5 w-1.5 rounded-full bg-sky-400" /> tool / service
					</span>
					<span className="inline-flex items-center gap-1">
						<i className="h-1.5 w-1.5 rounded-full bg-rose-400" /> error
					</span>
				</div>
			</footer>
		</aside>
	);
}
