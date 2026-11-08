import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
	AssistantRuntimeProvider,
	fromThreadMessageLike,
	useAssistantDataUI,
} from "@assistant-ui/react";
import { fromAgUiMessages, useAgUiRuntime } from "@assistant-ui/react-ag-ui";
import { HttpAgent } from "@ag-ui/client";
import type {
	ExternalStoreThreadListAdapter,
} from "@assistant-ui/core";
import { Thread } from "@/components/assistant-ui/elements/thread.aui";
import { ThreadList } from "@/components/assistant-ui/elements/thread-list.aui";
import { PipelineTraceFrame } from "@/components/pipeline/PipelineTraceFrame";
import { ProviderConnectDialog } from "@/components/provider/ProviderConnectDialog";
import { Button } from "@/components/ui/button";
import { ConversationSummary, createConversation, deleteConversation, fetchConversation, listConversations, renameConversation } from "@/lib/conversations";
import { PIPELINE_EVENT_NAME } from "@/lib/pipeline";
import { connectProvider, fetchFreeModels, fetchProviderStatus, type FreeLlmModel, type ProviderStatus } from "@/lib/provider";
import { Maximize2Icon, MoreHorizontalIcon } from "lucide-react";


/**
 * The PANIC workspace: an assistant-ui ThreadList over the server's
 * conversation records beside an assistant-ui Thread wired to the AG-UI
 * agent run. The runtime owns the whole chat life cycle; pipeline frames
 * stream in as data parts and render inline in the assistant messages.
 */
