import { KeyboardEvent, useEffect, useRef, useState } from "react";
import type { FreeLlmModel } from "../../lib/provider";

interface InquiryComposerProps {
	value: string;
	onChange: (value: string) => void;
	onSend: () => void;
	busy: boolean;
	modelLabel: string;
	live: boolean;
	models: FreeLlmModel[];
	onSelectModel: (id: string) => void;
	onOpenSettings: () => void;
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
	models,
	onSelectModel,
	onOpenSettings,
}: InquiryComposerProps) {
	const textareaRef = useRef<HTMLTextAreaElement>(null);
	const [pickerOpen, setPickerOpen] = useState(false);
	const [query, setQuery] = useState("");
	const pickerRef = useRef<HTMLDivElement>(null);

	// Model switching belongs to the next request: while streaming there is nothing to change.
	const canPickModel = !busy && !live;

	useEffect(() => {
		if (!pickerOpen) return;
		const onMouseDown = (event: MouseEvent) => {
			if (pickerRef.current && !pickerRef.current.contains(event.target as Node)) {
				setPickerOpen(false);
				setQuery("");
			}
		};
		const onKey = (event: globalThis.KeyboardEvent) => {
			if (event.key === "Escape") {
				setPickerOpen(false);
				setQuery("");
			}
		};
		document.addEventListener("mousedown", onMouseDown);
		document.addEventListener("keydown", onKey);
		return () => {
			document.removeEventListener("mousedown", onMouseDown);
			document.removeEventListener("keydown", onKey);
		};
	}, [pickerOpen]);

	const selected = models.find((model) => model.id === modelLabel) ?? null;

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

	const matches = (() => {
		const needle = query.trim().toLowerCase();
		if (!needle) return models;
		return models.filter(
			(model) => model.id.toLowerCase().includes(needle) || model.name.toLowerCase().includes(needle)
		);
	})();

	const contextLabel = selected
		? `${Math.round(selected.contextTokens / 1024)}K`
		: "—";

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
						<div className="relative flex items-center gap-3">
							<button
								className="flex items-center justify-center p-1 text-base font-medium leading-none text-fg-faint hover:text-fg"
								title="Đính kèm tệp tin"
							>
								+
							</button>
							<span className="h-3 w-px bg-edge" aria-hidden="true" />
							<div ref={pickerRef} className="relative">
								<button
									type="button"
									onClick={() => canPickModel && setPickerOpen((open) => !open)}
									disabled={!canPickModel}
									title={canPickModel ? "Chọn model LLM miễn phí (OpenRouter)" : "Model áp dụng từ lượt chạy kế tiếp"}
									className={`flex items-center gap-1.5 py-0.5 ${
										canPickModel ? "cursor-pointer text-fg-mute hover:text-fg" : "cursor-not-allowed text-fg-faint"
									}`}
								>
									<span className="text-[11px] font-medium tracking-tight">{modelLabel || "Model"}</span>
									<span className="text-[9px]">{pickerOpen ? "^" : "⌄"}</span>
								</button>

								{pickerOpen ? (
									<div className="absolute bottom-full left-0 z-30 mb-2 w-96 border border-edge bg-surface-raised shadow-lg">
										<input
											type="text"
											value={query}
											autoFocus
											onChange={(event) => setQuery(event.target.value)}
											placeholder="Tìm model miễn phí..."
											className="w-full border-b border-edge bg-canvas px-3 py-2 text-xs text-fg placeholder:text-fg-faint focus:outline-none"
										/>
										<div className="scrollbar-thin max-h-64 overflow-y-auto">
											{matches.length === 0 ? (
												<div className="px-3 py-3 font-mono text-[10px] text-fg-faint">Không có model nào khớp.</div>
											) : (
												matches.map((model) => {
													const isCurrent = model.id === modelLabel;
													return (
														<button
															key={model.id}
															type="button"
															onClick={() => {
																onSelectModel(model.id);
																setPickerOpen(false);
																setQuery("");
															}}
															className={`flex w-full cursor-pointer items-center gap-3 border-b border-edge-soft px-3 py-2 text-left transition-colors hover:bg-canvas ${
																isCurrent ? "bg-canvas" : ""
															}`}
														>
															<span className={`text-[10px] ${isCurrent ? "text-accent" : "text-fg-faint"}`}>
																{isCurrent ? "●" : "○"}
															</span>
															<span className="flex min-w-0 flex-col">
																<span
																	className={`truncate text-[11px] ${isCurrent ? "font-semibold text-fg" : "text-fg-dim"}`}
																>
																	{model.name}
																</span>
																<span className="truncate text-[10px] leading-snug text-fg-faint">{model.id}</span>
															</span>
															<span className="ml-auto shrink-0 font-mono text-[10px] text-fg-faint">
																{Math.round(model.contextTokens / 1024)}K
															</span>
														</button>
													);
												})
											)}
										</div>
										<button
											type="button"
											onClick={() => {
												setPickerOpen(false);
												setQuery("");
												onOpenSettings();
											}}
											className="w-full cursor-pointer px-3 py-2 text-left font-mono text-[10px] uppercase tracking-wider text-fg-mute transition-colors hover:bg-canvas hover:text-fg"
										>
											⊕ Connect provider (⚙ Settings)
										</button>
									</div>
								) : null}
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
					<span>TOKEN CONTEXT: {contextLabel}</span>
				</div>
			</div>
		</div>
	);
}
