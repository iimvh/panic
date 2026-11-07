import { useMemo, useState } from "react";
import type { ConversationSummary } from "../../lib/conversations";

/** "2m"-style activity stamps for the session list. */
function relativeTime(at: number): string {
	const seconds = Math.max(0, Math.floor((Date.now() - at) / 1000));
	if (seconds < 45) return "now";
	const minutes = Math.floor(seconds / 60);
	if (minutes < 60) return `${minutes}m`;
	const hours = Math.floor(minutes / 60);
	if (hours < 24) return `${hours}h`;
	const days = Math.floor(hours / 24);
	return `${days}d`;
}

function initialsOf(title: string | null): string {
	const source = (title ?? "").trim();
	if (!source) return "#";
	const parts = source.split(/\s+/).slice(0, 2);
	return parts.map((part) => part.charAt(0)).join("").slice(0, 2);
}

interface ChatSidebarProps {
	collapsed: boolean;
	sessions: ConversationSummary[];
	/** Conversation the dashboard follows; undefined = a new chat is being drafted. */
	activeId: string | undefined;
	/** Conversation with a task streaming right now (gets a live dot, no rename/delete). */
	runningId: string | undefined;
	onToggle: () => void;
	onNewChat: () => void;
	onSelect: (id: string) => void;
	onRename: (id: string, title: string) => void;
	onDelete: (id: string) => void;
}

/**
 * Session rail of the workspace: ＋ new chat, search, title + relative time, inline rename,
 * two-step delete (confirm pattern), active highlight — 256px expanded, 56px icon rail
 * collapsed. The preference is remembered; fresh visitors get width-based defaults.
 */
