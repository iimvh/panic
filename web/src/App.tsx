import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import ExecutionLedger, { type LedgerState } from "./components/workspace/ExecutionLedger";
import ConversationTranscript from "./components/workspace/ConversationTranscript";
import InquiryComposer from "./components/workspace/InquiryComposer";
import SessionDirectory from "./components/workspace/SessionDirectory";
import WorkspaceHeader, { type WorkspaceView } from "./components/workspace/WorkspaceHeader";
import { usePipelineStream } from "./hooks/usePipelineStream";
import { useTextStream } from "./hooks/useTextStream";
import { AgentError, askAgent, fetchAgentTree } from "./lib/api";
import {
	deleteConversation,
	fetchConversation,
	listConversations,
	type ConversationSummary,
} from "./lib/conversations";
import { splitActivity, buildDashboard } from "./lib/dashboard";
import {
	MAX_TURNS,
	loadTheme,
	loadWorkspace,
	newId,
	saveTheme,
	saveWorkspace,
	type Turn,
	type WorkspaceStore,
	type WorkspaceTheme,
} from "./lib/storage";

const DEFAULT_WORKSPACE_STATE: LedgerState = "idle";

export default function App() {
	const [workspace, setWorkspace] = useState<WorkspaceStore>(() => loadWorkspace());
	const [sessions, setSessions] = useState<ConversationSummary[]>([]);
	const [draft, setDraft] = useState("");
	const [busy, setBusy] = useState(false);
	const [liveTask, setLiveTask] = useState("");
	const [liveTraceId, setLiveTraceId] = useState<number | null>(null);
	const [rootAgentId, setRootAgentId] = useState<string | null>(null);
	const [agentCount, setAgentCount] = useState(0);
	const [view, setView] = useState<WorkspaceView>("synthesis");
	const [theme, setTheme] = useState<WorkspaceTheme>(() => loadTheme() ?? "light");
	/** Conversation the current run drives; the workspace streams it until the run closes. */
	const [runningConversationId, setRunningConversationId] = useState<string | undefined>(undefined);
	/** Wall-clock moment the first answer token arrived; the interleaving boundary of the chat timeline. */
	const [tokenStartedAt, setTokenStartedAt] = useState<number | null>(null);
	const tokenStartedRef = useRef<number | null>(null);

	const pipeline = usePipelineStream();
	/** Smoothed typewriter view of the live answer tokens (skeleton → typed text → cursor off). */
	const answerStream = useTextStream();
	const busyRef = useRef(false);
	/** Trace id of the turn currently running; set by onStart, read by the turn finaliser. */
	const runningTraceRef = useRef<number | null>(null);
	/** Conversation the running turn belongs to; captured from the start frame. */
	const runningConversationRef = useRef<string | undefined>(undefined);

	const activeId = workspace.activeId;

	// Theme is a document class so the paired CSS variables flip globally.
	useEffect(() => {
		document.documentElement.classList.toggle("dark", theme === "dark");
		saveTheme(theme);
	}, [theme]);

	const toggleTheme = useCallback(() => {
		setTheme((current) => (current === "dark" ? "light" : "dark"));
	}, []);

	const updateWorkspace = useCallback((mutate: (current: WorkspaceStore) => WorkspaceStore) => {
		setWorkspace((current) => {
			const next = mutate(current);
			saveWorkspace(next);
			return next;
		});
	}, []);

	const detachFromConversations = useCallback(() => {
		updateWorkspace((current) => ({ ...current, activeId: undefined }));
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
				/* the directory simply keeps its last known list */
			});
	}, []);

	useEffect(() => {
		void fetchAgentTree().then((tree) => {
			setRootAgentId(tree?.root ?? null);
			setAgentCount(tree?.agents.length ?? 0);
		});
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

	/** The run drives this conversation; while the transcript appends the live turn, panels mirror it. */
	const showingLive = busy && activeId === runningConversationId;

	/** The pipeline the panels mirror: live frames while streaming this conversation, last turn otherwise. */
	const viewEvents = useMemo(() => {
		if (showingLive) return pipeline.events;
		if (activeId) return finishedTurns[finishedTurns.length - 1]?.pipeline ?? [];
		return [];
	}, [showingLive, pipeline.events, finishedTurns, activeId]);

	const dashboard = useMemo(() => buildDashboard(viewEvents, rootAgentId), [viewEvents, rootAgentId]);

	/** Interleaved chat timeline of the mirrored events: work rows before and after
	 * the answer's first token. Replayed turns split on their assistant message. */
	const activity = useMemo(
		() => splitActivity(viewEvents, showingLive ? tokenStartedAt : null),
		[viewEvents, showingLive, tokenStartedAt]
	);

	/** Wall-clock the live turn started (the transcript's clock mark). */
	const [liveStartedAt, setLiveStartedAt] = useState<number | null>(null);

	/** Mirrored turn while idle (for the trace hash + failure state). */
	const mirroredTurn = finishedTurns[finishedTurns.length - 1] ?? null;

	const ledgerState: LedgerState = useMemo(() => {
		if (showingLive || pipeline.state === "connecting") return "running";
		if (mirroredTurn?.status === "error" || dashboard.trace?.status === "error") return "failed";
		if (viewEvents.length === 0) return DEFAULT_WORKSPACE_STATE;
		return "complete";
	}, [showingLive, pipeline.state, viewEvents.length, mirroredTurn, dashboard.trace]);

	const shownTraceId = showingLive ? liveTraceId : mirroredTurn?.traceId ?? null;

	// Viewport UX: the register scroller follows the growing feed while the user
	// reads near the bottom, and yields the moment they scroll away to review.
	const registerScrollerRef = useRef<HTMLDivElement>(null);
	const registerStickRef = useRef(true);

	const onRegisterScroll = useCallback(() => {
		const element = registerScrollerRef.current;
		if (!element) return;
		registerStickRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
	}, []);

	useEffect(() => {
		if (!registerStickRef.current) return;
		const element = registerScrollerRef.current;
		if (element) element.scrollTop = element.scrollHeight;
	}, [finishedTurns.length, answerStream.text, activity.post.length, showingLive]);

	const sendTask = useCallback(async () => {
		const task = draft.trim();
		if (!task || busyRef.current) return;

		busyRef.current = true;
		setBusy(true);
		setDraft("");
		setLiveTask(task);
		setLiveTraceId(null);
		setTokenStartedAt(null);
		tokenStartedRef.current = null;
		setLiveStartedAt(Date.now());
		pipeline.begin();
		answerStream.begin();

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
					onToken: (chunk) => {
						// Stamp the first arrival once: the point the chat text begins typing.
						if (tokenStartedRef.current == null) {
							tokenStartedRef.current = Date.now();
							setTokenStartedAt(tokenStartedRef.current);
						}
						answerStream.append(chunk);
					},
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
			// Close the typewriter: the buffered tail drains at speed, then the cursor retires
			// and the register switches to the finished turn's replay text.
			answerStream.end();
			runningTraceRef.current = null;
			runningConversationRef.current = undefined;
			busyRef.current = false;
			setBusy(false);
			setRunningConversationId(undefined);
			// Activity ordering and the freshly seeded title come from the server.
			refreshSessions();
		}
	}, [draft, activeId, pipeline, answerStream, updateWorkspace, refreshSessions]);

	/** `+ New Session`: detach so the next sent task creates a brand-new session. */
	const startNewSession = useCallback(() => {
		detachFromConversations();
		setLiveTraceId(null);
		if (!busyRef.current) {
			pipeline.reset();
			answerStream.reset();
		}
	}, [pipeline, answerStream, detachFromConversations]);

	/** Directory selection always triggers a server fetch; the local cache renders instantly meanwhile. */
	const selectSession = useCallback(
		(id: string) => {
			if (id === activeId) return;
			setLiveTraceId(null);
			updateWorkspace((current) => ({ ...current, activeId: id }));
			void openSession(id);
		},
		[activeId, updateWorkspace, openSession]
	);

	/** Forget a session for good; deleting the active one detaches the workspace. */
	const deleteSession = useCallback(
		async (id: string) => {
			if (id === runningConversationId) return;
			try {
				await deleteConversation(id);
				if (id === activeId) {
					detachFromConversations();
					setLiveTraceId(null);
				}
				updateWorkspace((current) => {
					const sessions = { ...current.sessions };
					delete sessions[id];
					return { ...current, sessions };
				});
				refreshSessions();
			} catch {
				// offline or the server refused while busy: the row keeps living in the directory
				console.error(`The server could not delete session ${id}.`);
			}
		},
		[runningConversationId, activeId, detachFromConversations, updateWorkspace, refreshSessions]
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

	const showSynthesis = view !== "trace";
	const showLedger = view !== "synthesis";

	return (
		<div className="flex h-full w-full flex-col overflow-hidden bg-canvas text-fg transition-colors duration-150">
			<WorkspaceHeader
				view={view}
				onViewChange={setView}
				theme={theme}
				onToggleTheme={toggleTheme}
				traceId={shownTraceId}
				onNewSession={startNewSession}
			/>

			<div className="flex min-h-0 flex-1 overflow-hidden">
				<div className="hidden md:flex">
					<SessionDirectory
						sessions={shownSessions}
						taskCache={workspace.sessions}
						activeId={activeId}
						runningId={runningConversationId}
						agentCount={agentCount}
						onSelect={selectSession}
						onDelete={(id) => void deleteSession(id)}
					/>
				</div>

				<main className="flex min-h-0 flex-1 overflow-hidden">
					{showSynthesis ? (
						<section
							className={`flex min-h-0 flex-col overflow-hidden bg-canvas transition-all lg:px-0 ${
								showLedger ? "flex-1 border-r border-edge" : "flex-1"
							}`}
						>
						<div
							ref={registerScrollerRef}
							onScroll={onRegisterScroll}
							className="scrollbar-thin min-h-0 flex-1 overflow-y-auto px-6 pb-4 pt-8 lg:px-16"
						>
						<ConversationTranscript
							turns={finishedTurns}
							live={
								showingLive
									? {
											task: liveTask,
											activity,
											stream: answerStream,
											liveStartedAt,
											totalMs: dashboard.trace?.durationMs ?? null,
											traceKey: `live:${liveTraceId ?? "pending"}`,
										}
									: null
							}
						/>
						</div>
							<InquiryComposer
								value={draft}
								onChange={setDraft}
								onSend={() => void sendTask()}
								busy={busy}
								modelLabel={dashboard.model ?? "PANIC Orchestrator"}
								live={showingLive}
							/>
						</section>
					) : null}

					{showLedger ? (
						<section
							className={`min-h-0 overflow-hidden bg-surface ${
								view === "trace" ? "flex w-full flex-1" : "w-[480px] shrink-0 lg:w-[540px]"
							} border-l border-edge`}
						>
							<ExecutionLedger dashboard={dashboard} state={ledgerState} traceId={shownTraceId} />
						</section>
					) : null}
				</main>
			</div>
		</div>
	);
}
