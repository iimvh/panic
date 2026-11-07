export type WorkspaceState = "idle" | "in_progress" | "complete" | "failed";

const CHIP: Record<WorkspaceState, string> = {
	idle: "border-edge text-fg-faint",
	in_progress: "border-amber/50 text-amber animate-pulse-soft",
	complete: "border-green/50 text-green",
	failed: "border-red/50 text-red",
};

const CHIP_LABEL: Record<WorkspaceState, string> = {
	idle: "idle",
	in_progress: "task in progress",
	complete: "task complete",
	failed: "task failed",
};

/** Masthead: session rail toggle, brand, one-line workflow summary, status chip. */
export default function WorkspaceHeader({
	state,
	traceId,
	sidebarCollapsed,
	onToggleSidebar,
	onClear,
}: {
	state: WorkspaceState;
	traceId: number | null;
	/** Mirrors the sidebar's collapse state — a second toggle next to the sessions edge. */
	sidebarCollapsed: boolean;
	onToggleSidebar: () => void;
	onClear: () => void;
}) {
	return (
		<header className="flex shrink-0 flex-wrap items-center gap-x-6 gap-y-2 border-b border-edge bg-panel px-4 py-3">
			<button
				type="button"
				onClick={onToggleSidebar}
				title={sidebarCollapsed ? "Expand sessions" : "Collapse sessions"}
				aria-label={sidebarCollapsed ? "Expand sessions" : "Collapse sessions"}
				className="shrink-0 border border-edge px-2.5 py-1.5 font-mono text-[12px] text-fg-dim transition-colors hover:border-edge-strong hover:text-fg"
			>
				{sidebarCollapsed ? "»" : "«"}
			</button>
			<div>
				<h1 className="text-[22px] font-medium leading-tight tracking-tight text-fg">
					PANIC
					<span className="ml-4 text-[15px] text-fg-faint">MULTI-AGENT WORKSPACE</span>
				</h1>
				<p className="mt-0.5 text-[11px] text-fg-faint">
					<span className="text-fg-dim">main session writes</span>
					<span className="px-1.5">·</span>
					<span className="text-fg-dim">workers scan evidence</span>
					<span className="px-1.5">·</span>
					<span className="text-fg-dim">tools verify</span>
				</p>
			</div>
			<div className="ml-auto flex items-center gap-2.5">
				{traceId != null && (
					<span className="text-[10.5px] text-fg-faint" title="Active trace id">
						trace #{String(traceId).slice(-6)}
					</span>
				)}
				<span className={`border px-3 py-1.5 text-[11px] font-medium ${CHIP[state]}`}>{CHIP_LABEL[state]}</span>
				<button
					type="button"
					onClick={onClear}
					disabled={state === "in_progress"}
					title="Detach from this conversation; the next task starts a fresh session"
					className="shrink-0 border border-edge px-3 py-1.5 text-[11px] text-fg-dim transition-colors hover:border-edge-strong hover:text-fg disabled:pointer-events-none disabled:opacity-40"
				>
					new chat
				</button>
			</div>
		</header>
	);
}
