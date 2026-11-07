export type WorkspaceView = "synthesis" | "parallel" | "trace";

const VIEWS: { key: WorkspaceView; label: string; wideOnly?: boolean }[] = [
	{ key: "synthesis", label: "Synthesis (User View)" },
	{ key: "parallel", label: "Parallel Ledger", wideOnly: true },
	{ key: "trace", label: "Execution Trace (Inspector)" },
];

const THEME_LABEL: Record<"light" | "dark", string> = {
	light: "Light",
	dark: "Dark",
};

interface WorkspaceHeaderProps {
	view: WorkspaceView;
	onViewChange: (view: WorkspaceView) => void;
	theme: "light" | "dark";
	onToggleTheme: () => void;
	traceId: number | null;
	onNewSession: () => void;
}

/** Top architectural header: brand, trace id, view tabs, theme and session controls. */
export default function WorkspaceHeader({
	view,
	onViewChange,
	theme,
	onToggleTheme,
	traceId,
	onNewSession,
}: WorkspaceHeaderProps) {
	return (
		<header className="z-20 flex h-12 shrink-0 items-center justify-between border-b border-edge px-4 bg-canvas lg:px-8">
			<div className="flex items-center gap-6">
				<div className="flex items-baseline gap-3">
					<span className="text-xs font-bold uppercase tracking-[0.2em] text-fg">PANIC</span>
					<span className="hidden uppercase tracking-[0.15em] text-fg-faint text-[10px] sm:inline">
						Orchestrated Agent Workspace
					</span>
				</div>
				<span className="h-3 w-px bg-edge" aria-hidden="true" />
				<div className="font-mono text-[11px] text-fg-faint">
					TRACE <span className="text-fg">{traceId ? `#${traceId}` : "—"}</span>
				</div>
			</div>

			<div className="flex items-center gap-5 text-xs lg:gap-8">
				{VIEWS.map((entry) => {
					const active = entry.key === view;
					return (
						<button
							key={entry.key}
							onClick={() => onViewChange(entry.key)}
							className={`h-12 tracking-[0.05em] uppercase transition-colors ${
								entry.wideOnly ? "hidden md:block " : ""
							}${
								active
									? "font-semibold text-fg"
									: "font-normal text-fg-faint hover:text-fg"
							}`}
						>
							{entry.label}
						</button>
					);
				})}
			</div>

			<div className="flex items-center gap-4 text-xs">
				<button
					onClick={onToggleTheme}
					className="flex items-center gap-1.5 font-mono uppercase tracking-[0.1em] text-fg-faint text-[11px] hover:text-fg"
				>
					{THEME_LABEL[theme]}
				</button>
				<span className="h-3 w-px bg-edge" aria-hidden="true" />
				<button
					onClick={onNewSession}
					className="whitespace-nowrap font-medium tracking-[0.05em] text-accent hover:underline"
				>
					+ New Session
				</button>
			</div>
		</header>
	);
}
