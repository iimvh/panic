import { ASK_USER_TOOL, EXIT_PLAN_TOOL, shellCommand, type Part, type ToolStep } from './reply';
import type { Message } from './types';
export { compact } from './format';

/** What a tool call does to the world, for counts and labels. */
export type Effect = 'read' | 'write' | 'command' | 'network' | 'ask' | 'agent' | 'other';

/** How Panic runs tools in this chat: asks first, only plans (read-only), or runs without asking. */
export type Mode = 'ask' | 'plan' | 'auto';

/** The effect of a tool by its name; unknown tools are `other`. */
export function effect(name: string): Effect {
	if (name === 'read_file' || name === 'list_dir' || name === 'memory_view' || name === 'skill' || name === 'todo_read') return 'read';
	if (name === 'write_file' || name === 'edit_file') return 'write';
	if (name === 'exec' || name === 'exec_output' || name === 'exec_stop' || name === 'check') return 'command';
	if (name.startsWith('mcp__')) return 'network';
	if (name === EXIT_PLAN_TOOL || name === ASK_USER_TOOL) return 'ask';
	if (name === 'agent') return 'agent';
	return 'other';
}

/** A short label of a call: a file name, a command, a server's tool, a sub-agent's name. */
export function stepLabel(step: Pick<ToolStep, 'name' | 'arguments'>): string {
	const command = shellCommand(step.arguments);
	if (command) return clip(command.slice(2).split('   ')[0], 60);
	const a = args(step.arguments);
	if (typeof a.path === 'string' && a.path) return clip(baseName(a.path), 60);
	if (step.name.startsWith('mcp__')) return step.name.split('__').slice(2).join('__') || step.name;
	if (step.name === 'agent' && typeof a.agent === 'string') return a.agent;
	return '';
}

/** "exit 1" from a failed command's output; "failed" when it says no code. */
export function failure(step: Pick<ToolStep, 'content'>): string {
	const code = /exit(?:ed)?(?: with)?(?: code| status)?[:= ]+(-?\d+)/i.exec(step.content ?? '');
	if (code) return `exit ${code[1]}`;
	return step.content === 'not run' ? 'not run' : 'failed';
}

/** Lines a write or an edit adds and removes, from its arguments. */
export function lineDelta(step: Pick<ToolStep, 'name' | 'arguments'>): { added: number; removed: number } {
	const a = args(step.arguments);
	const lines = (v: unknown) => (typeof v === 'string' && v ? v.split('\n').length : 0);
	if (step.name === 'edit_file') return { added: lines(a.new_str), removed: lines(a.old_str) };
	if (step.name === 'write_file') return { added: lines(a.content), removed: 0 };
	return { added: 0, removed: 0 };
}

export type Flat = { step: ToolStep; agent?: string; messageId: number };

/** Every tool call of `parts`, a sub-agent's calls right after the call that runs it. */
export function flatten(parts: Part[] | undefined, messageId: number, agent?: string): Flat[] {
	const out: Flat[] = [];
	for (const p of parts ?? []) {
		if (p.kind !== 'tool') continue;
		out.push({ step: p.step, agent, messageId });
		if (p.step.agent) out.push(...flatten(p.step.agent.parts, messageId, p.step.agent.name || 'sub-agent'));
	}
	return out;
}

/** Every tool call of the conversation, answer after answer. */
export function allSteps(messages: readonly Message[]): Flat[] {
	return messages.filter((m) => m.role === 'assistant').flatMap((m) => flatten(m.parts, m.id));
}

export type TodoItem = { text: string; status: 'pending' | 'in_progress' | 'done' };

/** The items of the last `todo_write` call among `steps`; empty when there is none. */
export function todos(steps: Pick<ToolStep, 'name' | 'arguments'>[]): TodoItem[] {
	for (let i = steps.length - 1; i >= 0; i--) {
		if (steps[i].name !== 'todo_write') continue;
		const items = args(steps[i].arguments).items;
		if (!Array.isArray(items)) continue;
		return items.map((it: Record<string, unknown>) => ({
			text: String(it?.text ?? ''),
			status: it?.status === 'done' || it?.status === 'in_progress' ? it.status : 'pending'
		}));
	}
	return [];
}

/** The conversation at a glance: the inspector's Overview. */
export type Summary = {
	answers: number;
	steps: number;
	failed: number;
	reads: number;
	/** Files written or edited (calls), and the lines they added and removed. */
	writes: number;
	added: number;
	removed: number;
	commands: number;
	network: number;
	agents: number;
	/** Tokens of the answers that reported usage; null while none did. */
	tokens: number | null;
	/** Time the answers took (ended ones, plus the running one up to `now`); null before any. */
	elapsed: number | null;
	/** Files touched, most recent first, without repeats. */
	files: { path: string; write: boolean }[];
	todo: TodoItem[];
};

export function summary(messages: readonly Message[], now: number): Summary {
	const answers = messages.filter((m) => m.role === 'assistant');
	const flat = allSteps(messages);
	const s: Summary = {
		answers: answers.length,
		steps: flat.length,
		failed: 0,
		reads: 0,
		writes: 0,
		added: 0,
		removed: 0,
		commands: 0,
		network: 0,
		agents: 0,
		tokens: null,
		elapsed: null,
		files: [],
		todo: todos(flat.map((f) => f.step))
	};
	const files = new Map<string, boolean>();
	for (const { step } of flat) {
		if (step.status === 'error') s.failed++;
		if (step.status === 'drafting') continue;
		const e = effect(step.name);
		if (e === 'read') s.reads++;
		if (e === 'write') {
			s.writes++;
			const d = lineDelta(step);
			s.added += d.added;
			s.removed += d.removed;
		}
		if (e === 'command') s.commands++;
		if (e === 'network') s.network++;
		if (e === 'agent') s.agents++;
		const path = args(step.arguments).path;
		if ((e === 'read' || e === 'write') && typeof path === 'string' && path) {
			const wrote = files.get(path) || e === 'write';
			files.delete(path);
			files.set(path, wrote);
		}
	}
	s.files = [...files].reverse().map(([path, write]) => ({ path, write }));
	for (const m of answers) {
		if (m.usage) s.tokens = (s.tokens ?? 0) + m.usage.promptTokens + m.usage.completionTokens;
		if (m.startedAt === undefined) continue;
		const end = m.endedAt ?? (m.streaming ? now : undefined);
		if (end !== undefined) s.elapsed = (s.elapsed ?? 0) + Math.max(0, end - m.startedAt);
	}
	return s;
}

/** A run of an answer's parts: one part on its own, or consecutive tool calls shown as one group. */
export type Block = { kind: 'part'; part: Part; index: number } | { kind: 'tools'; steps: ToolStep[]; index: number };

/** Groups consecutive tool calls of `parts`, so "text, tool, tool, tool, text" reads as text, 3 calls, text. */
export function blocks(parts: readonly Part[]): Block[] {
	const out: Block[] = [];
	parts.forEach((p, index) => {
		const last = out.at(-1);
		if (p.kind === 'tool') {
			if (last?.kind === 'tools') last.steps.push(p.step);
			else out.push({ kind: 'tools', steps: [p.step], index });
		} else out.push({ kind: 'part', part: p, index });
	});
	return out;
}

function args(text: string): Record<string, unknown> {
	try {
		const a = JSON.parse(text);
		return a && typeof a === 'object' ? (a as Record<string, unknown>) : {};
	} catch {
		return {};
	}
}

function baseName(path: string): string {
	return path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path;
}

function clip(text: string, n: number): string {
	return text.length > n ? `${text.slice(0, n - 1)}…` : text;
}
