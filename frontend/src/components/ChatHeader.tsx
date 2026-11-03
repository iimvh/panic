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
		<header className="flex items-center gap-3 border-b border-slate-200/80 bg-white/85 px-4 py-3 backdrop-blur-xl sm:px-6">
			<div className="flex min-w-0 flex-1 items-center gap-3">
				<div className="relative">
					<div className="grid h-10 w-10 place-items-center rounded-[14px] bg-gradient-to-br from-accent-500 to-violet-600 text-[11.5px] font-extrabold tracking-wide text-white shadow-glow">
						AI
					</div>
					<span className="absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full border-2 border-white bg-emerald-500" />
				</div>
				<div className="min-w-0">
					<h2 className="truncate text-[15px] font-bold text-slate-900">Meeting Room Assistant</h2>
					<p className="truncate text-[12px] text-slate-500">
						{busy ? "Processing your request…" : "Ready · search, book and cancel meetings"}
					</p>
				</div>
			</div>

			<button
				type="button"
				onClick={onTogglePipeline}
				aria-pressed={pipelineOpen}
				title="Xem pipeline agent realtime"
				className={`inline-flex h-9 items-center gap-1.5 rounded-xl border px-2.5 text-[12px] font-medium transition ${
					pipelineOpen
						? "border-accent-300 bg-accent-50 text-accent-700"
						: "border-slate-200 text-slate-500 hover:border-slate-300 hover:text-slate-800"
				}`}
			>
				<PipelineIcon className="h-4 w-4" />
				<span className="hidden sm:inline">Pipeline</span>
				{pipelineRunning > 0 && (
					<span className="grid min-w-[16px] place-items-center rounded-full bg-accent-500 px-1 text-[9.5px] font-bold leading-4 text-white">
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
				className="grid h-9 w-9 shrink-0 place-items-center rounded-xl text-slate-400 transition hover:bg-rose-50 hover:text-rose-600 disabled:pointer-events-none disabled:opacity-40"
			>
				<TrashIcon className="h-[18px] w-[18px]" />
			</button>
		</header>
	);
}
