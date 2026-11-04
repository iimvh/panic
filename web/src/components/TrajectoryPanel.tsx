import { useEffect, useMemo, useRef, useState } from "react";
import type { PipelineEvent } from "../lib/pipeline";
import {
	formatDuration,
	matchesSearch,
	roleLabel,
	roleOf,
	stats,
	timeWindow,
	timeline,
	type TrajectoryRole,
} from "../lib/trajectory";
import { SearchIcon } from "./Icons";

interface TrajectoryPanelProps {
	events: PipelineEvent[];
	busy: boolean;
	error: string | null;
}

const ROLE_BADGE: Record<TrajectoryRole, string> = {
	system: "border-hairline bg-card text-body-muted",
	user: "border-hairline-strong bg-soft-fill text-ink",
	assistant: "border-accent-border bg-accent-soft text-accent",
	tool: "border-transparent bg-white text-canvas",
	context: "border-hairline bg-card text-muted",
};

const ROW_STYLE: Record<string, { label: string; bar: string; hint: string }> = {
	input: { label: "Input", bar: "bg-muted-soft", hint: "prompts & context" },
	model: { label: "Model", bar: "bg-white", hint: "assistant turns" },
	tools: { label: "Tools", bar: "bg-accent", hint: "tool & agent calls" },
};

function prettyDetail(detail: string | null): string {
	if (!detail) return "";
	try {
		return JSON.stringify(JSON.parse(detail), null, 2);
	} catch {
		return detail;
	}
}

/** One horizontal track of the Gantt; `segments` already filtered and positioned. */
function TimelineRow({ label, hint, bar, segments }: {
	label: string;
	hint: string;
	bar: string;
	segments: { id: number; left: number; width: number; tooltip: string }[];
}) {
	return (
		<div className="flex items-center gap-2">
			<div className="w-14 shrink-0 text-right">
				<span className="block font-sans text-[11px] font-semibold leading-none text-ink">{label}</span>
				<span className="block font-sans text-[10px] leading-tight text-muted">{hint}</span>
			</div>
			<div className="relative h-4 flex-1 rounded-sm bg-canvas shadow-[inset_0_0_0_1px_rgba(255,255,255,0.07)]">
				{segments.map((segment) => (
					<span
						key={segment.id}
						title={segment.tooltip}
						style={{ left: `${segment.left}%`, width: `${segment.width}%` }}
						className={`absolute top-1/2 h-2 -translate-y-1/2 rounded-[3px] transition-opacity ${bar} opacity-85 hover:opacity-100`}
					/>
				))}
			</div>
		</div>
	);
}

/** One row of the message stream; expands to the raw payload like the viewer in the screenshot. */
function StreamEntry({ event }: { event: PipelineEvent }) {
	const [open, setOpen] = useState(false);
	const role = roleOf(event);
	const log = prettyDetail(event.detail);
	const hasDetail = log.trim().length > 0;

	return (
		<li className="group">
			<button
				type="button"
				onClick={() => hasDetail && setOpen((value) => !value)}
				className={`flex w-full items-start gap-2.5 border-b border-hairline/70 px-1 py-2 text-left transition-colors ${
					hasDetail ? "hover:bg-card" : "cursor-default"
				}`}
				aria-expanded={open}
			>
				<span
					className={`mt-[3px] shrink-0 rounded-xs border px-1.5 py-px font-sans text-[9.5px] font-bold uppercase tracking-wide ${ROLE_BADGE[role]}`}
				>
					{roleLabel(role)}
				</span>
				<span className="min-w-0 flex-1">
					<span className="block font-sans text-[12.5px] leading-snug text-ink">{event.title}</span>
					{!open && event.actor && event.actor !== "user" && (
						<span className="mt-px block font-sans text-[10.5px] text-muted">{event.actor}</span>
					)}
					{open && hasDetail && (
						<pre className="scrollbar-thin mt-1.5 max-h-56 overflow-auto whitespace-pre-wrap rounded-sm bg-surface-alt p-2.5 font-mono text-[10.5px] leading-relaxed text-body-muted">
							{log}
						</pre>
					)}
				</span>
				<span className="mt-[3px] shrink-0 font-mono text-[10.5px] text-muted-soft">
					{formatDuration(event.durationMs)}
				</span>
			</button>
		</li>
	);
}

