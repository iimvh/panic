import { useMemo } from "react";
import type { ReactNode } from "react";
import InquiryActivity from "./InquiryActivity";
import FindingMarkdown from "./FindingMarkdown";
import { splitActivity, type ActivityFeed } from "../../lib/dashboard";
import type { TextStream } from "../../hooks/useTextStream";
import type { Turn } from "../../lib/storage";

function clockstamp(at: number): string {
	return new Date(at).toLocaleTimeString(undefined, {
		year: undefined,
		hour: "2-digit",
		minute: "2-digit",
		hour12: false,
	});
}

function Cursor() {
	return (
		<span
			aria-hidden="true"
			className="ml-px inline-block h-[1.05em] w-[0.55ch] translate-y-[0.12em] animate-cursor-blink bg-accent"
		/>
	);
}

/** The streamed/static finding text: first paragraph as an accent statement, then body. */
function AnswerBody({ text, streaming }: { text: string; streaming: boolean }) {
	const paragraphs = text.split(/\n\n+/).filter((part) => part.trim() !== "");
	const [lead, ...rest] = paragraphs;
	return (
		<div className="space-y-6 text-sm leading-[1.8] text-fg-body">
			{lead ? (
				<div className="border-l-2 border-accent py-1 pl-5 text-base font-medium leading-relaxed text-fg">
					<FindingMarkdown text={lead} />
					{streaming ? <Cursor /> : null}
				</div>
			) : null}
			{rest.map((paragraph, index) => (
				<FindingMarkdown key={index} text={paragraph} />
			))}
			{streaming && !lead ? (
				<div aria-hidden="true" className="space-y-2 font-mono text-[11px]">
					<span className="stream-shimmer animate-shimmer inline-block h-3 w-11/12 bg-edge" />
					<span className="stream-shimmer animate-shimmer mt-2 inline-block h-3 w-8/12 bg-edge" />
				</div>
			) : null}
		</div>
	);
}

/** The user's inquiry as a right-aligned chat bubble with its clock mark. */
function UserInquiry({ task, at }: { task: string; at: number | null }) {
	return (
		<div className="flex flex-col items-end gap-1">
			{at != null ? (
				<time className="font-mono text-[10px] tracking-[0.2em] text-fg-faint">{clockstamp(at)}</time>
			) : null}
			<div className="max-w-2xl whitespace-pre-wrap border border-edge bg-edge-soft px-4 py-3 text-sm leading-relaxed text-fg">
				{task}
			</div>
		</div>
	);
}

function ErrorFinding({ text }: { text: string }) {
	return (
		<div className="border-l-2 border-red-600 py-1 pl-5 text-sm leading-relaxed text-fg-body whitespace-pre-wrap">
			{text}
		</div>
	);
}

/** Total trace duration of a replayed turn (from its root trace event, when closed). */
function turnTotalMs(turn: Turn): number | null {
	return turn.pipeline.find((event) => event.phase === "trace")?.durationMs ?? null;
}

/** One finished turn of the conversation: bubble, worked line, finding. */
function TurnEntry({ turn }: { turn: Turn }) {
	const feed: ActivityFeed = useMemo(() => splitActivity(turn.pipeline, null), [turn.pipeline]);
	return (
		<article aria-label="Turn" className="space-y-3">
			<UserInquiry task={turn.task} at={turn.at} />
			<InquiryActivity
				steps={feed.pre}
				live={false}
				answerStarted={false}
				totalMs={turnTotalMs(turn)}
				sinceMs={null}
				traceKey={`turn:${turn.id}`}
			/>
			{turn.status === "error" ? (
				<ErrorFinding text={turn.answer} />
			) : turn.answer ? (
				<AnswerBody text={turn.answer} streaming={false} />
			) : null}
			{feed.post.length > 0 ? (
				<InquiryActivity
					steps={feed.post}
					live={false}
					answerStarted={false}
					totalMs={turnTotalMs(turn)}
					sinceMs={null}
					traceKey={`turn:${turn.id}`}
					variant="post"
				/>
			) : null}
		</article>
	);
}

interface LiveTurnProps {
	task: string;
	activity: ActivityFeed;
	stream: TextStream | null;
	liveStartedAt: number | null;
	totalMs: number | null;
	traceKey: string | null;
}

/** The turn currently running: bubble, live activity, streaming finding. */
function LiveTurnEntry({ task, activity, stream, liveStartedAt, totalMs, traceKey }: LiveTurnProps) {
	const answerStarted = stream !== null && stream.text.length > 0;
	return (
		<article aria-label="Running turn" className="space-y-3">
			<UserInquiry task={task} at={liveStartedAt} />
			<InquiryActivity
				steps={activity.pre}
				live
				answerStarted={answerStarted}
				totalMs={totalMs}
				sinceMs={liveStartedAt}
				traceKey={traceKey}
			/>
			<AnswerBody text={stream?.text ?? ""} streaming={stream !== null && stream.status === "streaming"} />
			{activity.post.length > 0 ? (
				<InquiryActivity
					steps={activity.post}
					live
					answerStarted={false}
					totalMs={totalMs}
					sinceMs={liveStartedAt}
					traceKey={traceKey}
					variant="post"
				/>
			) : null}
		</article>
	);
}

interface ConversationTranscriptProps {
	/** Finished turns of the active conversation, oldest first. */
	turns: Turn[];
	/** The running turn appended at the bottom while a run streams, null otherwise. */
	live: LiveTurnProps | null;
}

/**
 * The conversation transcript: every inquiry/answer pair of the session stacked in
 * order like a chat, with the live turn appended at the bottom while it streams.
 */
export default function ConversationTranscript({ turns, live }: ConversationTranscriptProps): ReactNode {
	return (
		<div className="mx-auto max-w-3xl space-y-8 pb-6">
			{turns.map((turn) => (
				<TurnEntry key={turn.id} turn={turn} />
			))}
			{live ? (
				<LiveTurnEntry
					task={live.task}
					activity={live.activity}
					stream={live.stream}
					liveStartedAt={live.liveStartedAt}
					totalMs={live.totalMs}
					traceKey={live.traceKey}
				/>
			) : null}
			{!live && turns.length === 0 ? (
				<p className="text-sm text-fg-faint">
					Describe the inquiry below — the agents take it from there.
				</p>
			) : null}
		</div>
	);
}
