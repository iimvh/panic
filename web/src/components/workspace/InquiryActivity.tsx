import { useEffect, useRef, useState } from "react";
import { formatMs, type ActivityStep, type WorkStatus } from "../../lib/dashboard";

interface InquiryActivityProps {
	steps: ActivityStep[];
	/** The run currently streams through this feed. */
	live: boolean;
	/** A token of the streamed answer arrived — the synthesis stage has been reached. */
	answerStarted: boolean;
	/** Total trace duration once closed; completes the collapsed summary. */
	totalMs: number | null;
	/** Wall-clock the turn started; the running summary ticks its elapsed from here. */
	sinceMs: number | null;
	/** Identity of the turn behind the steps; changing it resets open/closed state. */
	traceKey: string | null;
	/** `pre` feeds sit over the finding (auto-fold when the text starts); `post` ones trail it. */
	variant?: "pre" | "post";
}

/** Current wall-clock while `active`, freezing at the last value otherwise. */
function useTicker(active: boolean): number {
	const [now, setNow] = useState(() => Date.now());
	useEffect(() => {
		if (!active) return;
		const id = window.setInterval(() => setNow(Date.now()), 250);
		return () => window.clearInterval(id);
	}, [active]);
	return now;
}

/** Live elapsed readout, ticking every 250 ms while the step is still running. */
function Elapsed({ startedAt, settledMs }: { startedAt: number; settledMs: number | null }) {
	const now = useTicker(settledMs == null);
	if (settledMs != null) return <>{formatMs(settledMs)}</>;
	return <>{`${(Math.max(0, now - startedAt) / 1000).toFixed(1)}s`}</>;
}

function StepDot({ status }: { status: WorkStatus }) {
	if (status === "running") {
		return <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 animate-cursor-blink bg-accent" />;
	}
	if (status === "error") {
		return <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 bg-red-600" />;
	}
	if (status === "idle") {
		return <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 bg-fg-mute" />;
	}
	return <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 bg-fg" />;
}

function Chevron({ open }: { open: boolean }) {
	return (
		<svg
			aria-hidden="true"
			viewBox="0 0 12 12"
			className={`h-3 w-3 shrink-0 text-fg-faint transition-transform duration-150 ${open ? "rotate-90" : ""}`}
		>
			<path d="M4 2l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.2" />
		</svg>
	);
}

/**
 * ChatGPT-style inline activity: action rows surface one by one while the run is
 * in flight (the running row pulses via gradient-clipped text plus a live elapsed
 * ticker), and the pre-answer feed folds into a one-line summary the moment the
 * answer starts typing — unless the user opened it manually during the run.
 */
export default function InquiryActivity({
	steps,
	live,
	answerStarted,
	totalMs,
	sinceMs,
	traceKey,
	variant = "pre",
}: InquiryActivityProps) {
	const [expanded, setExpanded] = useState(live);
	/** Set once the user touches the feed during a run; that run stays as-is afterwards. */
	const userToggledRef = useRef(false);

	useEffect(() => {
		setExpanded(live);
		userToggledRef.current = false;
	}, [live, traceKey]);

	useEffect(() => {
		if (variant === "pre" && answerStarted && !userToggledRef.current) setExpanded(false);
	}, [answerStarted, variant]);

	// Hooks run unconditionally regardless of how many events arrived yet; the
	// skeleton-empty state differs only in what is rendered.
	const runningStep = steps.find((step) => step.status === "running") ?? null;
	const toolCount = steps.filter((step) => step.phase === "tool").length;
	const hasError = steps.some((step) => step.status === "error");
	const now = useTicker(live && runningStep != null);

	if (steps.length === 0) {
		if (variant === "pre" && live) {
			// Connecting skeleton: shimmering placeholder text until the first event lands.
			return (
				<section aria-label="Run activity">
					<div className="flex items-center gap-3 py-2 font-mono text-[11px] text-fg-faint">
						<span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 animate-cursor-blink bg-accent" />
						<span className="shimmer-text animate-shimmer-text">Contacting orchestrator…</span>
					</div>
				</section>
			);
		}
		return null;
	}

	const workingSince = sinceMs ?? steps[0]?.startedAt ?? null;
	const toolSuffix = toolCount > 0 ? ` · ${toolCount} TOOLS` : "";

	// The single ChatGPT-style line: WORKING n.s while live steps run (shimmer),
	// FAILED in red after a failure, WORKED n.s once the run settled.
	let summary: string;
	let summaryClass: string;
	if (runningStep && workingSince != null) {
		summary = `WORKING ${((now - workingSince) / 1000).toFixed(1).padStart(4, "0")}s${toolSuffix}`;
		summaryClass = "shimmer-text animate-shimmer-text";
	} else if (hasError) {
		summary = `FAILED${toolSuffix}`;
		summaryClass = "text-red-600";
	} else {
		summary = `WORKED${totalMs != null ? ` ${formatMs(totalMs)}` : ""}${toolSuffix}`;
		summaryClass = "text-fg-faint";
	}

	const toggle = () => {
		userToggledRef.current = true;
		setExpanded((current) => !current);
	};

	return (
		<section aria-label="Run activity">
			<button type="button" onClick={toggle} className="flex items-center gap-2 py-1.5 text-left">
				<Chevron open={expanded} />
				<span
					className={`font-mono text-[11px] uppercase tracking-[0.08em] tabular-nums ${summaryClass}`}
				>
					{summary}
				</span>
			</button>

			{expanded ? (
				<ul className="scrollbar-thin max-h-56 overflow-y-auto pr-1">
					{steps.map((step) => (
						<li
							key={step.id}
							className="flex animate-rise items-center gap-3 py-1.5 font-mono text-[11px] leading-none"
						>
							<StepDot status={step.status} />
							<span
								className={`flex-1 truncate ${
									step.status === "running"
										? "shimmer-text animate-shimmer-text"
										: step.status === "error"
											? "text-red-600"
											: "text-fg-dim"
								}`}
							>
								{step.label}
							</span>
							{step.status === "running" ? (
								<Elapsed startedAt={step.startedAt} settledMs={step.durationMs} />
							) : step.status === "error" ? (
								<span className="shrink-0 text-red-600">
									{step.durationMs != null ? formatMs(step.durationMs) : "failed"}
								</span>
							) : (
								<span className="shrink-0 tabular-nums text-fg-faint">{step.hint}</span>
							)}
						</li>
					))}
				</ul>
			) : null}
		</section>
	);
}
