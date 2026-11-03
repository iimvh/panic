import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { SendIcon } from "./Icons";

interface ComposerProps {
	busy: boolean;
	draft: string;
	onDraftChange: (value: string) => void;
	onSend: (text: string) => void;
}

export default function Composer({ busy, draft, onDraftChange, onSend }: ComposerProps) {
	const textareaRef = useRef<HTMLTextAreaElement>(null);
	const [focused, setFocused] = useState(false);

	useEffect(() => {
		const el = textareaRef.current;
		if (!el) return;
		el.style.height = "auto";
		el.style.height = `${Math.min(el.scrollHeight, 152)}px`;
	}, [draft]);

	const submit = (event?: FormEvent) => {
		event?.preventDefault();
		if (busy || !draft.trim()) return;
		onSend(draft);
	};

	const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
		if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
			event.preventDefault();
			submit();
		}
	};

	return (
		<div className="border-t border-slate-200/80 bg-white/90 px-4 pb-[calc(14px+env(safe-area-inset-bottom))] pt-3.5 backdrop-blur-xl sm:px-6">
			<form
				onSubmit={submit}
				className={`mx-auto flex max-w-3xl items-end gap-2.5 rounded-2xl border bg-slate-50/80 py-1.5 pl-4 pr-1.5 transition ${
					focused
						? "border-accent-300 bg-white shadow-[0_0_0_4px_rgba(99,102,241,0.1)]"
						: "border-slate-200"
				}`}
			>
				<textarea
					ref={textareaRef}
					value={draft}
					onChange={(event) => onDraftChange(event.target.value)}
					onKeyDown={handleKeyDown}
					onFocus={() => setFocused(true)}
					onBlur={() => setFocused(false)}
					rows={1}
					maxLength={4000}
					placeholder="Type a request, e.g. find a room for 10 people at 2:00 PM tomorrow…"
					aria-label="Message"
					className="max-h-[152px] min-w-0 flex-1 resize-none bg-transparent py-2.5 text-[14.5px] leading-relaxed text-slate-900 outline-none placeholder:text-slate-400"
				/>
				<button
					type="submit"
					disabled={busy || !draft.trim()}
					aria-label="Send message"
					title="Send"
					className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-accent-500 to-violet-600 text-white shadow-glow transition hover:brightness-110 active:scale-95 disabled:opacity-40 disabled:shadow-none"
				>
					<SendIcon className="h-[18px] w-[18px]" />
				</button>
			</form>
			<p className="mx-auto mt-2 hidden max-w-3xl text-right text-[11px] text-slate-400 sm:block">
				<kbd className="rounded border border-b-2 border-slate-200 bg-slate-100 px-1.5 py-px font-sans text-[10.5px] text-slate-500">
					Enter
				</kbd>{" "}
				to send ·{" "}
				<kbd className="rounded border border-b-2 border-slate-200 bg-slate-100 px-1.5 py-px font-sans text-[10.5px] text-slate-500">
					Shift
				</kbd>{" "}
				+{" "}
				<kbd className="rounded border border-b-2 border-slate-200 bg-slate-100 px-1.5 py-px font-sans text-[10.5px] text-slate-500">
					Enter
				</kbd>{" "}
				for a new line
			</p>
		</div>
	);
}
