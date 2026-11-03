import { useCallback, useEffect, useRef, useState } from "react";
import ChatHeader from "./components/ChatHeader";
import Composer from "./components/Composer";
import MessageBubble from "./components/MessageBubble";
import PipelinePanel from "./components/PipelinePanel";
import TypingRow from "./components/TypingRow";
import Welcome from "./components/Welcome";
import { usePipelineStream } from "./hooks/usePipelineStream";
import { AgentError, askAgent } from "./lib/api";
import { countRunning } from "./lib/pipeline";
import { loadMessages, newId, saveMessages, type Message, type Role } from "./lib/storage";
import { SUGGESTIONS, type Suggestion } from "./lib/suggestions";

const PIPELINE_STORAGE_KEY = "meeting-room-pipeline-open";

export default function App() {
	const [messages, setMessages] = useState<Message[]>(() => loadMessages());
	const [draft, setDraft] = useState("");
	const [busy, setBusy] = useState(false);
	const [pipelineOpen, setPipelineOpen] = useState(() => {
		try {
			return localStorage.getItem(PIPELINE_STORAGE_KEY) !== "0";
		} catch {
			return true;
		}
	});
	const [pipelineError, setPipelineError] = useState<string | null>(null);
	const [traceId, setTraceId] = useState<number | null>(null);

	const pipeline = usePipelineStream();
	const messagesRef = useRef<HTMLDivElement>(null);
	const busyRef = useRef(false);

	useEffect(() => saveMessages(messages), [messages]);

	useEffect(() => {
		try {
			localStorage.setItem(PIPELINE_STORAGE_KEY, pipelineOpen ? "1" : "0");
		} catch {
			/* storage unavailable */
		}
	}, [pipelineOpen]);

	useEffect(() => {
		const el = messagesRef.current;
		if (!el) return;
		el.scrollTo({ top: el.scrollHeight, behavior: messages.length > 0 ? "smooth" : "auto" });
	}, [messages, busy]);

	useEffect(() => {
		const onKey = (event: KeyboardEvent) => {
			if (event.key === "Escape") setPipelineOpen(false);
		};
		document.addEventListener("keydown", onKey);
		return () => document.removeEventListener("keydown", onKey);
	}, []);

	const appendMessage = useCallback((role: Role, text: string) => {
		setMessages((prev) => [...prev, { id: newId(), role, text, at: Date.now() }].slice(-200));
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
			pipeline.reset();
			appendMessage("user", text);

			try {
				const result = await askAgent(text, {
					onStart: (id) => {
						setTraceId(id);
						pipeline.connect(id);
					},
				});
				pipeline.merge(result.pipeline, { status: "ok", durationMs: result.durationMs });
				appendMessage("assistant", result.reply);
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
				} else if (error instanceof Error) {
					detail = error.message;
					setPipelineError(error.message);
				}
				appendMessage("error", detail);
			} finally {
				busyRef.current = false;
				setBusy(false);
			}
		},
		[appendMessage, pipeline]
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
	};

	const running = countRunning(pipeline.events);

	return (
		<div className="flex h-full w-full justify-center p-0 lg:p-6">
			<div className="flex h-full w-full max-w-[1540px] overflow-hidden bg-transparent lg:rounded-[26px] lg:bg-white lg:shadow-[0_40px_100px_-40px_rgba(2,6,23,0.7)] lg:ring-1 lg:ring-white/10">
				<main className="flex min-w-0 flex-1 flex-col bg-[#f5f7fd]">
					<ChatHeader
						onClear={handleClear}
						onTogglePipeline={() => setPipelineOpen((value) => !value)}
						pipelineOpen={pipelineOpen}
						pipelineRunning={running}
						hasMessages={messages.length > 0}
						busy={busy}
					/>

					<div
						ref={messagesRef}
						className="scrollbar-thin flex-1 overflow-y-auto px-4 py-5 sm:px-8 sm:py-6"
						aria-live="polite"
						aria-label="Conversation"
					>
						<div className="mx-auto flex max-w-3xl flex-col gap-4">
							{messages.length === 0 && !busy ? (
								<Welcome suggestions={SUGGESTIONS} onSuggestion={handleSuggestion} />
							) : (
								messages.map((message) => (
									<MessageBubble key={message.id} message={message} />
								))
							)}
							{busy && <TypingRow />}
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
					<div className="fixed inset-0 z-50 flex flex-col bg-[#0b1020]/97 xl:static xl:z-auto xl:w-[380px] xl:shrink-0 xl:bg-transparent">
						<PipelinePanel
							events={pipeline.events}
							state={pipeline.state}
							busy={busy}
							traceId={traceId}
							error={pipelineError}
							onClose={() => setPipelineOpen(false)}
						/>
					</div>
				)}
			</div>
		</div>
	);
}
