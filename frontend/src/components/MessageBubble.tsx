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

export default function MessageBubble({ message }: { message: Message }) {
	const isUser = message.role === "user";

	return (
		<div
			className={`flex animate-rise items-end gap-2.5 ${isUser ? "justify-end" : "justify-start"}`}
		>
			{!isUser && (
				<div
					className={`grid h-8 w-8 shrink-0 place-items-center rounded-[10px] text-[10px] font-extrabold ${
						message.role === "error"
							? "bg-rose-100 text-rose-600"
							: "bg-gradient-to-br from-accent-500 to-violet-600 text-white"
					}`}
					aria-hidden="true"
				>
					{message.role === "error" ? <ErrorIcon className="h-4 w-4" /> : "AI"}
				</div>
			)}

			<div
				className={`flex min-w-0 max-w-[min(680px,82%)] flex-col ${
					isUser ? "items-end" : "items-start"
				}`}
			>
				<div
					className={`whitespace-pre-wrap break-words px-4 py-3 ${
						isUser
							? "rounded-2xl rounded-br-md bg-gradient-to-br from-accent-600 to-violet-600 text-[14.5px] leading-relaxed text-white shadow-[0_14px_30px_-18px_rgba(79,70,229,0.9)]"
							: message.role === "error"
								? "rounded-2xl rounded-bl-md border border-rose-200 bg-rose-50 text-rose-900"
								: "rounded-2xl rounded-bl-md border border-slate-200/90 bg-white text-slate-700 shadow-[0_18px_40px_-32px_rgba(15,23,42,0.55)]"
					}`}
				>
					{isUser ? message.text : <Markdown text={message.text} />}
				</div>
				<span className="mt-1.5 px-1 text-[10.5px] text-slate-400">
					{message.role === "error" ? "Error · " : ""}
					{formatTime(message.at)}
				</span>
			</div>
		</div>
	);
}
