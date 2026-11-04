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
		el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
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
		<footer className="shrink-0 border-t border-hairline bg-surface px-4 pb-[calc(14px+env(safe-area-inset-bottom))] pt-4 sm:px-8">
			<form
				onSubmit={submit}
				className={`mx-auto flex w-full max-w-3xl items-end gap-2 rounded-md border bg-card py-1.5 pl-4 pr-1.5 transition-shadow ${
					focused
						? "border-accent-border shadow-[0_0_0_2px_rgba(123,132,255,0.15),0px_4px_24px_0px_rgba(0,0,0,0.4)]"
						: "border-hairline"
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
					className="max-h-40 min-w-0 flex-1 resize-none bg-transparent py-2.5 font-sans text-[15px] leading-[1.4] text-ink outline-none placeholder:text-muted-soft"
				/>
				<button
					type="submit"
					disabled={busy || !draft.trim()}
					aria-label="Send message"
					title="Send"
					className="btn-primary h-10 w-10 shrink-0 !px-0"
				>
					<SendIcon className="h-[18px] w-[18px]" />
				</button>
			</form>
			<p className="mx-auto mt-2 hidden max-w-3xl text-right font-sans text-[12px] text-muted-soft sm:block">
				<kbd className="rounded-xs border border-hairline bg-surface-alt px-1.5 py-px font-sans text-[11px] text-body-muted">
					Enter
				</kbd>{" "}
				to send ·{" "}
				<kbd className="rounded-xs border border-hairline bg-surface-alt px-1.5 py-px font-sans text-[11px] text-body-muted">
					Shift
				</kbd>{" "}
				+{" "}
				<kbd className="rounded-xs border border-hairline bg-surface-alt px-1.5 py-px font-sans text-[11px] text-body-muted">
					Enter
				</kbd>{" "}
				for a new line
			</p>
		</footer>
	);
}
