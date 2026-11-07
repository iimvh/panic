import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import ChatSidebar from "./components/dashboard/ChatSidebar";
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
import {
	deleteConversation,
	fetchConversation,
	listConversations,
	renameConversation,
	type ConversationSummary,
} from "./lib/conversations";
import { buildDashboard } from "./lib/dashboard";
import {
	MAX_TURNS,
	loadSidebarCollapsed,
	loadWorkspace,
	newId,
	saveSidebarCollapsed,
	saveWorkspace,
	type Turn,
	type WorkspaceStore,
} from "./lib/storage";

const DEFAULT_WORKSPACE_STATE: WorkspaceState = "idle";
const WIDE_SCREEN_PX = 1280;

export default function App() {
	const [workspace, setWorkspace] = useState<WorkspaceStore>(() => loadWorkspace());
	const [sessions, setSessions] = useState<ConversationSummary[]>([]);
	const [selectedTurnId, setSelectedTurnId] = useState<string | null>(null);
	const [draft, setDraft] = useState("");
	const [busy, setBusy] = useState(false);
	const [liveTask, setLiveTask] = useState("");
	const [liveTraceId, setLiveTraceId] = useState<number | null>(null);
	const [streamingText, setStreamingText] = useState("");
	const [rootAgentId, setRootAgentId] = useState<string | null>(null);
	const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(
		() => loadSidebarCollapsed() ?? window.innerWidth < WIDE_SCREEN_PX
	);
	/** Conversation the current run drives; the dashboard streams it until the run closes. */
	const [runningConversationId, setRunningConversationId] = useState<string | undefined>(undefined);

	const pipeline = usePipelineStream();
	const busyRef = useRef(false);
	/** Trace id of the turn currently running; set by onStart, read by the turn finaliser (state is stale there). */
	const runningTraceRef = useRef<number | null>(null);
	/** Conversation the running turn belongs to; captured from the start frame. */
	const runningConversationRef = useRef<string | undefined>(undefined);

	const activeId = workspace.activeId;

	const updateWorkspace = useCallback((mutate: (current: WorkspaceStore) => WorkspaceStore) => {
		setWorkspace((current) => {
			const next = mutate(current);
			saveWorkspace(next);
			return next;
		});
	}, []);

	const detachFromConversations = useCallback(() => {
		updateWorkspace((current) => ({ ...current, activeId: undefined }));
		setSelectedTurnId(null);
	}, [updateWorkspace]);

	const openSession = useCallback(
		async (id: string) => {
			try {
				const replay = await fetchConversation(id);
				if (!replay) {
					// Gone server-side (deleted from another browser): detach quietly.
					detachFromConversations();
					return;
				}
				const turns: Turn[] = replay.turns.map((turn) => ({
					id: newId(),
					task: turn.task,
					traceId: turn.pipeline[0]?.traceId ?? null,
					pipeline: turn.pipeline,
					status: turn.status,
					answer: turn.answer,
					at: turn.at,
				}));
				updateWorkspace((current) => ({ ...current, sessions: { ...current.sessions, [id]: turns } }));
			} catch {
				/* offline or server hiccup: the cached copy stays as the accelerant */
			}
		},
		[updateWorkspace, detachFromConversations]
	);

	const refreshSessions = useCallback(() => {
		listConversations()
			.then((rows) => setSessions(rows))
			.catch(() => {
				/* the sidebar simply keeps its last known list */
			});
	}, []);

	useEffect(() => {
		void fetchAgentTree().then((tree) => setRootAgentId(tree?.root ?? null));
		refreshSessions();
	}, [refreshSessions]);

	// Server truth wins: open on the session the last visit was in, replayed from the store.
	useEffect(() => {
		if (activeId && !busyRef.current) void openSession(activeId);
		// Only on mount: re-opening would race with the live stream of a freshly used session.
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	const turns = useMemo(
		() => (activeId ? workspace.sessions[activeId] ?? [] : []),
		[workspace, activeId]
	);

	const finishedTurns = useMemo(
		() => turns.filter((turn) => turn.status !== "running"),
		[turns]
	);

	/** The run drives this conversation; while the dashboard looks at it, panels mirror the live stream. */
	const showingLive = busy && activeId === runningConversationId;

	/** The pipeline the panels mirror: live frames while streaming this conversation, selected turn otherwise. */
	const viewEvents = useMemo(() => {
		if (showingLive) return pipeline.events;
		if (selectedTurnId) {
			return finishedTurns.find((turn) => turn.id === selectedTurnId)?.pipeline ?? [];
		}
		if (activeId) return finishedTurns[finishedTurns.length - 1]?.pipeline ?? [];
		return [];
	}, [showingLive, pipeline.events, finishedTurns, selectedTurnId, activeId]);

	const dashboard = useMemo(() => buildDashboard(viewEvents, rootAgentId), [viewEvents, rootAgentId]);

	/** The turn the panels mirror (null while a turn runs live in this conversation). */
	const viewedTurn = useMemo(() => {
		if (showingLive) return null;
		return (
			finishedTurns.find((turn) => turn.id === selectedTurnId) ??
			finishedTurns[finishedTurns.length - 1] ??
			null
		);
	}, [showingLive, finishedTurns, selectedTurnId]);

	const workspaceState: WorkspaceState = useMemo(() => {
		if (showingLive || pipeline.state === "connecting") return "in_progress";
		if (viewedTurn?.status === "error") return "failed";
		if (viewEvents.length === 0) return DEFAULT_WORKSPACE_STATE;
		return "complete";
	}, [showingLive, pipeline.state, viewEvents.length, viewedTurn]);

	/** Failure detail of the viewed turn (kept on the turn, cleared when another turn is viewed). */
	const viewedError = viewedTurn?.status === "error" ? viewedTurn.answer : null;

	const selectedTask = viewedTurn?.task ?? "";
	const selectedTraceId = viewedTurn?.traceId ?? null;

	const turnNumber = selectedTurnId
		? finishedTurns.findIndex((turn) => turn.id === selectedTurnId) + 1
		: finishedTurns.length + (showingLive ? 1 : 0);

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
			const conversationId = runningConversationRef.current;
			if (!conversationId) return;
			const turn: Turn = {
				id: newId(),
				task,
				traceId: runningTraceRef.current,
				pipeline: events,
				status,
				answer,
				at: Date.now(),
			};
			updateWorkspace((current) => {
				const existing = current.sessions[conversationId] ?? [];
				return {
					...current,
					sessions: {
						...current.sessions,
						[conversationId]: [...existing, turn].slice(-MAX_TURNS),
					},
				};
			});
		};

		try {
			const result = await askAgent(
				{ message: task, conversationId: activeId },
				{
					onStart: (info) => {
						runningTraceRef.current = info.traceId;
						setLiveTraceId(info.traceId);
						if (info.conversationId) {
							runningConversationRef.current = info.conversationId;
							setRunningConversationId(info.conversationId);
							const id = info.conversationId;
							updateWorkspace((current) => ({ ...current, activeId: id }));
						}
					},
					onPipeline: (event) => pipeline.push(event),
					onToken: (chunk) => setStreamingText((current) => current + chunk),
				}
			);
			pipeline.merge(result.pipeline, { status: "ok", durationMs: result.durationMs });
			if (result.conversationId) {
				const id = result.conversationId;
				updateWorkspace((current) => ({ ...current, activeId: id }));
			}
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
			runningConversationRef.current = undefined;
			busyRef.current = false;
			setBusy(false);
			setRunningConversationId(undefined);
			// Activity ordering and the freshly seeded title come from the server.
			refreshSessions();
		}
	}, [draft, activeId, pipeline, updateWorkspace, refreshSessions]);

	/** `＋ new chat`: detach so the next sent task creates a brand-new session. */
	const startNewChat = useCallback(() => {
		detachFromConversations();
		setLiveTraceId(null);
		if (!busyRef.current) pipeline.reset();
	}, [pipeline, detachFromConversations]);

	/** Sidebar selection always triggers a server fetch; the local cache renders instantly meanwhile. */
	const selectSession = useCallback(
		(id: string) => {
			if (id === activeId) return;
			setSelectedTurnId(null);
			setLiveTraceId(null);
			updateWorkspace((current) => ({ ...current, activeId: id }));
			void openSession(id);
		},
		[activeId, updateWorkspace, openSession]
	);

	const renameSession = useCallback((id: string, title: string) => {
		renameConversation(id, title)
			.then((updated) =>
				setSessions((current) =>
					current.map((row) => (row.id === id ? { ...row, title: updated.title } : row))
				)
			)
			.catch(() => {
				/* name kept as-is when the server rejects the change */
			});
	}, []);

	const removeSession = useCallback(
		async (id: string) => {
			try {
				await deleteConversation(id);
			} catch {
				return; // the row stays when the server refuses (running task or unknown id)
			}
			setSessions((current) => current.filter((row) => row.id !== id));
			updateWorkspace((current) => {
				const sessions = { ...current.sessions };
				delete sessions[id];
				const next: WorkspaceStore = { ...current, sessions };
				if (current.activeId === id) next.activeId = undefined;
				return next;
			});
			setSelectedTurnId(null);
		},
		[updateWorkspace]
	);

	/** Offline fallback rows so cached sessions stay reachable when the list cannot load. */
	const shownSessions = useMemo(
		() =>
			sessions.length > 0
				? sessions
				: Object.keys(workspace.sessions).map<ConversationSummary>((id) => ({
						id,
						title: null,
						lastActivityAt: Date.now(),
					})),
		[sessions, workspace.sessions]
	);

	const toggleSidebar = useCallback(() => {
		setSidebarCollapsed((current) => {
			saveSidebarCollapsed(!current);
			return !current;
		});
	}, []);

	return (
		<div className="flex h-full w-full overflow-hidden bg-canvas">
			<ChatSidebar
				collapsed={sidebarCollapsed}
				sessions={shownSessions}
				activeId={activeId}
				runningId={runningConversationId}
				onToggle={toggleSidebar}
				onNewChat={startNewChat}
				onSelect={selectSession}
				onRename={renameSession}
				onDelete={(id) => void removeSession(id)}
			/>

			{/*
				Wireframe: role cards → middle band (tool lanes | main session | advisor) →
				viewport boundary → evidence row + event stream spanning the full page width,
				the page scrolling naturally below the first screenful.
			*/}
			<div className="flex min-w-0 flex-1 flex-col">
				<WorkspaceHeader
					state={workspaceState}
					traceId={showingLive ? liveTraceId : selectedTraceId}
					sidebarCollapsed={sidebarCollapsed}
					onToggleSidebar={toggleSidebar}
					onClear={startNewChat}
				/>

				<TaskBar
					value={draft}
					onChange={setDraft}
					onSend={() => void sendTask()}
					busy={busy}
				/>

				<main className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
					<div className="mx-auto flex max-w-[1560px] flex-col gap-2.5 p-3">
						<RoleCards roles={dashboard.roles} />

						<div className="flex min-h-0 flex-col gap-2.5 lg:h-[min(58vh,540px)] lg:flex-row">
							<div className="order-2 flex min-h-[200px] flex-col lg:order-1 lg:h-full lg:w-[248px] lg:shrink-0">
								<ToolRail cards={dashboard.tools} />
							</div>
							<div className="order-1 flex min-h-[380px] flex-col lg:order-2 lg:h-full lg:flex-1">
								<SessionPanel
									lines={dashboard.session}
									agentId={rootAgentId}
									turnNumber={turnNumber > 0 ? turnNumber : null}
									live={showingLive}
									answer={
										showingLive
											? streamingText
											: viewedTurn && viewedTurn.status === "ok"
												? viewedTurn.answer
												: ""
									}
									error={viewedError}
								/>
							</div>

							{/* Right rail: advisor column, full band height on desktop; stacks last on mobile. */}
							<div className="order-last flex min-h-[320px] flex-col lg:order-none lg:h-full lg:min-h-0 lg:w-[276px] lg:shrink-0">
								<SideRail
									llmRounds={dashboard.llmRounds}
									checkpoints={dashboard.checkpoints}
									task={showingLive ? liveTask : selectedTask}
									workersSummary={
										dashboard.workers.length > 0
											? `${dashboard.workers.length} worker${dashboard.workers.length > 1 ? "s" : ""} · ${dashboard.tools.length} lanes`
											: dashboard.tools.length > 0
												? `${dashboard.tools.length} tool lane${dashboard.tools.length > 1 ? "s" : ""}`
												: "just the main agent"
									}
									live={showingLive}
								/>
							</div>
						</div>

						{/* Viewport boundary: everything below scrolls naturally with the page. */}
						<div className="flex items-center gap-3 text-fg-faint" aria-hidden="true">
							<span className="h-px flex-1 bg-edge-strong" />
							<span className="micro-label shrink-0">viewport boundary</span>
							<span className="h-px flex-1 bg-edge-strong" />
						</div>

						<EvidenceRow dashboard={dashboard} />
						<EventStream dashboard={dashboard} />
					</div>
				</main>
			</div>
		</div>
	);
}
