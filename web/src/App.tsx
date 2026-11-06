import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import EventStream from "./components/dashboard/EventStream";
import EvidenceRow from "./components/dashboard/EvidenceRow";
import RoleCards from "./components/dashboard/RoleCards";
import SessionPanel from "./components/dashboard/SessionPanel";
import SideRail from "./components/dashboard/SideRail";
import TaskBar from "./components/dashboard/TaskBar";
import ToolRail from "./components/dashboard/ToolRail";
import WorkspaceHeader, { type WorkspaceState } from "./components/dashboard/WorkspaceHeader";
import { usePipelineStream } from "./hooks/usePipelineStream";
import { AgentError, askAgent, fetchAgentTree } from "./lib/api";
import { buildDashboard } from "./lib/dashboard";
import {
	loadConversationId,
	loadTurns,
	newId,
	saveConversationId,
	saveTurns,
	type Turn,
} from "./lib/storage";

const DEFAULT_WORKSPACE_STATE: WorkspaceState = "idle";

export default function App() {
	const [turns, setTurns] = useState<Turn[]>(() => loadTurns());
	const [selectedTurnId, setSelectedTurnId] = useState<string | null>(null);
	const [draft, setDraft] = useState("");
	const [busy, setBusy] = useState(false);
	const [conversationId, setConversationId] = useState<string | undefined>(() => loadConversationId());
	const [liveTask, setLiveTask] = useState("");
	const [liveTraceId, setLiveTraceId] = useState<number | null>(null);
	const [streamingText, setStreamingText] = useState("");
	const [rootAgentId, setRootAgentId] = useState<string | null>(null);

	const pipeline = usePipelineStream();
	const busyRef = useRef(false);
	/** Trace id of the turn currently running; set by onStart, read by the turn finaliser (state is stale there). */
	const runningTraceRef = useRef<number | null>(null);

	useEffect(() => {
		void fetchAgentTree().then((tree) => setRootAgentId(tree?.root ?? null));
	}, []);

	useEffect(() => {
		if (conversationId) saveConversationId(conversationId);
	}, [conversationId]);

	const finishedTurns = useMemo(
		() => turns.filter((turn) => turn.status !== "running"),
		[turns]
	);

	/** The pipeline the panels mirror: live frames while streaming, selected turn otherwise. */
	const viewEvents = useMemo(() => {
		if (busy) return pipeline.events;
		if (selectedTurnId) {
			return finishedTurns.find((turn) => turn.id === selectedTurnId)?.pipeline ?? [];
		}
		return finishedTurns[finishedTurns.length - 1]?.pipeline ?? [];
	}, [busy, pipeline.events, finishedTurns, selectedTurnId]);

	const dashboard = useMemo(() => buildDashboard(viewEvents, rootAgentId), [viewEvents, rootAgentId]);

	/** The turn the panels mirror (null while a turn runs live). */
	const viewedTurn = useMemo(() => {
		if (busy) return null;
		return (
			finishedTurns.find((turn) => turn.id === selectedTurnId) ??
			finishedTurns[finishedTurns.length - 1] ??
			null
		);
	}, [busy, finishedTurns, selectedTurnId]);

	const workspaceState: WorkspaceState = useMemo(() => {
		if (busy || pipeline.state === "connecting") return "in_progress";
		if (viewedTurn?.status === "error") return "failed";
		if (viewEvents.length === 0) return DEFAULT_WORKSPACE_STATE;
		return "complete";
	}, [busy, pipeline.state, viewEvents.length, viewedTurn]);

	/** Failure detail of the viewed turn (kept on the turn, cleared when another turn is viewed). */
	const viewedError = viewedTurn?.status === "error" ? viewedTurn.answer : null;

	const sendTask = useCallback(async () => {
		const task = draft.trim();
		if (!task || busyRef.current) return;

		busyRef.current = true;
		setBusy(true);
		setDraft("");
		setLiveTask(task);
		setLiveTraceId(null);
		setStreamingText("");
		setSelectedTurnId(null);
		pipeline.begin();

		const finish = (status: "ok" | "error", answer: string, events: Turn["pipeline"]) => {
			const turn: Turn = {
				id: newId(),
				task,
				traceId: runningTraceRef.current,
				pipeline: events,
				status,
				answer,
				at: Date.now(),
			};
			setTurns((current) => {
				const next = [...current, turn].slice(-20);
				saveTurns(next);
				return next;
			});
		};

		try {
			const result = await askAgent(
				{ message: task, conversationId },
				{
					onStart: (info) => {
						runningTraceRef.current = info.traceId;
						setLiveTraceId(info.traceId);
						if (info.conversationId) setConversationId(info.conversationId);
					},
					onPipeline: (event) => pipeline.push(event),
					onToken: (chunk) => setStreamingText((current) => current + chunk),
				}
			);
			pipeline.merge(result.pipeline, { status: "ok", durationMs: result.durationMs });
			if (result.conversationId) setConversationId(result.conversationId);
			finish("ok", result.reply, result.pipeline);
		} catch (caughtError) {
			let detail = "Something went wrong.";
			if (caughtError instanceof AgentError) {
				detail = caughtError.message;
				pipeline.merge(caughtError.pipeline, {
					status: "error",
					error: caughtError.message,
					durationMs: 0,
				});
				finish("error", detail, caughtError.pipeline);
			} else if (caughtError instanceof Error) {
				detail = caughtError.message;
				pipeline.fail(detail);
				finish("error", detail, []);
			} else {
				pipeline.fail(detail);
				finish("error", detail, []);
			}
		} finally {
			setStreamingText("");
			runningTraceRef.current = null;
			busyRef.current = false;
			setBusy(false);
		}
	}, [draft, conversationId, pipeline]);

	const handleClear = () => {
		if (busyRef.current) return;
		setTurns([]);
		saveTurns([]);
		setSelectedTurnId(null);
		pipeline.reset();
		setLiveTraceId(null);
	};

	const selectedTask = viewedTurn?.task ?? "";

	const selectedTraceId = viewedTurn?.traceId ?? null;

	const turnNumber = selectedTurnId
		? finishedTurns.findIndex((turn) => turn.id === selectedTurnId) + 1
		: finishedTurns.length + (busy ? 1 : 0);

	return (
		<div className="flex h-full w-full flex-col bg-canvas">
			<WorkspaceHeader
				state={workspaceState}
				traceId={busy ? liveTraceId : selectedTraceId}
				onClear={handleClear}
			/>

			<TaskBar
				value={draft}
				onChange={setDraft}
				onSend={() => void sendTask()}
				busy={busy}
				turns={finishedTurns}
				activeTurnId={selectedTurnId}
				onSelectTurn={(id) => setSelectedTurnId((current) => (current === id ? null : id))}
			/>

			{/* Desktop: the whole workspace fits the viewport — only panel bodies scroll.
			    Mobile: natural page scroll, panels keep natural heights. */}
			<main className="scrollbar-thin min-h-0 flex-1 overflow-y-auto lg:overflow-hidden">
				<div className="mx-auto flex max-w-[1560px] flex-col gap-2.5 p-3 lg:h-full">
					<RoleCards roles={dashboard.roles} />

					<div className="flex min-h-0 flex-col gap-2.5 lg:flex-1 lg:flex-row">
						{/* Left region: middle row + evidence + event stream, spanning under both columns. */}
						<div className="flex min-h-0 flex-col gap-2.5 lg:flex-1">
							<div className="flex min-h-0 flex-col gap-2.5 lg:flex-1 lg:flex-row">
								<div className="order-2 flex min-h-0 flex-col lg:order-1 lg:w-[248px] lg:shrink-0">
									<ToolRail cards={dashboard.tools} />
								</div>
								<div className="order-1 flex min-h-[340px] flex-col lg:order-2 lg:min-h-0 lg:flex-1">
									<SessionPanel
										lines={dashboard.session}
										agentId={rootAgentId}
										turnNumber={turnNumber > 0 ? turnNumber : null}
										live={busy}
										answer={
											busy
												? streamingText
												: viewedTurn?.answer && viewedTurn.status === "ok"
													? viewedTurn.answer
													: ""
										}
										error={viewedError}
									/>
								</div>
							</div>

							<EvidenceRow dashboard={dashboard} />
							<EventStream dashboard={dashboard} />
						</div>

						{/* Right rail runs to the bottom on desktop; stacks last on mobile. */}
						<div className="order-last flex min-h-[320px] flex-col lg:order-none lg:min-h-0 lg:w-[276px] lg:shrink-0">
							<SideRail
								llmRounds={dashboard.llmRounds}
								checkpoints={dashboard.checkpoints}
								task={busy ? liveTask : selectedTask}
								workersSummary={
									dashboard.workers.length > 0
										? `${dashboard.workers.length} worker${dashboard.workers.length > 1 ? "s" : ""} · ${dashboard.tools.length} lanes`
										: dashboard.tools.length > 0
											? `${dashboard.tools.length} tool lane${dashboard.tools.length > 1 ? "s" : ""}`
											: "just the main agent"
								}
								live={busy}
							/>
						</div>
					</div>
				</div>
			</main>
		</div>
	);
}