export default function ChatSidebar({
	collapsed,
	sessions,
	activeId,
	runningId,
	onToggle,
	onNewChat,
	onSelect,
	onRename,
	onDelete,
}: ChatSidebarProps) {
	const [search, setSearch] = useState("");
	const [renaming, setRenaming] = useState<{ id: string; title: string } | null>(null);
	const [confirmingDelete, setConfirmingDelete] = useState<string | null>(null);

	const visible = useMemo(() => {
		const needle = search.trim().toLowerCase();
		if (!needle) return sessions;
		return sessions.filter((session) => (session.title ?? "").toLowerCase().includes(needle));
	}, [sessions, search]);

	const commitRename = (id: string) => {
		if (!renaming) return;
		const title = renaming.title.trim();
		setRenaming(null);
		if (title && id) onRename(id, title);
	};

	if (collapsed) {
		return (
			<aside
				className="flex h-full w-[56px] shrink-0 flex-col items-center gap-2 border-r border-edge bg-panel py-2.5"
				aria-label="Sessions (collapsed)"
			>
				<button
					type="button"
					onClick={onToggle}
					title="Expand sessions"
					aria-label="Expand sessions"
					className="w-[38px] border border-edge px-0 py-1.5 font-mono text-[12px] text-fg-dim transition-colors hover:border-edge-strong hover:text-fg"
				>
					»
				</button>
				<button
					type="button"
					onClick={onNewChat}
					title="New chat"
					aria-label="New chat"
					className="w-[38px] border border-edge-strong py-1.5 font-mono text-[13px] text-fg transition-colors hover:border-green hover:text-green"
				>
					＋
				</button>
				<nav className="scrollbar-thin flex min-h-0 w-full flex-1 flex-col items-center gap-1 overflow-y-auto py-1">
					{sessions.map((session) => (
						<button
							key={session.id}
							type="button"
							onClick={() => onSelect(session.id)}
							title={session.title ?? "untitled session"}
							aria-label={session.title ?? "untitled session"}
							className={`w-[38px] shrink-0 border py-1 font-mono text-[11px] uppercase transition-colors ${
								session.id === activeId
									? "border-blue text-blue"
									: "border-edge text-fg-dim hover:border-edge-strong hover:text-fg"
							}`}
						>
							{initialsOf(session.title)}
						</button>
					))}
				</nav>
			</aside>
		);
	}

	return (
		<aside
			className="flex h-full w-[256px] shrink-0 flex-col border-r border-edge bg-panel"
			aria-label="Sessions"
		>
			<div className="flex items-center gap-2 border-b border-edge px-2.5 py-2">
				<span className="micro-label flex-1 text-fg-dim">sessions · {String(sessions.length).padStart(2, "0")}</span>
				<button
					type="button"
					onClick={onToggle}
					title="Collapse sessions"
					aria-label="Collapse sessions"
					className="border border-edge px-2 py-1 font-mono text-[11px] text-fg-dim transition-colors hover:border-edge-strong hover:text-fg"
				>
					«
				</button>
			</div>

			<div className="shrink-0 px-2.5 pt-2.5">
				<button
					type="button"
					onClick={onNewChat}
					title="Detach from this conversation, so the next task starts a fresh session"
					className="w-full border border-edge-strong px-3 py-2 text-left font-mono text-[12px] text-fg transition-colors hover:border-green hover:text-green"
				>
					＋ new chat
				</button>
			</div>

			<div className="shrink-0 px-2.5 py-2">
				<input
					type="text"
					value={search}
					onChange={(changeEvent) => setSearch(changeEvent.target.value)}
					placeholder="filter sessions…"
					aria-label="Filter sessions"
					className="w-full border border-edge bg-transparent px-2.5 py-1.5 font-mono text-[11.5px] text-fg outline-none placeholder:text-fg-faint focus:border-edge-strong"
				/>
			</div>

			<nav className="scrollbar-thin min-h-0 flex-1 overflow-y-auto" aria-label="Saved sessions">
				<ul>
					{visible.map((session) => {
						const active = session.id === activeId;
						const running = session.id === runningId;
						const isRenaming = renaming?.id === session.id;
						const isConfirming = confirmingDelete === session.id;
						const activity = relativeTime(session.lastActivityAt);
						return (
							<li
								key={session.id}
								className={`group border-b border-edge last:border-b-0 ${active ? "bg-blue-soft" : ""}`}
							>
								{isRenaming ? (
									<input
										autoFocus
										type="text"
										value={renaming.title}
										onChange={(changeEvent) =>
											setRenaming({ id: session.id, title: changeEvent.target.value })
										}
										onBlur={() => commitRename(session.id)}
										onKeyDown={(keyEvent) => {
											if (keyEvent.key === "Enter" && !keyEvent.nativeEvent.isComposing)
												commitRename(session.id);
											if (keyEvent.key === "Escape") setRenaming(null);
										}}
										aria-label="Session title"
										className="w-full bg-transparent px-3 py-2 font-mono text-[11.5px] text-fg outline-none"
									/>
								) : isConfirming ? (
									<div className="flex items-center gap-2 px-3 py-2">
										<span className="min-w-0 flex-1 truncate font-mono text-[11px] text-red">
											delete this session?
										</span>
										<button
											type="button"
											onClick={() => {
												setConfirmingDelete(null);
												onDelete(session.id);
											}}
											className="shrink-0 border border-red/50 px-2 py-0.5 font-mono text-[10px] text-red transition-colors hover:bg-red/10"
										>
											delete
										</button>
										<button
											type="button"
											onClick={() => setConfirmingDelete(null)}
											className="shrink-0 border border-edge px-2 py-0.5 font-mono text-[10px] text-fg-dim transition-colors hover:text-fg"
										>
											keep
										</button>
									</div>
								) : (
									<div className="flex items-stretch">
										<button
											type="button"
											onClick={() => onSelect(session.id)}
											title={session.title ?? "untitled session"}
											className={`flex min-w-0 flex-1 flex-col items-start gap-0.5 px-3 py-2 text-left transition-colors ${
												active ? "" : "hover:bg-panel-head"
											}`}
										>
											<span className="flex w-full min-w-0 items-center gap-1.5">
												{running && (
													<span
														className="h-[6px] w-[6px] shrink-0 rounded-full bg-green animate-pulse-soft"
														aria-label="task running"
													/>
												)}
												<span
													className={`min-w-0 flex-1 truncate font-mono text-[11.5px] ${
														active ? "text-fg" : "text-fg-dim"
													}`}
												>
													{session.title ?? "untitled session"}
												</span>
											</span>
											<span className="font-mono text-[10px] text-fg-faint">
												{activity === "now" ? "now" : `${activity} ago`}
											</span>
										</button>
										{!running && (
											<div className="flex shrink-0 items-center opacity-0 transition-opacity group-hover:opacity-100">
												<button
													type="button"
													title="Rename this session"
													aria-label="Rename this session"
													onClick={() =>
														setRenaming({ id: session.id, title: session.title ?? "" })
													}
													className="px-2.5 font-mono text-[11px] text-fg-faint transition-colors hover:text-blue"
												>
													✎
												</button>
												<button
													type="button"
													title="Delete this session"
													aria-label="Delete this session"
													onClick={() => setConfirmingDelete(session.id)}
													className="px-2.5 font-mono text-[11px] text-fg-faint transition-colors hover:text-red"
												>
													x
												</button>
											</div>
										)}
									</div>
								)}
							</li>
						);
					})}
					{visible.length === 0 && (
						<li className="px-3 py-6 text-center font-mono text-[11px] text-fg-faint">
							{sessions.length === 0 ? "no sessions yet" : "nothing matches the filter"}
						</li>
					)}
				</ul>
			</nav>
		</aside>
	);
}
