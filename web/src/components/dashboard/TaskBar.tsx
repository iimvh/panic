/** Command strip back to the wireframe shape: task input + run task. */
export default function TaskBar({
	value,
	onChange,
	onSend,
	busy,
}: {
	value: string;
	onChange: (next: string) => void;
	onSend: () => void;
	busy: boolean;
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
		</div>
	);
}
