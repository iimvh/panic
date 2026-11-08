import {
	AlertCircle,
	Check,
	CircleDashed,
	LoaderCircle,
} from "lucide-react";
import { cn } from "@/lib/utils";
import type { PipelineEvent } from "@/lib/pipeline";

/**
 * Renderer for one streamed pipeline frame: each AG-UI CUSTOM event named
 * `panic.pipeline` lands as a data part on the assistant message, and each
 * part renders as a single step row of the run's execution trace.
 */
export function PipelineTraceFrame({ data }: { data: PipelineEvent }) {
	const phaseLabel = data.phase.toUpperCase();

	return (
		<div
			className={cn(
				"flex items-center gap-2 px-2 py-1 font-mono text-[11px] leading-relaxed",
				data.status === "error" && "destructive",
			)}
		>
			{data.status === "running" ? (
				<LoaderCircle
					data-slot="pipeline-icon"
					aria-hidden="true"
					className="size-3 shrink-0 animate-spin text-muted-foreground"
				/>
			) : data.status === "ok" ? (
				<Check
					data-slot="pipeline-icon"
					aria-hidden="true"
					className="size-3 shrink-0 text-emerald-600 dark:text-emerald-400"
				/>
			) : (
				<AlertCircle
					data-slot="pipeline-icon"
					aria-hidden="true"
					className="size-3 shrink-0 text-destructive"
				/>
			)}
			<span className="w-14 shrink-0 uppercase tracking-wider text-muted-foreground">
				{phaseLabel}
			</span>
			<span className="min-w-0 flex-1 truncate text-foreground">
				{data.title}
			</span>
			{data.durationMs != null ? (
				<span className="shrink-0 text-muted-foreground">
					{formatDuration(data.durationMs)}
				</span>
			) : (
				<CircleDashed
					data-slot="pipeline-icon"
					aria-hidden="true"
					className="size-3 shrink-0 animate-pulse text-muted-foreground/40"
				/>
			)}
		</div>
	);
}

function formatDuration(durationMs: number): string {
	if (durationMs < 1000) return `${durationMs}ms`;
	return `${(durationMs / 1000).toFixed(1)}s`;
}
