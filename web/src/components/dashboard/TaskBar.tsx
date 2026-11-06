import type { Turn } from "../../lib/storage";

/** Command strip: task input + run button, followed by past turns. */
export default function TaskBar({
	value,
	onChange,
	onSend,
	busy,
	turns,
	activeTurnId,
	onSelectTurn,
}: {
	value: string;
	onChange: (next: string) => void;
	onSend: () => void;
	busy: boolean;
	turns: Turn[];
	activeTurnId: string | null;
	onSelectTurn: (id: string) => void;
}) {
	return (
		<div className="shrink-0 border-b border-edge bg-panel px-4 py-2.5">
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
			{turns.length > 0 && (
				<nav className="mt-2 flex items-center gap-1.5 overflow-x-auto" aria-label="Task history">
					<span className="micro-label shrink-0 text-fg-faint">history</span>
					{turns.map((turn, index) => (
						<button
							key={turn.id}
							type="button"
							onClick={() => onSelectTurn(turn.id)}
							title={turn.task}
							className={`shrink-0 border px-2 py-0.5 font-mono text-[10.5px] transition-colors ${
								turn.id === activeTurnId
									? "border-blue text-blue"
									: turn.status === "error"
										? "border-edge text-red hover:border-red"
										: "border-edge text-fg-faint hover:border-edge-strong hover:text-fg-dim"
							}`}
						>
							{String(index + 1).padStart(2, "0")}
						</button>
					))}
				</nav>
			)}
		</div>
	);
}
