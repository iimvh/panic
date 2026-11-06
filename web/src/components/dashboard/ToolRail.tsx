import type { ToolCard } from "../../lib/dashboard";
import { Panel, SegmentBar } from "./Panel";

const statusWord: Record<ToolCard["status"], string> = {
	idle: "standby",
	running: "running",
	ok: "returned",
	error: "failed",
};

/** One tool/worker card: name, action line, block bar, status word. */
function ToolRailCard({ card }: { card: ToolCard }) {
	return (
		<div className="border border-edge bg-panel px-3 py-3">
			<div className="flex items-center justify-between gap-2">
				<span className={`truncate text-[13px] ${card.status === "error" ? "text-red" : "text-fg"}`}>
					{card.title}
				</span>
				<span
					className={`micro-label shrink-0 ${card.status === "running" ? "text-green" : card.status === "error" ? "text-red" : "text-fg-faint"}`}
				>
					{statusWord[card.status]}
				</span>
			</div>
			{card.subtitle && <p className="mt-1 truncate text-[11px] text-fg-dim">{card.subtitle}</p>}
			<div className="mt-2.5">
				<SegmentBar
					done={card.done}
					total={Math.max(8, Math.min(card.blocks, 14))}
					tint={card.status === "error" ? "red" : "blue"}
				/>
			</div>
		</div>
	);
}

/** Left rail: one card per tool source / sub-agent lane of the live turn. */
export default function ToolRail({ cards }: { cards: ToolCard[] }) {
	return (
		<Panel title="tool lanes" className="flex-1" bodyClassName="scrollbar-thin overflow-y-auto p-2 space-y-2">
			{cards.length === 0 ? (
				<p className="px-1 py-6 text-center text-[11px] text-fg-faint">
					No tool lane yet. Send a task to watch calls return.
				</p>
			) : (
				cards.map((card) => <ToolRailCard key={card.key} card={card} />)
			)}
		</Panel>
	);
}
