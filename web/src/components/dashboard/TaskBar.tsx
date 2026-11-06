import { useEffect, useRef, useState } from "react";
import type { Turn } from "../../lib/storage";

function stamp(at: number): string {
	const date = new Date(at);
	return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

const statusLabel: Record<Turn["status"], string> = {
	ok: "done",
	error: "failed",
	running: "running",
};

const statusClass: Record<Turn["status"], string> = {
	ok: "text-green",
	error: "text-red",
	running: "text-amber",
};

function numberAt(indexFromEnd: number, total: number): string {
	return String(total - indexFromEnd).padStart(2, "0");
}

/** Command strip: task input + run button, plus a saved-sessions popup once sessions exist. */
export default function TaskBar({
	value,
	onChange,
	onSend,
	busy,
	turns,
	activeTurnId,
	onSelectTurn,
	onDeleteTurn,
}: {
	value: string;
	onChange: (next: string) => void;
	onSend: () => void;
	busy: boolean;
	turns: Turn[];
	activeTurnId: string | null;
	onSelectTurn: (id: string) => void;
	onDeleteTurn: (id: string) => void;
}) {
	const [open, setOpen] = useState(false);
	const rootRef = useRef<HTMLDivElement | null>(null);

	// Close the popup on outside click or Escape.
	useEffect(() => {
		if (!open) return;
		const onPointerDown = (event: MouseEvent) => {
			if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
		};
		const onKeyDown = (event: KeyboardEvent) => {
			if (event.key === "Escape") setOpen(false);
		};
		document.addEventListener("mousedown", onPointerDown);
		document.addEventListener("keydown", onKeyDown);
		return () => {
			document.removeEventListener("mousedown", onPointerDown);
			document.removeEventListener("keydown", onKeyDown);
		};
	}, [open]);

	return (
		<div ref={rootRef} className="relative shrink-0 border-b border-edge bg-panel px-4 py-2.5">
			<form className="flex items-center gap-2" onSubmit={(formEvent) => formEvent.preventDefault()}>
				<span className="font-mono text-[13px] text-green">➜</span>
				<input
					type="text"
					value={value}
					onChange={(changeEvent) => onChange(changeEvent.target.value)}
					onKeyDown={(keyEvent) => {
						if (keyEvent.key === "Enter" && !keyEvent.nativeEvent.isComposing) onSend();
					}}
					placeholder="describe the task…  (find a room, book a meeting, cancel)"
					disabled={busy}
					className="min-w-0 flex-1 bg-transparent font-mono text-[12.5px] text-fg outline-none"
					aria-label="Task"
				/>
				<button
					type="submit"
					disabled={busy || value.trim().length === 0}
					className="shrink-0 border border-edge-strong px-3 py-1.5 font-mono text-[11px] text-fg transition-colors hover:border-green hover:text-green disabled:pointer-events-none disabled:opacity-40"
				>
					{busy ? "running…" : "run task"}
				</button>
			</form>

			{/* History entry point appears only once at least one session exists. */}
			{turns.length > 0 && (
				<nav className="mt-2 flex items-center" aria-label="Saved sessions">
					<button
						type="button"
						onClick={() => setOpen((current) => !current)}
						aria-expanded={open}
						title="Open saved sessions"
						className={`micro-label flex items-center gap-2 border px-2.5 py-1 transition-colors ${
							open ? "border-blue text-blue" : "border-edge text-fg-dim hover:border-edge-strong hover:text-fg"
						}`}
					>
						sessions
						<span className="font-mono text-[9.5px] leading-none tracking-normal">{String(turns.length).padStart(2, "0")}</span>
					</button>
				</nav>
			)}

			{turns.length > 0 && open && (
				<div className="absolute left-3 right-3 top-full z-30 mt-1.5 overflow-hidden border border-edge-strong bg-panel-raised shadow-[0_10px_24px_rgba(0,0,0,0.5)]">
					<ul className="scrollbar-thin max-h-72 overflow-y-auto" role="listbox" aria-label="Saved sessions">
						{[...turns].reverse().map((turn, indexFromEnd) => {
							const number = numberAt(indexFromEnd, turns.length);
							const active = turn.id === activeTurnId;
							return (
								<li key={turn.id} className="group flex items-stretch border-b border-edge last:border-b-0">
									<button
										type="button"
										onClick={() => {
											onSelectTurn(turn.id);
											setOpen(false);
										}}
										title={turn.task}
										className={`flex min-w-0 flex-1 items-baseline gap-3 px-3 py-2 text-left transition-colors hover:bg-panel-head ${
											active ? "bg-blue-soft" : ""
										}`}
									>
										<span
											className={`shrink-0 font-mono text-[10.5px] ${
												active ? "text-blue" : turn.status === "error" ? "text-red" : "text-fg-faint"
											}`}
										>
											{number}
										</span>
										<span
											className={`min-w-0 flex-1 truncate font-mono text-[11.5px] ${
												active ? "text-fg" : "text-fg-dim"
											}`}
										>
											{turn.task}
										</span>
										<span className="shrink-0 font-mono text-[10px] text-fg-faint">{stamp(turn.at)}</span>
										<span className={`micro-label shrink-0 ${statusClass[turn.status]}`}>{statusLabel[turn.status]}</span>
									</button>
									<button
										type="button"
										title="Delete this session"
										aria-label={`Delete session ${number}`}
										onClick={() => onDeleteTurn(turn.id)}
										className="shrink-0 border-l border-edge px-3 font-mono text-[11px] text-fg-faint transition-colors hover:text-red"
									>
										x
									</button>
								</li>
							);
						})}
					</ul>
					<p className="border-t border-edge px-3 py-1.5 text-[10px] text-fg-faint">
						{turns.length} saved session{turns.length > 1 ? "s" : ""} · click a row to replay it in the workspace
					</p>
				</div>
			)}
		</div>
	);
}
