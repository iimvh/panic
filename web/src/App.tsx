import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import ChatHeader from "./components/ChatHeader";
import Composer from "./components/Composer";
import MessageBubble from "./components/MessageBubble";
import PipelinePanel from "./components/PipelinePanel";
import SplitDivider from "./components/SplitDivider";
import TypingRow from "./components/TypingRow";
import Welcome from "./components/Welcome";
import { usePipelineStream, type StreamState } from "./hooks/usePipelineStream";
import { AgentError, askAgent } from "./lib/api";
import { countRunning, type PipelineEvent } from "./lib/pipeline";
import {
	loadConversationId,
	loadMessages,
	newId,
	saveConversationId,
	saveMessages,
	type Message,
	type Role,
} from "./lib/storage";
import { SUGGESTIONS, type Suggestion } from "./lib/suggestions";

const PIPELINE_STORAGE_KEY = "panic-pipeline-open";
const PIPELINE_WIDTH_KEY = "panic-pipeline-width";
const PIPELINE_MIN_WIDTH = 320;
const PIPELINE_MAX_WIDTH = 760;

function clampPipelineWidth(value: number): number {
	const max = Math.min(PIPELINE_MAX_WIDTH, Math.max(PIPELINE_MIN_WIDTH, window.innerWidth - 420));
	return Math.min(max, Math.max(PIPELINE_MIN_WIDTH, value));
}

function loadPipelineWidth(): number {
	try {
		const raw = Number(localStorage.getItem(PIPELINE_WIDTH_KEY));
		if (Number.isFinite(raw) && raw > 0) return clampPipelineWidth(raw);
	} catch {
		/* storage unavailable */
	}
	return 400;
}

