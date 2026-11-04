import { useMemo, useState } from "react";
import { buildTree, countRunning, type PipelineEvent, type TreeNode } from "../lib/pipeline";
import type { StreamState } from "../hooks/usePipelineStream";
import TrajectoryPanel from "./TrajectoryPanel";
import { CloseIcon, PipelineIcon } from "./Icons";

interface PipelinePanelProps {
	events: PipelineEvent[];
	state: StreamState;
	busy: boolean;
	traceId: number | null;
	error: string | null;
	onClose: () => void;
}

const PHASE_STYLE: Record<string, { label: string; badge: string; dot: string }> = {
	trace: {
		label: "Trace",
		badge: "border-hairline text-body-muted",
		dot: "bg-muted",
	},
	llm: {
		label: "LLM",
		badge: "border-accent-border bg-accent-soft text-accent",
		dot: "bg-accent",
	},
	tool: {
		label: "Tool",
		badge: "border-hairline bg-card text-ink",
		dot: "bg-body-muted",
	},
	parse: {
		label: "Parse",
		badge: "border-hairline text-muted",
		dot: "bg-muted-soft",
	},
	message: {
		label: "Message",
		badge: "border-hairline bg-card text-body-muted",
		dot: "bg-hairline-strong",
	},
	error: {
		label: "Error",
		badge: "border-danger-border bg-danger-soft text-danger",
		dot: "bg-danger-strong",
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

/** Collapsed detail preview: tool/skill chips, quoted text, tiny labels — never raw braces. */
function DetailPreview({ detail }: { detail: string }) {
	let parsed: unknown;
	try {
		parsed = JSON.parse(detail);
	} catch {
		const text = String(detail).trim();
		return <span className="block truncate font-sans text-[12px] text-muted-soft">“{text}”</span>;
	}
	if (parsed == null || typeof parsed !== "object") {
		const text = String(parsed ?? "").trim();
		return text.length > 0 ? (
			<span className="block truncate font-sans text-[12px] text-muted-soft">“{text}”</span>
		) : null;
	}
	const record = parsed as Record<string, unknown>;
	const chips = (keys: string[]) => {
		for (const key of keys) {
			const value = record[key];
			if (Array.isArray(value) && value.length > 0) return value;
		}
		return [];
	};
	const tools = chips(["tools"]);
	const skills = chips(["skills"]);
	const textKeys = ["error", "output", "text", "prompt", "query"];
	let text = "";
	for (const key of textKeys) {
		const value = record[key];
		if (typeof value === "string" && value.trim().length > 0) {
			text = value.trim();
			break;
		}
	}
	if (typeof record.output === "object" && record.output !== null) {
		text = JSON.stringify(record.output);
	}
	return (
		<span className="mt-0.5 flex flex-wrap items-center gap-1">
			{typeof record.depth === "number" && (
				<span className="rounded-xs bg-surface-alt px-1.5 py-px font-sans text-[9.5px] font-medium uppercase tracking-wide text-muted">
					depth {record.depth}
				</span>
			)}
				{tools.map((tool) => (
				<span
					key={String(tool)}
					className="max-w-40 truncate rounded-xs border border-hairline bg-card px-1.5 py-px font-mono text-[9.5px] text-body-muted"
					title={String(tool)}
				>
					{String(tool)}
				</span>
			))}
			{skills.map((skill) => (
				<span
					key={String(skill)}
					className="rounded-xs bg-accent-soft px-1.5 py-px font-sans text-[9.5px] font-medium text-accent"
				>
					{String(skill)}
				</span>
			))}
			{text && (
				<span className="block w-full truncate font-sans text-[12px] text-muted-soft">
					“{text.length > 120 ? `${text.slice(0, 120)}…` : text}”
				</span>
			)}
		</span>
	);
}

function formatMs(value: number | null): string {
	if (value == null) return "";
	return value >= 1000 ? `${(value / 1000).toFixed(2)}s` : `${Math.round(value)}ms`;
}

function StatusDot({ status }: { status: PipelineEvent["status"] }) {
	if (status === "running") {
		return (
			<span className="relative flex h-2 w-2">
				<span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-accent opacity-40" />
				<span className="relative inline-flex h-2 w-2 rounded-full bg-accent" />
			</span>
		);
	}
		return (
			<span
				className={`h-2 w-2 rounded-full ${status === "error" ? "bg-danger-strong" : "bg-muted-soft"}`}
			/>
		);
}

function Chevron({ open }: { open: boolean }) {
	return (
		<span
			aria-hidden="true"
			className={`grid h-4 w-4 shrink-0 place-items-center text-muted transition-transform ${open ? "rotate-90" : ""}`}
		>
			<svg viewBox="0 0 8 8" fill="none" className="h-2 w-2">
				<path d="M2.5 1.5 5 4 2.5 6.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
			</svg>
		</span>
	);
}

function EventRow({ node, depth }: { node: TreeNode; depth: number }) {
	const [open, setOpen] = useState(false);
	const { event } = node;
	const style = PHASE_STYLE[event.phase] ?? PHASE_STYLE.trace;
	const detail = prettyDetail(event.detail);
	const hasDetail = detail.trim().length > 0;
	const isMessage = event.phase === "message";

	return (
		<li className="relative list-none">
			{depth > 0 && (
				<span
					className="absolute left-0 top-0 h-[26px] w-3.5 rounded-bl-md border-b border-l border-hairline"
					aria-hidden="true"
				/>
			)}
			<div
				className={`ml-1 rounded-sm border transition-colors ${
					depth > 0 ? "bg-surface/50" : "bg-card"
				} ${
					event.status === "running"
						? "border-accent-border bg-accent-soft shadow-[0_0_0_2px_rgba(123,132,255,0.1)]"
						: event.status === "error"
							? "border-danger-border bg-danger-soft"
							: "border-hairline hover:border-hairline-strong"
				}`}
			>
				<button
					type="button"
					onClick={() => hasDetail && setOpen((value) => !value)}
					className={`flex w-full items-start gap-2 px-2.5 py-2 text-left ${hasDetail ? "cursor-pointer" : "cursor-default"}`}
					aria-expanded={open}
				>
					<span className="mt-[5px]">
						<StatusDot status={event.status} />
					</span>
					<span className="min-w-0 flex-1">
						<span className="flex flex-wrap items-center gap-1.5">
							<span
								className={`rounded-xs border px-1.5 py-px font-sans text-[10px] font-bold uppercase tracking-wide ${style.badge}`}
							>
								{style.label}
							</span>
							{isMessage ? (
								<span className="font-sans text-[12px] italic text-muted">conversation</span>
							) : (
								<span className="truncate font-sans text-[12px] text-body-muted">{event.actor}</span>
							)}
							{event.durationMs != null && event.durationMs > 0 && (
								<span
									className={`ml-auto shrink-0 rounded-xs px-1.5 py-px font-mono text-[10.5px] ${
										event.status === "running"
											? "bg-accent-soft text-accent"
											: "text-muted-soft"
									}`}
								>
									{event.status === "running" ? "•••" : formatMs(event.durationMs)}
								</span>
							)}
							{hasDetail && <Chevron open={open} />}
						</span>
						<span className="mt-0.5 block font-sans text-[13px] leading-snug text-ink">
							{event.title}
						</span>
						{!open && hasDetail && <DetailPreview detail={detail} />}
					</span>
				</button>
				{open && hasDetail && (
					<pre className="scrollbar-thin mt-0 max-h-56 overflow-auto rounded-b-sm border-t border-dashed border-hairline bg-surface-alt/70 p-2.5 font-mono text-[11px] leading-relaxed text-body-muted">
						{detail}
					</pre>
				)}
			</div>
			{node.children.length > 0 && (
				<ul className="ml-3 mt-1.5 space-y-1.5 border-l border-dashed border-hairline pl-2.5">
					{node.children.map((child) => (
						<EventRow key={child.event.id} node={child} depth={depth + 1} />
					))}
				</ul>
			)}
		</li>
	);
}

type PanelView = "trajectory" | "steps";

export default function PipelinePanel({ events, state, busy, traceId, error, onClose }: PipelinePanelProps) {
	const tree = useMemo(() => buildTree(events), [events]);
	const running = countRunning(events);
	const [view, setView] = useState<PanelView>("trajectory");

	return (
		<aside className="flex h-full w-full flex-col border-l border-hairline bg-surface-alt">
			<header className="border-b border-hairline bg-surface px-4 py-3">
				<div className="flex items-center gap-2.5">
					<span className="grid h-7 w-7 place-items-center rounded-sm border border-hairline bg-card text-ink">
						<PipelineIcon className="h-4 w-4" />
					</span>
					<div className="min-w-0 flex-1">
						<h2 className="font-sans text-[13px] font-semibold text-ink">Agent pipeline</h2>
						<p className="flex items-center gap-1.5 font-sans text-[12px] text-body-muted">
							<span
								className={`h-1.5 w-1.5 rounded-full ${
									state === "live" || busy
										? "animate-pulse bg-accent"
										: state === "closed"
											? "bg-muted"
											: "bg-hairline-strong"
								}`}
							/>
							{STATE_TEXT[state]}
							{running > 0 && <span className="text-ink">· {running} running</span>}
							{traceId != null && (
								<span className="ml-auto truncate font-mono text-[11px] text-muted-soft">
									#{String(traceId).slice(-6)}
								</span>
							)}
						</p>
					</div>
					<button
						type="button"
						onClick={onClose}
						aria-label="Hide pipeline"
						className="grid h-8 w-8 place-items-center rounded-sm text-body-muted transition-colors hover:bg-soft-fill hover:text-ink"
					>
						<CloseIcon className="h-4 w-4" />
					</button>
				</div>
				{/* Tabs: trajectory viewer (predefined screenshot) or raw step tree. */}
				<nav className="mt-2.5 flex gap-3 border-b border-hairline" aria-label="Pipeline views">
					{(["trajectory", "steps"] as const).map((key) => (
						<button
							key={key}
							type="button"
							onClick={() => setView(key)}
							aria-current={view === key}
							className={`-mb-px border-b-2 pb-1.5 font-sans text-[12px] transition-colors ${
								view === key
									? "border-accent font-semibold text-ink"
									: "border-transparent text-body-muted hover:text-ink"
							}`}
						>
							{key === "trajectory" ? "Trajectory" : "Steps"}
						</button>
					))}
				</nav>
			</header>

			{view === "trajectory" ? (
				<TrajectoryPanel events={events} busy={busy} error={error} />
			) : (
				<>
					<div className="scrollbar-thin flex-1 overflow-y-auto px-2.5 py-3">
						{events.length === 0 ? (
						<div className="mt-10 px-4 text-center">
							<p className="font-sans text-[15px] leading-relaxed text-body-muted">
									No chat turn has run yet.
								</p>
								<p className="mt-2 font-sans text-[12px] leading-relaxed text-muted">
									Send a message to watch the coordinator call tools, domain agents work and
									services return data — updated step by step.
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
							<div className="mt-3 rounded-sm border border-danger-border bg-danger-soft p-2.5 font-sans text-[12px] leading-relaxed text-danger">
								{error}
							</div>
						)}
					</div>

					<footer className="border-t border-hairline px-4 py-2.5 font-sans text-[11px] text-muted">
						<div className="flex flex-wrap items-center gap-x-3 gap-y-1">
							<span className="inline-flex items-center gap-1">
								<i className="h-1.5 w-1.5 rounded-full bg-muted" /> trace
							</span>
							<span className="inline-flex items-center gap-1">
								<i className="h-1.5 w-1.5 rounded-full bg-accent" /> LLM
							</span>
							<span className="inline-flex items-center gap-1">
								<i className="h-1.5 w-1.5 rounded-full bg-body-muted" /> tool / service
							</span>
							<span className="inline-flex items-center gap-1">
								<i className="h-1.5 w-1.5 rounded-full bg-muted-soft" /> parse
							</span>
						</div>
					</footer>
				</>
			)}
		</aside>
	);
}
