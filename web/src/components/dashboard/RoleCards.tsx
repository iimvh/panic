import type { RoleCard } from "../../lib/dashboard";
import { Dot, tintFill, tintText } from "./Panel";

/**
 * One role lane: tiny label, tinted name, status dot, kicker line.
 * The dot carries the lane tint when alive (amber/blue/purple/green), red on error.
 */
function RoleLane({ card }: { card: RoleCard }) {
	return (
		<div className="flex flex-col gap-2 border border-edge bg-panel px-4 py-3">
			<div className="flex items-center justify-between gap-2">
				<span className="micro-label text-fg-faint">{card.label}</span>
				<LaneDot card={card} />
			</div>
			<span className={`truncate text-[17px] ${tintText[card.tint]}`} title={`${card.title} — ${card.subtitle}`}>
				{card.title}
			</span>
			<span className="truncate text-[11px] text-fg-faint">{card.subtitle}</span>
		</div>
	);
}

function LaneDot({ card }: { card: RoleCard }) {
	if (card.status === "idle") return <Dot status="idle" />;
	const fill = card.status === "error" ? "bg-red" : tintFill[card.tint === "dim" ? "green" : card.tint];
	return (
		<span
			aria-hidden="true"
			className={`inline-block h-[7px] w-[7px] shrink-0 rounded-full ${fill} ${
				card.status === "running" ? "animate-pulse-soft" : ""
			}`}
		/>
	);
}

/** Top lane strip with the four fixed roles: MAIN · WORKERS · TOOLS · ROUTING. */
export default function RoleCards({ roles }: { roles: RoleCard[] }) {
	return (
		<div className="grid grid-cols-2 gap-2.5 lg:grid-cols-4">
			{roles.map((card) => (
				<RoleLane key={card.key} card={card} />
			))}
		</div>
	);
}
