import { formatMs, type DashboardModel } from "../../lib/dashboard";

export type LedgerState = "idle" | "running" | "complete" | "failed";

interface ExecutionLedgerProps {
	dashboard: DashboardModel;
	state: LedgerState;
	traceId: number | null;
}

const STAGE_LABELS: { key: string; label: string }[] = [
	{ key: "request", label: "Request" },
	{ key: "routing", label: "Routing" },
	{ key: "tools", label: "Tools" },
	{ key: "answer", label: "Synthesize" },
];

const STAGE_STATUS_CLASS: Record<string, string> = {
	ready: "text-fg",
	running: "text-accent",
	failed: "text-red-600",
	pending: "text-fg-faint",
};

function routingMetric(dashboard: DashboardModel): string {
	if (dashboard.workers.length === 0 && dashboard.tools.length === 0) return "DIRECT";
	const role = dashboard.roles.find((entry) => entry.key === "routing");
	return (role?.title ?? "ROUTED").toUpperCase();
}

function statusChipClass(state: LedgerState): string {
	switch (state) {
		case "running":
		case "complete":
			return "bg-edge text-fg";
		case "failed":
			return "bg-red-600 text-white";
		default:
			return "bg-edge text-fg-faint";
	}
}

function statusChipText(state: LedgerState): string {
	switch (state) {
		case "running":
			return "STATUS: RUNNING";
		case "complete":
			return "STATUS: 200 OK";
		case "failed":
			return "STATUS: ERROR";
		default:
			return "STATUS: IDLE";
	}
}

/** One numbered evidence record: worker name, timing meta and its stored detail. */
function EvidenceRecord({
	index,
	label,
	detail,
	timing,
}: {
	index: string;
	label: string;
	detail: string;
	timing: string | null;
}) {
	return (
		<div className="p-3 font-mono text-xs">
			<div className="flex items-center justify-between">
				<span className="font-semibold text-fg">
					Worker {index} · {label}
				</span>
				<span className="text-[10px] text-fg-faint">{timing}</span>
			</div>
			{detail ? (
				<div className="mt-1 whitespace-pre-wrap border-l border-accent bg-surface p-2 font-sans text-[11px] leading-relaxed text-fg-mute">
					{detail}
				</div>
			) : null}
		</div>
	);
}

/** The telemetry register: run metrics, checkpoint stages, evidence records, timeline. */
export default function ExecutionLedger({ dashboard, state, traceId }: ExecutionLedgerProps) {
	const stream = dashboard.stream.slice(-8);
	const latency =
		dashboard.trace?.status === "running"
			? "running"
			: dashboard.trace
				? formatMs(dashboard.trace.durationMs) || "—"
				: "—";

	return (
		<div className="flex h-full min-h-0 flex-col overflow-hidden border-l border-edge bg-surface">
			<div className="flex h-12 shrink-0 items-center justify-between border-b border-edge px-6 bg-canvas">
				<div className="flex items-baseline gap-2">
					<span className="micro-label text-fg">Ledger</span>
					<span className="font-mono text-xs text-fg-faint">/ Telemetry Register</span>
				</div>
				<span className={`px-2 py-0.5 font-mono text-[10px] ${statusChipClass(state)}`}>
					{statusChipText(state)}
				</span>
			</div>

			<div className="grid shrink-0 grid-cols-4 divide-x divide-edge border-b border-edge bg-canvas text-left">
				<div className="p-3">
					<div className="font-mono uppercase tracking-wider text-fg-faint text-[9px]">Latency</div>
					<div className="mt-0.5 font-mono text-xs font-semibold tabular-nums text-fg">{latency}</div>
				</div>
				<div className="p-3">
					<div className="font-mono uppercase tracking-wider text-fg-faint text-[9px]">Events</div>
					<div className="mt-0.5 font-mono text-xs font-semibold tabular-nums text-fg">
						{dashboard.progress.done}/{dashboard.progress.total}
					</div>
				</div>
				<div className="p-3">
					<div className="font-mono uppercase tracking-wider text-fg-faint text-[9px]">Workers</div>
					<div className="mt-0.5 font-mono text-xs font-semibold text-fg">
						{dashboard.workers.length} w / {dashboard.tools.length} l
					</div>
				</div>
				<div className="p-3">
					<div className="font-mono uppercase tracking-wider text-fg-faint text-[9px]">Routing</div>
					<div className="mt-0.5 truncate font-mono text-xs font-semibold text-accent">
						{routingMetric(dashboard)}
					</div>
				</div>
			</div>

			<div className="scrollbar-thin min-h-0 flex-1 space-y-6 overflow-y-auto p-6">
				<div>
					<div className="micro-label mb-2.5 text-fg-faint">Checkpoint Stages</div>
					<div className="grid grid-cols-4 divide-x divide-edge border border-edge bg-canvas text-center font-mono text-[10px]">
						{STAGE_LABELS.map((stage) => {
							const checkpoint = dashboard.checkpoints.find((entry) => entry.key === stage.key);
							const status = checkpoint?.status ?? "pending";
							const index = String(STAGE_LABELS.indexOf(stage) + 1).padStart(2, "0");
							return (
								<div key={stage.key} className={`py-2 ${STAGE_STATUS_CLASS[status]}`}>
									<span className="text-accent">{index}</span> {stage.label}
								</div>
							);
						})}
					</div>
				</div>

				{dashboard.evidence.length > 0 ? (
					<div>
						<div className="micro-label mb-2.5 flex items-center justify-between text-fg-faint">
							<span>Evidence Record</span>
							<span className="font-mono text-[9px] font-normal">
								{String(dashboard.evidence.length).padStart(2, "0")} RECORDS
							</span>
						</div>
						<div className="divide-y divide-edge border border-edge bg-canvas">
							{dashboard.evidence.map((record, index) => (
								<EvidenceRecord
									key={record.key}
									index={String(index + 1).padStart(2, "0")}
									label={record.label}
									detail={record.detail}
									timing={record.timing}
								/>
							))}
						</div>
					</div>
				) : null}

				{stream.length > 0 ? (
					<div>
						<div className="micro-label mb-2.5 flex items-center justify-between text-fg-faint">
							<span>Execution Timeline</span>
							<span className="font-mono text-[9px] font-normal">OFFSET LOG</span>
						</div>
						<div className="divide-y divide-edge border border-edge bg-canvas font-mono text-[11px]">
							{stream.map((row) => (
								<div key={row.id} className="flex items-baseline gap-3 px-3 py-2">
									<span className="tabular-nums text-fg-faint">{row.stamp}s</span>
									<span
										className={`w-24 font-semibold ${
											row.status === "error" ? "text-red-600" : "text-fg"
										}`}
									>
										{row.actor}
									</span>
									<span className="truncate text-fg-mute">{row.text}</span>
								</div>
							))}
						</div>
					</div>
				) : (
					<div className="font-mono text-[11px] text-fg-faint">
						No execution record yet — run an inquiry to populate the ledger.
					</div>
				)}
			</div>

			<div className="flex shrink-0 items-center justify-between border-t border-edge bg-canvas p-3.5 font-mono text-[10px] text-fg-faint">
				<span>TRACE HASH: {traceId ? `SHA256-${traceId}` : "—"}</span>
				<span className="truncate">{dashboard.model ?? "model pending"}</span>
			</div>
		</div>
	);
}
