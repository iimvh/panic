import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ConversationSummary } from "../../lib/conversations";
import type { Turn } from "../../lib/storage";
import { DEFAULT_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH, MIN_SIDEBAR_WIDTH } from "../../lib/storage";

/** One sidebar record: server title (fallback: latest cached task) plus its activity stamp. */
export interface SessionRow {
	id: string;
	title: string;
	task: string;
	at: number;
	active: boolean;
	running: boolean;
}

function clockstamp(at: number): string {
	if (!at) return "";
	const date = new Date(at);
	const now = new Date();
	if (date.toDateString() !== now.toDateString()) {
		const yesterday = new Date(now);
		yesterday.setDate(now.getDate() - 1);
		if (date.toDateString() === yesterday.toDateString()) return "Yesterday";
		return date.toLocaleDateString(undefined, { day: "2-digit", month: "2-digit" });
	}
	return date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

const ACTIVE_MS_WINDOW = 12 * 60 * 60 * 1000;
const TITLE_FALLBACK = "Untitled inquiry";

function rowsFor(
	sessions: ConversationSummary[],
	taskCache: Record<string, Turn[]>,
	activeId: string | undefined,
	runningId: string | undefined
): SessionRow[] {
	return sessions
		.map((session) => {
			const turns = taskCache[session.id] ?? [];
			const lastTurn = turns[turns.length - 1];
			return {
				id: session.id,
				title: session.title ?? lastTurn?.task.split("\n")[0] ?? TITLE_FALLBACK,
				task: lastTurn?.task ?? "",
				at: session.lastActivityAt || lastTurn?.at || 0,
				active: session.id === activeId,
				running: session.id === runningId,
			};
		})
		.sort((a, b) => b.at - a.at);
}

interface SessionDirectoryProps {
	sessions: ConversationSummary[];
	taskCache: Record<string, Turn[]>;
	activeId: string | undefined;
	runningId: string | undefined;
	agentCount: number;
	onSelect: (id: string) => void;
	/** Removes the session from the server; absent while the store cannot forget. */
	onDelete?: (id: string) => void;
	/** Opens the floating Settings (provider connection) panel. */
	onOpenSettings?: () => void;
	/** Highlighted while the settings panel sits above the footer. */
	settingsOpen?: boolean;
	/** Current width in px; falls back to the fixed default while absent. */
	width?: number;
	/** Compact rail state; the rail only offers expanding again. */
	collapsed?: boolean;
	onToggleCollapsed?: () => void;
	/** Live width updates while the user drags the right edge. */
	onWidthChange?: (width: number) => void;
}

/** Sessions directory: filter input, hairline-rowed inquiry list and the agent footer. */
export default function SessionDirectory({
	sessions,
	taskCache,
	activeId,
	runningId,
	agentCount,
	onSelect,
	onDelete,
	onOpenSettings,
	settingsOpen,
	width,
	collapsed,
	onToggleCollapsed,
	onWidthChange,
}: SessionDirectoryProps) {
	const [query, setQuery] = useState("");
	/** Row showing the red DELETE? strip; any other interaction folds it back. */
	const [confirmingId, setConfirmingId] = useState<string | null>(null);
	const asideRef = useRef<HTMLElement>(null);

	// Drag the right hairline to re-width the directory; the width follows the cursor live.
	const startResize = useCallback(
		(downEvent: React.MouseEvent) => {
			downEvent.preventDefault();
			const asideLeft = asideRef.current?.getBoundingClientRect().left ?? 0;
			const onMove = (moveEvent: MouseEvent) => {
				const next = Math.min(MAX_SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH, moveEvent.clientX - asideLeft));
				onWidthChange?.(next);
			};
			const onUp = () => {
				document.removeEventListener("mousemove", onMove);
				document.removeEventListener("mouseup", onUp);
			};
			document.addEventListener("mousemove", onMove);
			document.addEventListener("mouseup", onUp);
		},
		[onWidthChange]
	);

	// Selecting a row clears any focus residue in the rail; nothing else transactional.
	useEffect(() => {
		if (!collapsed) setConfirmingId(null);
	}, [collapsed]);

	const rows = useMemo(
		() => rowsFor(sessions, taskCache, activeId, runningId),
		[sessions, taskCache, activeId, runningId]
	);

	const filtered = useMemo(() => {
		const needle = query.trim().toLowerCase();
		if (!needle) return rows;
		return rows.filter(
			(row) => row.title.toLowerCase().includes(needle) || row.task.toLowerCase().includes(needle)
		);
	}, [rows, query]);

	if (collapsed) {
		return (
			<aside
				className="flex w-12 shrink-0 flex-col items-center justify-between border-r border-edge bg-surface py-4"
				aria-label="Sessions (collapsed)"
			>
				<button
					type="button"
					onClick={onToggleCollapsed}
					aria-label="Expand session directory"
					title="Expand directory"
					className="cursor-pointer rounded-none px-1.5 py-1 font-mono text-xs text-fg-faint transition-colors hover:bg-canvas hover:text-fg"
				>
					»
				</button>
				{onOpenSettings ? (
					<button
						type="button"
						onClick={onOpenSettings}
						aria-label="Open provider settings"
						className={`cursor-pointer text-xs leading-none transition-colors hover:text-fg ${
							settingsOpen ? "text-accent" : "text-fg-faint"
						}`}
					>
						⚙
					</button>
				) : null}
			</aside>
		);
	}

	return (
		<aside
			ref={asideRef}
			className="relative flex shrink-0 flex-col overflow-hidden border-r border-edge bg-surface"
			style={width ? { width } : undefined}
		>
			<div className="shrink-0 border-b border-edge p-4">
				<div className="flex items-center justify-between">
					<div className="micro-label text-fg-faint">Directory</div>
					{onToggleCollapsed ? (
						<button
							type="button"
							onClick={onToggleCollapsed}
							aria-label="Collapse session directory"
							title="Collapse directory"
							className="cursor-pointer px-1 font-mono text-xs text-fg-faint transition-colors hover:text-fg"
						>
							«
						</button>
					) : null}
				</div>
				<input
					type="text"
					value={query}
					onChange={(event) => setQuery(event.target.value)}
					placeholder="Filter records..."
					className="mt-2 w-full rounded-none border border-edge bg-canvas px-3 py-1.5 text-xs text-fg focus:border-accent focus:outline-none"
				/>
			</div>

			<div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
				{filtered.length === 0 ? (
					<div className="p-4 font-mono text-[11px] text-fg-faint">No inquiry records yet.</div>
				) : (
				filtered.map((row) =>
					row.id === confirmingId && onDelete ? (
						<div
							key={row.id}
							className="flex items-center justify-between border-b border-edge bg-canvas p-4 font-mono text-[10px] uppercase tracking-[0.08em]"
						>
							<span className="text-red-600">Delete?</span>
							<span className="flex gap-3">
								<button
									type="button"
									onClick={() => {
										setConfirmingId(null);
										onDelete(row.id);
									}}
									className="cursor-pointer font-medium text-red-600 hover:underline"
								>
									Yes
								</button>
								<button
									type="button"
									onClick={() => setConfirmingId(null)}
									className="cursor-pointer text-fg-faint transition-colors hover:text-fg"
								>
									No
								</button>
							</span>
						</div>
					) : (
						<div
							key={row.id}
							className={`group relative block w-full border-b border-edge p-4 transition-colors ${
								row.active ? "bg-canvas" : "hover:bg-canvas"
							}`}
						>
							{row.active && (
								<span className="absolute bottom-0 left-0 top-0 w-0.5 bg-accent" aria-hidden="true" />
							)}
							<button type="button" onClick={() => onSelect(row.id)} className="block w-full cursor-pointer text-left">
								<div
									className={`truncate pr-6 text-xs tracking-tight ${
										row.active ? "font-semibold text-fg" : "font-medium text-fg-dim group-hover:text-fg"
									}`}
								>
									{row.title}
								</div>
								{row.task ? (
									<div
										className={`truncate text-[11px] leading-snug text-fg-faint ${
											row.active ? "" : "group-hover:hidden"
										}`}
									>
										{row.task}
									</div>
								) : null}
								<div className="mt-2 flex items-center justify-between font-mono text-[10px] text-fg-faint">
									<span>{clockstamp(row.at)}</span>
									{row.running ? (
										<span className="flex items-center gap-1.5 font-medium text-accent">
											<span
												className="inline-block h-1.5 w-1.5 animate-cursor-blink bg-accent"
												aria-hidden="true"
											/>
											Streaming
										</span>
									) : row.active && row.at > Date.now() - ACTIVE_MS_WINDOW ? (
										<span className="font-medium text-accent">Active</span>
									) : null}
								</div>
							</button>
							{onDelete && !row.running ? (
								<button
									type="button"
									aria-label={`Delete session ${row.title}`}
									onClick={() => setConfirmingId(row.id)}
									className="absolute right-2 top-3 cursor-pointer font-mono text-[11px] text-fg-faint opacity-0 transition-opacity hover:text-red-600 focus-visible:opacity-100 group-hover:opacity-100"
								>
									✕
								</button>
							) : null}
						</div>
					)
				)
				)}
			</div>

			<div className="flex shrink-0 items-center justify-between border-t border-edge bg-surface p-3.5 font-mono text-[10px] text-fg-faint">
				<span>AGENTS: {String(agentCount).padStart(2, "0")} ONLINE</span>
				<span className="flex items-center gap-2.5">
					<span>SSE</span>
					{onOpenSettings ? (
						<button
							type="button"
							onClick={onOpenSettings}
							aria-label="Open provider settings"
							className={`cursor-pointer text-xs leading-none transition-colors hover:text-fg ${
								settingsOpen ? "text-accent" : "text-fg-faint"
							}`}
						>
							⚙
						</button>
					) : null}
				</span>
			</div>

			{onWidthChange ? (
				<div
					role="separator"
					aria-orientation="vertical"
					aria-label="Resize directory"
					onMouseDown={startResize}
					onDoubleClick={() => onWidthChange?.(DEFAULT_SIDEBAR_WIDTH)}
					className="absolute bottom-0 right-0 top-0 z-20 w-1 cursor-col-resize transition-colors hover:bg-accent/40"
				/>
			) : null}
		</aside>
	);
}