export default function App() {
	const [sessions, setSessions] = useState<ConversationSummary[]>([]);
	/** Selected conversation; null is the in-memory new chat (no server id yet). */
	const [selectedId, setSelectedId] = useState<string | null>(null);
	/** Server-held provider connection; the settings dialog mirrors it. */
	const [provider, setProvider] = useState<ProviderStatus | null>(null);
	const [freeModels, setFreeModels] = useState<FreeLlmModel[]>([]);

	const sessionsRef = useRef(sessions);
	sessionsRef.current = sessions;

	const refreshSessions = useCallback(() => {
		listConversations()
			.then((rows) => setSessions(rows))
			.catch(() => {
				/* the list keeps its last known rows */
			});
	}, []);

	useEffect(() => {
		refreshSessions();
	}, [refreshSessions]);

	useEffect(() => {
		void fetchProviderStatus()
			.then((status) => {
				setProvider(status);
				return status.provider ?? undefined;
			})
			.then((providerId) => {
				void fetchFreeModels(providerId)
					.then((models) => setFreeModels(models))
					.catch(() => {
						/* the picker stays on whatever the last successful fetch cached */
					});
			})
			.catch(() => {
				/* the dialog keeps its unknown state until the backend answers */
			});
	}, []);

	/** Fetch free models for a given provider id. */
	const fetchModelsForProvider = useCallback(async (providerId: string) => {
		return fetchFreeModels(providerId);
	}, []);

	/** Settings save: a failed key throws and the dialog reports it. */
	const connectProviderAndRefresh = useCallback(async (kind: string, apiKey: string, model: string) => {
		const status = await connectProvider(kind, apiKey, model);
		setProvider(status);
		void fetchFreeModels(kind)
			.then((models) => setFreeModels(models))
			.catch(() => {});
	}, []);

	/** The AG-UI agent for the open conversation; recreated on every switch. */
	const agent = useMemo(() => {
		const instance = new HttpAgent({
			url: "/api/agent/chat",
			...(selectedId !== null ? { threadId: selectedId } : {}),
		});
		// A settled run refreshed the directory once; titles arrive server-side.
		instance.subscribe({
			onRunFinalized: () => refreshSessions(),
		});
		return instance;
	}, [selectedId, refreshSessions]);

	/**
	 * Conversation sync as an external-store thread list: metadata rows from
	 * GET /api/conversations, hydration per switch from the session replay.
	 */
	const threadListAdapters = useMemo(
		(): Pick<
			ExternalStoreThreadListAdapter,
			"threadId" | "threads" | "onRename" | "onDelete"
		> => ({
			threadId: selectedId ?? undefined,
			threads: sessions.map((session) => ({
				id: session.id,
				status: "regular" as const,
				threadId: session.id,
				...(session.title !== null ? { title: session.title } : {}),
			})),
			onRename: async (threadId, title) => {
				setSessions((current) =>
					current.map((session) =>
						session.id === threadId ? { ...session, title } : session,
					),
				);
				await renameConversation(threadId, title).catch(() => refreshSessions());
			},
			onDelete: async (threadId) => {
				await deleteConversation(threadId);
				if (threadId === selectedId) setSelectedId(null);
				refreshSessions();
			},
		}),
		[selectedId, sessions, refreshSessions],
	);

	const onSwitchToNewThread = useCallback(async () => {
		const id = await createConversation();
		setSessions((current) => {
			if (current.some((session) => session.id === id)) return current;
			return [{ id, title: null, lastActivityAt: Date.now() }, ...current];
		});
		setSelectedId(id);
	}, []);

	/** A switch fetches the replay once; the runtime keeps later turns live. */
	const onSwitchToThread = useCallback(async (threadId: string) => {
		setSelectedId(threadId);
		const replay = await fetchConversation(threadId).catch(() => null);
		if (!replay || replay.messages.length === 0) return { messages: [] };
		const messages = fromAgUiMessages(replay.messages).map((like, index) =>
			fromThreadMessageLike(like, `replay-${index}`, {
				type: "complete",
				reason: "unknown",
			}),
		);
		return { messages };
	}, []);

	const runtime = useAgUiRuntime({
		agent,
		adapters: {
			threadList: {
				...threadListAdapters,
				onSwitchToNewThread,
				onSwitchToThread,
			},
		},
	});

	return (
		<AssistantRuntimeProvider runtime={runtime}>
			<PipelineDataRegistration />
			<div className="flex h-dvh flex-col bg-background text-foreground">
				<header className="flex shrink-0 items-center justify-between px-4 py-3 border-b border-border bg-background">
					<div className="flex items-center gap-2">
						<span className="px-1 text-sm font-semibold tracking-tight">PANIC</span>
						<span className="text-xs font-medium text-foreground">New chat</span>
					</div>
					<div className="flex items-center gap-2">
					<ProviderConnectDialog
						status={provider}
						initialModels={freeModels}
						fetchModels={fetchModelsForProvider}
						onConnect={connectProviderAndRefresh}
					/>
						<Button
							variant="ghost"
							size="icon"
							className="size-6 hover:bg-muted"
							aria-label="More"
						>
							<MoreHorizontalIcon className="size-4" />
						</Button>
						<Button
							variant="ghost"
							size="icon"
							className="size-6 hover:bg-muted"
							aria-label="Toggle fullscreen"
						>
							<Maximize2Icon className="size-4" />
						</Button>
					</div>
				</header>
				<div className="flex flex-1 min-w-0 overflow-hidden">
					<aside className="flex w-72 shrink-0 flex-col border-r border-border bg-muted/30">
						<div className="min-h-0 flex-1 overflow-y-auto px-3 py-3">
							<ThreadList />
						</div>
						<div className="border-t border-border p-3">
							<Button
								variant="ghost"
								className="w-full justify-start text-xs text-muted-foreground"
								onClick={() => {
									/* sign-in placeholder */
								}}
							>
								Sign in
							</Button>
						</div>
					</aside>
					<main className="min-w-0 flex-1">
						<Thread
							models={freeModels}
							onModelChange={(model) => {
								if (provider?.provider) {
									void connectProvider(provider.provider, "", model)
										.then((status) => {
											setProvider(status);
											void fetchFreeModels(status.provider ?? undefined)
												.then((models) => setFreeModels(models))
												.catch(() => {});
										})
										.catch(() => {});
								}
							}}
						/>
					</main>
				</div>
			</div>
		</AssistantRuntimeProvider>
	);
}

/** Registers the pipeline data-part renderer once inside the runtime. */
function PipelineDataRegistration(): null {
	useAssistantDataUI({ name: PIPELINE_EVENT_NAME, render: PipelineTraceFrame });
	return null;
}