export default function App() {
	const [messages, setMessages] = useState<Message[]>(() => loadMessages());
	const [draft, setDraft] = useState("");
	const [busy, setBusy] = useState(false);
	const [conversationId, setConversationId] = useState<string | undefined>(() => loadConversationId());
	const [pipelineOpen, setPipelineOpen] = useState(() => {
		try {
			return localStorage.getItem(PIPELINE_STORAGE_KEY) !== "0";
		} catch {
			return true;
		}
	});
	const [pipelineWidth, setPipelineWidth] = useState<number>(() => loadPipelineWidth());
	const [pipelineError, setPipelineError] = useState<string | null>(null);
	const [traceId, setTraceId] = useState<number | null>(null);
	/** Partial assistant answer while it streams; empty when not streaming. */
	const [streamingText, setStreamingText] = useState("");
	/** Index of the assistant message whose pipeline is shown; -1 = follow the live turn. */
	const [selectedMessageIndex, setSelectedMessageIndex] = useState(-1);

	const pipeline = usePipelineStream();
	const messagesRef = useRef<HTMLDivElement>(null);
	const busyRef = useRef(false);

	useEffect(() => saveMessages(messages), [messages]);

	useEffect(() => {
		saveConversationId(conversationId);
	}, [conversationId]);

	useEffect(() => {
		try {
			localStorage.setItem(PIPELINE_STORAGE_KEY, pipelineOpen ? "1" : "0");
		} catch {
			/* storage unavailable */
		}
	}, [pipelineOpen]);

	useEffect(() => {
		const onResize = () => setPipelineWidth((value) => clampPipelineWidth(value));
		window.addEventListener("resize", onResize);
		return () => window.removeEventListener("resize", onResize);
	}, []);

	const persistPipelineWidth = useCallback((value: number) => {
		try {
			localStorage.setItem(PIPELINE_WIDTH_KEY, String(Math.round(value)));
		} catch {
			/* storage unavailable */
		}
	}, []);

	useEffect(() => {
		const el = messagesRef.current;
		if (!el) return;
		el.scrollTo({ top: el.scrollHeight, behavior: messages.length > 0 ? "smooth" : "auto" });
	}, [messages, busy, streamingText]);

	useEffect(() => {
		const onKey = (event: KeyboardEvent) => {
			if (event.key === "Escape") setPipelineOpen(false);
		};
		document.addEventListener("keydown", onKey);
		return () => document.removeEventListener("keydown", onKey);
	}, []);

	const appendMessage = useCallback((role: Role, text: string, extra?: Partial<Message>): string => {
		const id = newId();
		setMessages((prev) =>
			[...prev, { id, role, text, at: Date.now(), ...extra }].slice(-200)
		);
		return id;
	}, []);

	const sendMessage = useCallback(
		async (rawText: string) => {
			const text = rawText.trim();
			if (!text || busyRef.current) return;

			busyRef.current = true;
			setBusy(true);
			setDraft("");
			setPipelineError(null);
			setTraceId(null);
			setStreamingText("");
			setSelectedMessageIndex(-1);
			pipeline.begin();
			appendMessage("user", text);

			try {
				const result = await askAgent(
					{ message: text, conversationId },
					{
						onStart: (info) => {
							setTraceId(info.traceId);
							if (info.conversationId) setConversationId(info.conversationId);
						},
						onPipeline: (event) => pipeline.push(event),
						onToken: (chunk) => setStreamingText((current) => current + chunk),
					}
				);
				setStreamingText("");
				pipeline.merge(result.pipeline, { status: "ok", durationMs: result.durationMs });
				if (result.conversationId) setConversationId(result.conversationId);
				appendMessage("assistant", result.reply, {
					traceId: result.traceId,
					pipeline: result.pipeline,
				});
			} catch (error) {
				let detail = "Something went wrong.";
				if (error instanceof AgentError) {
					detail = error.message;
					pipeline.merge(error.pipeline, {
						status: "error",
						error: error.message,
						durationMs: 0,
					});
					setPipelineError(error.message);
					appendMessage("error", detail, {
						traceId: error.traceId,
						pipeline: error.pipeline,
					});
				} else {
					if (error instanceof Error) {
						detail = error.message;
						setPipelineError(error.message);
					}
					pipeline.fail(detail);
					appendMessage("error", detail);
				}
			} finally {
				setStreamingText("");
				busyRef.current = false;
				setBusy(false);
			}
		},
		[appendMessage, pipeline, conversationId]
	);

	const handleSuggestion = (suggestion: Suggestion) => {
		void sendMessage(suggestion.prompt);
	};

	const handleClear = () => {
		if (messages.length === 0) return;
		if (!window.confirm("Clear the whole conversation?")) return;
		setMessages([]);
		pipeline.reset();
		setPipelineError(null);
		setTraceId(null);
		setConversationId(undefined);
	};

	/** Pipeline shown in the panel: the live turn while running, otherwise the selected message's turn. */
	const panelEvents = useMemo<PipelineEvent[]>(() => {
		if (busy) return pipeline.events;
		if (selectedMessageIndex >= 0) {
			return messages[selectedMessageIndex]?.pipeline ?? [];
		}
		return pipeline.events;
	}, [busy, pipeline.events, messages, selectedMessageIndex]);

	const panelTraceId = busy ? traceId : messages[selectedMessageIndex]?.traceId ?? traceId;
	// Live turn reflects the stream state; a past turn is always finished.
	const panelState: StreamState = busy ? pipeline.state : selectedMessageIndex >= 0 ? "closed" : pipeline.state;
	const running = countRunning(panelEvents);

	return (
		<div className="flex h-full w-full flex-col bg-surface">
			<ChatHeader
				onClear={handleClear}
				onTogglePipeline={() => setPipelineOpen((value) => !value)}
				pipelineOpen={pipelineOpen}
				pipelineRunning={running}
				hasMessages={messages.length > 0}
				busy={busy}
			/>

			<div className="flex min-h-0 flex-1">
				<main className="flex min-w-0 flex-1 flex-col border-r border-hairline sm:border-r-0">
					<div
						ref={messagesRef}
						className="scrollbar-thin flex-1 overflow-y-auto px-4 py-6 sm:px-8 sm:py-8"
						aria-live="polite"
						aria-label="Conversation"
					>
						<div className="mx-auto flex max-w-3xl flex-col gap-5">
							{messages.length === 0 && !busy ? (
								<Welcome suggestions={SUGGESTIONS} onSuggestion={handleSuggestion} />
							) : (
								messages.map((message, index) => (
									<MessageBubble
										key={message.id}
										message={message}
										selected={index === selectedMessageIndex}
										onSelectPipeline={
											message.role !== "user" && (message.pipeline?.length ?? 0) > 0
												? () => {
														setSelectedMessageIndex(index);
														setPipelineOpen(true);
													}
												: undefined
										}
									/>
								))
							)}
							{streamingText ? (
								<MessageBubble
									message={{ id: "streaming", role: "assistant", text: streamingText, at: Date.now() }}
								/>
							) : (
								busy && <TypingRow />
							)}
						</div>
					</div>

					<Composer
						busy={busy}
						draft={draft}
						onDraftChange={setDraft}
						onSend={(text) => void sendMessage(text)}
					/>
				</main>

				{pipelineOpen && (
					<SplitDivider
						width={pipelineWidth}
						min={PIPELINE_MIN_WIDTH}
						max={PIPELINE_MAX_WIDTH}
						onResize={(value) => setPipelineWidth(clampPipelineWidth(value))}
						onResizeEnd={persistPipelineWidth}
					/>
				)}

				{pipelineOpen && (
					<div
						style={{ width: pipelineWidth }}
						className="fixed inset-0 z-50 flex flex-col bg-surface-alt max-[899px]:!w-full min-[900px]:static min-[900px]:z-auto min-[900px]:max-w-[calc(100vw-420px)] min-[900px]:shrink-0">
						<PipelinePanel
							events={panelEvents}
							state={panelState}
							busy={busy}
							traceId={panelTraceId}
							error={pipelineError}
							onClose={() => setPipelineOpen(false)}
						/>
					</div>
				)}
			</div>
		</div>
	);
}
