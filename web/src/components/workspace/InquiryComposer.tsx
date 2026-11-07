import { KeyboardEvent, useRef } from "react";

interface InquiryComposerProps {
	value: string;
	onChange: (value: string) => void;
	onSend: () => void;
	busy: boolean;
	modelLabel: string;
	live: boolean;
}

/**
 * Bottom input dock of the synthesis view: a floating hairline field with the
 * model selector on the left and the execution control on the right. The section
 * has no top border — negative space (Ma) separates it from the register.
 */
export default function InquiryComposer({
	value,
	onChange,
	onSend,
	busy,
	modelLabel,
	live,
}: InquiryComposerProps) {
	const textareaRef = useRef<HTMLTextAreaElement>(null);

	const submit = () => {
		if (busy || value.trim() === "") return;
		onSend();
	};

	const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
		if (event.key === "Enter" && !event.shiftKey) {
			event.preventDefault();
			submit();
		}
	};

	return (
		<div className="z-10 shrink-0 px-6 pb-6 pt-2 bg-canvas lg:px-16">
			<div className="mx-auto max-w-3xl">
				<div className="border border-edge bg-surface-raised p-3 transition-colors focus-within:border-accent dark:border-edge dark:bg-surface-raised">
					<textarea
						ref={textareaRef}
						rows={2}
						value={value}
						onChange={(event) => onChange(event.target.value)}
						onKeyDown={onKeyDown}
						placeholder="Nhập chỉ thị khảo sát hoặc yêu cầu phân tích tiếp theo cho Agent..."
						className="w-full resize-none bg-transparent text-sm leading-relaxed text-fg focus:outline-none"
					/>

					<div className="mt-1 flex items-center justify-between border-t border-edge-soft pt-2 font-mono text-xs">
						<div className="flex items-center gap-3">
							<button
								className="flex items-center justify-center p-1 text-base font-medium leading-none text-fg-faint hover:text-fg"
								title="Đính kèm tệp tin"
							>
								+
							</button>
							<span className="h-3 w-px bg-edge" aria-hidden="true" />
							<div className="flex cursor-pointer items-center gap-1.5 py-0.5 text-fg-mute hover:text-fg">
								<span className="text-[11px] font-medium tracking-tight">
									{modelLabel || "Model"}
								</span>
								<span className="text-[9px] text-fg-faint">^</span>
							</div>
						</div>

						<div className="flex items-center gap-3">
							<span className="hidden tracking-wider text-fg-faint uppercase sm:inline text-[10px]">
								{live ? "Streaming" : "Ready"}
							</span>
							<button
								onClick={submit}
								disabled={busy || value.trim() === ""}
								className={`flex h-7 w-7 items-center justify-center transition-colors ${
									busy || value.trim() === ""
										? "cursor-not-allowed bg-edge text-fg-faint"
										: "bg-fg text-canvas hover:bg-accent hover:text-white"
								}`}
								title="Thực thi chỉ thị (Enter)"
							>
								<span className="text-xs font-bold leading-none">→</span>
							</button>
						</div>
					</div>
				</div>

				<div className="mt-2 flex items-center justify-between px-1 font-mono text-[10px] text-fg-faint">
					<span>SHIFT + ENTER for newline</span>
					<span>TOKEN CONTEXT: 128K</span>
				</div>
			</div>
		</div>
	);
}
