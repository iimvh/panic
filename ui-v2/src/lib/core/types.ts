import type { Approval, Part, PlanProposal, Questions, Usage } from './reply';

export type Message = {
	id: number;
	role: 'user' | 'assistant' | 'command';
	text: string;
	/** model · tokens, "stopped", ... */
	meta?: string;
	/** An answer's text, tool calls and reviews in the order they happened (assistant only). */
	parts?: Part[];
	/** When the answer was asked (ms since epoch), for the elapsed time while it streams. */
	startedAt?: number;
	/** When its stream ended (this page's clock), for the time it took. */
	endedAt?: number;
	/** Tokens it used, from the `done` event (assistant only). */
	usage?: Usage;
	/** A tool call waiting for the user to allow or deny it. */
	approval?: Approval;
	/** More questions, asked while `approval` is shown (calls running in parallel); shown next, in order. */
	waiting?: Approval[];
	/** A plan the agent proposes (plan mode), waiting for the user to approve it or send it back. */
	plan?: PlanProposal;
	/** Why the answer ended (`done`, `max_steps`, `budget`, `rejected`, `truncated`, `stalled`), from the `done` event (assistant only). */
	stop?: string;
	/** Questions the agent asks, waiting for the user's answers. */
	question?: Questions;
	error?: boolean;
	streaming?: boolean;
	/** The answer's trace (assistant only), when the server traces turns. */
	traceId?: string;
};