export default function TrajectoryPanel({ events, busy, error }: TrajectoryPanelProps) {
	const [query, setQuery] = useState("");
	const [stick, setStick] = useState(true);
	const scrollerRef = useRef<HTMLDivElement | null>(null);
	const lastStreamEnd = useRef(0);
	const stickRef = useRef(true);
	stickRef.current = stick;

	const window = useMemo(
		() => timeWindow(events, busy ? Date.now() : undefined),
		[events, busy]
	);

	const statsBox = useMemo(() => stats(events, window), [events, window]);
	const segments = useMemo(() => timeline(events, window), [events, window]);

	const filtered = useMemo(() => {
		const visible = events.filter((event) => matchesSearch(event, query));
		const visibleIds = new Set(visible.map((event) => event.id));
		return { visible, visibleIds };
	}, [events, query]);

	const rows: Record<string, { id: number; left: number; width: number; tooltip: string }[]> = {
		input: [],
		model: [],
		tools: [],
	};
	for (const segment of segments) {
		if (!filtered.visibleIds.has(segment.event.id)) continue;
		const value = segment.event.durationMs ?? 0;
		rows[segment.row].push({
			id: segment.event.id,
			left: segment.left,
			width: segment.width,
			tooltip: `[${roleLabel(roleOf(segment.event))}] ${segment.event.title} · ${formatDuration(value)}`,
		});
	}

	const stream = useMemo(
		() => filtered.visible.slice().sort((a, b) => a.startedAt - b.startedAt || a.id - b.id),
		[filtered]
	);

	// Auto-scroll: stick to the newest entry while streaming. Scrolling up pauses the stick;
	// scrolling back to the bottom (or a fresh turn) resumes it.
	useEffect(() => {
		if (stream.length === lastStreamEnd.current) return;
		if (stream.length < lastStreamEnd.current) setStick(true);
		lastStreamEnd.current = stream.length;
		if (stickRef.current) {
			scrollerRef.current?.scrollTo({ top: scrollerRef.current.scrollHeight });
		}
	}, [stream.length]);

	useEffect(() => {
		if (!stick) return;
		scrollerRef.current?.scrollTo({ top: scrollerRef.current.scrollHeight });
	}, [stick]);

	const keepStick = () => {
		const el = scrollerRef.current;
		if (!el) return;
		setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 24);
	};

	return (
		<div className="flex min-h-0 flex-1 flex-col">
			{/* Toolbar: summary chips + search, like the header of the screenshot. */}
			<div className="flex flex-wrap items-center gap-x-2 gap-y-1.5 border-b border-hairline bg-surface px-3 py-2">
				<div className="flex items-center gap-1.5">
					<span className="rounded-xs bg-white px-1.5 py-px font-sans text-[10px] font-medium text-canvas"
						title="Total time of this trace">
						Duration {formatDuration(statsBox.durationMs)}
					</span>
					<span className="rounded-xs border border-hairline bg-card px-1.5 py-px font-sans text-[10px] font-medium text-body-muted">
						Turns {statsBox.turns}
					</span>
					<span className="rounded-xs border border-hairline bg-card px-1.5 py-px font-sans text-[10px] font-medium text-body-muted">
						Calls {statsBox.calls}
					</span>
				</div>
				<label className="relative ml-auto w-40 min-w-32">
					<SearchIcon className="pointer-events-none absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted" />
					<input
						type="search"
						value={query}
						onChange={(changeEvent) => setQuery(changeEvent.target.value)}
						placeholder="Search"
						className="w-full rounded-sm border border-hairline bg-card py-1 pl-7 pr-2 font-sans text-[11.5px] text-ink outline-none placeholder:text-muted-soft focus:border-accent-border"
					/>
				</label>
			</div>

			{events.length === 0 ? (
				<div className="mt-10 px-6 text-center">
					<p className="font-sans text-[15px] leading-relaxed text-body-muted">
						No chat turn has run yet.
					</p>
					<p className="mt-2 font-sans text-[12px] leading-relaxed text-muted">
						Send a message and the trajectory shows the timeline (Input · Model · Tools) plus the
						full role stream of the turn.
					</p>
				</div>
			) : (
				<div
					ref={scrollerRef}
					onScroll={keepStick}
					className="scrollbar-thin relative min-h-0 flex-1 overflow-y-auto"
				>
					{/* Gantt timeline across the trace. */}
					<div className="border-b border-hairline bg-surface px-3 py-3">
						<div className="space-y-1.5">
							{(Object.keys(rows) as Array<keyof typeof rows>).map((key) => (
								<TimelineRow
									key={key}
									label={ROW_STYLE[key].label}
									hint={ROW_STYLE[key].hint}
									bar={ROW_STYLE[key].bar}
									segments={rows[key]}
								/>
							))}
						</div>
						<p className="mt-2 flex justify-between font-mono text-[9.5px] text-muted">
							<span>trace start</span>
							<span>{formatDuration(window.totalMs)}</span>
						</p>
					</div>

					{/* Role stream. */}
					<div className="px-3 py-2">
						<p className="mb-1 border-b-2 border-white/25 pb-1.5 font-sans text-[11px] font-semibold text-body-muted">
							Turn 1
						</p>
						<ul>
							{stream.map((event) => (
								<StreamEntry key={event.id} event={event} />
							))}
						</ul>
						{stream.length === 0 && (
							<p className="py-6 text-center font-sans text-[12px] text-muted">
								No entry matches “{query.trim()}”.
							</p>
						)}
					</div>
					{!stick && (
						<button
							type="button"
							onClick={() => setStick(true)}
							className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-xs border border-hairline bg-card px-2.5 py-1 font-sans text-[11px] text-ink shadow-sm transition-colors hover:bg-soft-fill"
						>
							Jump to bottom ↓
						</button>
					)}
				</div>
			)}

			{error && (
				<div className="mx-3 mb-3 rounded-sm border border-danger-border bg-danger-soft p-2.5 font-sans text-[12px] leading-relaxed text-danger">
					{error}
				</div>
			)}
		</div>
	);
}
