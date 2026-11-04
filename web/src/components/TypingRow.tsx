export default function TypingRow() {
	return (
		<div
			className="flex animate-rise items-start gap-3"
			role="status"
			aria-label="The assistant is replying"
		>
		<img
			src="/favicon.svg"
			alt=""
			aria-hidden="true"
			className="mt-0.5 h-7 w-7 shrink-0 rounded-sm"
		/>
		<div className="rounded-lg rounded-tl-xs border border-hairline bg-card px-4 py-4">
				<div className="flex items-center gap-1.5">
					{[0, 1, 2].map((i) => (
						<span
							key={i}
							className="h-1.5 w-1.5 animate-blink rounded-sm bg-muted-soft"
							style={{ animationDelay: `${i * 0.15}s` }}
						/>
					))}
				</div>
			</div>
		</div>
	);
}
