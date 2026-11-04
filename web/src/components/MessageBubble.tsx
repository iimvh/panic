import type { Message } from "../lib/storage";
import Markdown from "./Markdown";
import { ErrorIcon } from "./Icons";

const timeFormatter = new Intl.DateTimeFormat("vi-VN", {
	hour: "2-digit",
	minute: "2-digit",
});

function formatTime(timestamp: number): string {
	try {
		return timeFormatter.format(new Date(timestamp));
	} catch {
		return "";
	}
}

interface MessageBubbleProps {
	message: Message;
	selected?: boolean;
	/** Present when this turn recorded a pipeline; opens it in the panel. */
	onSelectPipeline?: () => void;
}

export default function MessageBubble({ message, selected, onSelectPipeline }: MessageBubbleProps) {
	const isUser = message.role === "user";

	return (
		<div
			className={`flex animate-rise items-start gap-3 ${isUser ? "flex-row-reverse" : "flex-row"}`}
		>
			{message.role === "error" ? (
				<div
					className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-sm bg-danger-soft"
					aria-hidden="true"
				>
					<ErrorIcon className="h-4 w-4 text-danger" />
				</div>
			) : isUser ? (
				<div
					className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-sm border border-hairline bg-soft-fill font-sans text-[10px] font-bold text-body-muted"
					aria-hidden="true"
				>
					You
				</div>
			) : (
				<img
					src="/favicon.svg"
					alt=""
					aria-hidden="true"
					className="mt-0.5 h-7 w-7 shrink-0 rounded-sm"
				/>
			)}

			<div
				className={`flex min-w-0 max-w-[min(680px,84%)] flex-col ${
					isUser ? "items-end" : "items-start"
				}`}
			>
				<div
					className={`break-words font-sans text-[15px] leading-[1.5] ${
						isUser
							? "rounded-lg rounded-tr-xs border border-hairline bg-surface-alt px-4 py-3 text-ink"
							: message.role === "error"
								? "rounded-lg rounded-tl-xs border border-danger-border bg-danger-soft px-4 py-3 text-ink"
								: "rounded-lg rounded-tl-xs border border-hairline bg-card px-4 py-3 text-ink"
					}`}
				>
					{isUser ? (
						<span className="whitespace-pre-wrap">{message.text}</span>
					) : (
						<Markdown text={message.text} />
					)}
				</div>
				<span className="mt-1.5 flex items-center gap-2 px-1 font-sans text-[12px] text-muted-soft">
					{message.role === "error" ? "Error · " : ""}
					{formatTime(message.at)}
					{onSelectPipeline && (
						<button
							type="button"
							onClick={onSelectPipeline}
							aria-pressed={selected}
							className={`rounded-xs px-1.5 py-px font-sans text-[12px] font-medium transition-colors ${
								selected
									? "bg-accent-soft text-accent"
									: "text-muted-soft underline decoration-transparent transition hover:text-ink hover:decoration-ink"
							}`}
						>
							Pipeline
						</button>
					)}
				</span>
			</div>
		</div>
	);
}
