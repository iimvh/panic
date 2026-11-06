import { PipelineIcon, TrashIcon } from "./Icons";

interface ChatHeaderProps {
	onClear: () => void;
	onTogglePipeline: () => void;
	pipelineOpen: boolean;
	pipelineRunning: number;
	hasMessages: boolean;
	busy: boolean;
}

export default function ChatHeader({
	onClear,
	onTogglePipeline,
	pipelineOpen,
	pipelineRunning,
	hasMessages,
	busy,
}: ChatHeaderProps) {
	return (
		<header className="shrink-0 border-b border-hairline bg-surface">
			<div className="mx-auto flex h-[68px] w-full max-w-shell items-center gap-6 px-5 sm:px-8">
				<div className="flex min-w-0 flex-1 items-center gap-3">
					<img
						src="/favicon.svg"
						alt=""
						aria-hidden="true"
						className="h-8 w-8 shrink-0 rounded-sm"
					/>
					<div className="min-w-0">
						<h2 className="truncate font-sans text-[15px] font-semibold leading-[1.2] text-ink">
							Panic
						</h2>
						<p className="truncate font-sans text-[12px] leading-[1.4] text-muted">
							{busy ? "Processing your request…" : "Ready · ask me anything"}
						</p>
					</div>
				</div>

				<div className="flex shrink-0 items-center gap-2">
					<a href="/ban-tin" className="btn-secondary" title="Đọc bản tin mỗi sáng">
						<span className="hidden sm:inline">Bản tin</span>
					</a>

					<button
						type="button"
						onClick={onTogglePipeline}
						aria-pressed={pipelineOpen}
						title="Xem pipeline agent realtime"
						className={pipelineOpen ? "btn-primary" : "btn-secondary"}
					>
						<PipelineIcon className="h-4 w-4" />
						<span className="hidden sm:inline">Pipeline</span>
						{pipelineRunning > 0 && (
							<span className="grid min-w-[18px] place-items-center rounded-xs bg-canvas px-1 font-sans text-[10px] font-bold leading-[16px] text-white">
								{pipelineRunning}
							</span>
						)}
					</button>

					<button
						type="button"
						onClick={onClear}
						disabled={!hasMessages}
						aria-label="Clear conversation"
						title="Clear conversation"
						className="btn-ghost h-10 w-10 !px-0"
					>
						<TrashIcon className="h-[18px] w-[18px]" />
					</button>
				</div>
			</div>
		</header>
	);
}
