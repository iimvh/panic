import type { DashboardModel } from "../../lib/dashboard";
import { Panel } from "./Panel";

/** Bottom-left: numbered tool evidence lines (positions + short summary). */
function EvidencePanel({ evidence }: { evidence: DashboardModel["evidence"] }) {
	return (
		<Panel
			title="evidence / tool return"
			className="shrink-0"
			bodyClassName="scrollbar-thin max-h-36 space-y-1.5 overflow-y-auto px-3 py-2.5 [@media(max-height:900px)]:max-h-24"
		>
			{evidence.length === 0 ? (
				<p className="py-4 text-[11px] text-fg-faint">no tool return yet</p>
			) : (
				evidence.map((row) => (
					<div key={row.key} className="flex min-w-0 items-baseline gap-2.5 font-mono text-[11.5px]">
						<span className="shrink-0 text-blue">{row.label}</span>
						<span className="min-w-0 flex-1 truncate text-fg-dim" title={row.detail}>
							{row.detail || "—"}
						</span>
					</div>
				))
			)}
			<p className="pt-1 text-[10.5px] text-fg-faint [@media(max-height:900px)]:hidden">only the positions + summaries that matter</p>
		</Panel>
	);
}

/** Bottom-right: last closed tool output + the review chain of the workspace. */
function AuditPanel({ audit }: { audit: string | null }) {
	return (
		<Panel title="tool result / worktree" className="shrink-0" bodyClassName="px-3 py-2.5">
			<dl className="mb-2 flex items-baseline gap-3 font-mono text-[11px]">
				<dt className="text-fg-faint">worktree</dt>
				<dd className="text-fg-dim">tree</dd>
			</dl>
			<p className="scrollbar-thin max-h-24 overflow-y-auto whitespace-pre-wrap break-words font-mono text-[11.5px] leading-relaxed text-fg [@media(max-height:900px)]:max-h-16">
				{audit ?? "diff review → related checks → return"}
			</p>
		</Panel>
	);
}

/** Bottom row: evidence left, audit right. */
export default function EvidenceRow({ dashboard }: { dashboard: DashboardModel }) {
	return (
		<div className="grid grid-cols-1 gap-2.5 lg:grid-cols-2">
			<EvidencePanel evidence={dashboard.evidence} />
			<AuditPanel audit={dashboard.audit} />
		</div>
	);
}
