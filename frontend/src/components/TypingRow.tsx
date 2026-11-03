export default function TypingRow() {
	return (
		<div
			className="flex animate-rise items-end gap-2.5"
			role="status"
			aria-label="The assistant is replying"
		>
			<div className="grid h-8 w-8 shrink-0 place-items-center rounded-[10px] bg-gradient-to-br from-accent-500 to-violet-600 text-[10px] font-extrabold text-white">
				AI
			</div>
			<div className="rounded-2xl rounded-bl-md border border-slate-200/90 bg-white px-4 py-4 shadow-[0_18px_40px_-32px_rgba(15,23,42,0.55)]">
				<div className="flex items-center gap-1.5">
					{[0, 1, 2].map((i) => (
						<span
							key={i}
							className="h-1.5 w-1.5 animate-blink rounded-full bg-accent-400"
							style={{ animationDelay: `${i * 0.15}s` }}
						/>
					))}
				</div>
			</div>
		</div>
	);
}
