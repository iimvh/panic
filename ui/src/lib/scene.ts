import type { Message } from './chat.svelte';
import { activity, ASK_USER_TOOL, EXIT_PLAN_TOOL, shellCommand, thoughtTail, type Part, type ToolStep } from './reply';

/**
 * The agent map: what one answer does, drawn as blocks that stay put (you, Panic's home dock, the model, the
 * permission gate, the workspace, the shell, MCP servers...) joined by links. Panic and its clones walk between them:
 * who stands where is `actors.ts`, how they get there is `motion.ts`. `scene` is a pure function of the answer and a
 * little context, so the map follows the stream live and can replay any step of a finished answer.
 */

export type NodeKind =
	| 'you'
	| 'home'
	| 'model'
	| 'gate'
	| 'workspace'
	| 'shell'
	| 'mcp'
	| 'agent'
	| 'memory'
	| 'skills'
	| 'todo'
	| 'reviewer'
	| 'tool';

/** ghost: not used in this answer yet. dim: cannot be used now (plan mode is read-only). */
export type NodeState = 'ghost' | 'idle' | 'active' | 'waiting' | 'error' | 'dim';
export type Tone = 'ok' | 'warn' | 'error' | 'info' | 'muted';
export type SceneNode = {
	id: string;
	kind: NodeKind;
	label: string;
	/** Position on the stage, in stage units (the map fits its view box to the nodes shown). */
	x: number;
	y: number;
	state: NodeState;
	/** A status label beside the block: the file last touched, `exit 1`, `WAITING FOR YOU`... */
	pill?: { text: string; tone: Tone };
	/** A small count on the block: reads and writes, calls. */
	badge?: string;
	/** Small blocks (memory, skills, todo) are drawn as tiles. */
	small?: boolean;
	/** Where a character stands to work here: in front of the block, clear of its name. */
	spot: { x: number; y: number };
};
export type LinkState = 'ghost' | 'idle' | 'active' | 'waiting' | 'error';
export type SceneLink = { id: string; from: string; to: string; state: LinkState };
/** What a character carries: the kind of work it takes to a block. */
export type PacketKind = 'prompt' | 'read' | 'write' | 'command' | 'network' | 'waiting';
export type Mode = 'ask' | 'plan' | 'auto';
export type Hud = {
	/** ms; null before the answer started */
	elapsed: number | null;
	steps: number;
	reads: number;
	writes: number;
	commands: number;
	/** null while unknown (tokens come with the end of the answer) */
	tokens: number | null;
	mode: Mode;
};
/** One tool call on the step rail, in the order they started; sub-agents' calls included. */
export type RailStep = { id: string; name: string; status: ToolStep['status']; node: string; label: string; agent?: string };
export type Scene = {
	nodes: SceneNode[];
	links: SceneLink[];
	hud: Hud;
	steps: RailStep[];
	/** The step shown (replay), or the one running now; -1 for none. */
	current: number;
	/** One line on what happens now, for the mini strip and the narration. */
	caption: string;
	/** The node to explain in the narration panel, or a kind with no block of its own (`agent`). */
	focus: string;
	/** The agent's checklist as its last `todo_write` (up to the step shown) left it; empty when it wrote none. */
	todo: TodoItem[];
};
/** A thought streaming now: its newest line, and the sub-agent thinking it (none: the main agent). */
export type Thought = { text: string; agent?: string };
export type TodoItem = { text: string; status: 'pending' | 'in_progress' | 'done' };

export type SceneContext = {
	model: string | null;
	connected: boolean;
	mode: Mode;
	now: number;
	/** Show the answer as it was at this step (0-based); unset shows it as it is. */
	atStep?: number;
};

/** Where a tool call goes and what it carries there. */
export type Route = { node: string; kind: NodeKind; label: string; packet: PacketKind; write?: boolean };

/** The block a tool call reaches and the kind of packet it sends; unknown tools land on their own block. */
export function route(name: string): Route {
	if (name === 'read_file' || name === 'list_dir') return { node: 'workspace', kind: 'workspace', label: 'workspace', packet: 'read' };
	if (name === 'write_file' || name === 'edit_file')
		return { node: 'workspace', kind: 'workspace', label: 'workspace', packet: 'write', write: true };
	if (name === 'exec' || name === 'exec_output' || name === 'exec_stop' || name === 'check')
		return { node: 'shell', kind: 'shell', label: 'shell', packet: 'command', write: true };
	if (name.startsWith('mcp__')) {
		const server = name.slice(5).split('__')[0] || 'mcp';
		return { node: `mcp:${server}`, kind: 'mcp', label: server, packet: 'network', write: true };
	}
	if (name.startsWith('memory_'))
		return { node: 'memory', kind: 'memory', label: 'memory', packet: name === 'memory_view' ? 'read' : 'write', write: name !== 'memory_view' };
	if (name === 'skill') return { node: 'skills', kind: 'skills', label: 'skills', packet: 'read' };
	if (name.startsWith('todo_')) return { node: 'todo', kind: 'todo', label: 'todo', packet: name === 'todo_read' ? 'read' : 'write' };
	if (name === EXIT_PLAN_TOOL || name === ASK_USER_TOOL) return { node: 'you', kind: 'you', label: 'you', packet: 'waiting' };
	if (name === 'agent') return { node: 'agent', kind: 'agent', label: 'sub-agent', packet: 'prompt' };
	return { node: `tool:${name}`, kind: 'tool', label: name || 'tool', packet: 'read' };
}

/** Blocks that sit behind the permission gate: their calls pass it. */
const GATED = new Set<NodeKind>(['workspace', 'shell', 'mcp', 'tool']);

/** Half width, half height of the top face, and depth of a block, in stage units. */
export function dims(small?: boolean) {
	return small ? { a: 28, b: 16, d: 12 } : { a: 46, b: 26, d: 24 };
}

/** Where a character stands at a block: on the dock at home, right of your screen, front left of the others. */
function spotOf(kind: NodeKind, x: number, y: number, small: boolean) {
	const d = dims(small);
	if (kind === 'home') return { x, y: y + 2 };
	if (kind === 'you') return { x: x + d.a + 18, y: y + 14 };
	return { x: x - d.a - 10, y: y + d.d + 6 };
}

/** A short label of a call for the rail and the pills: a file name, a command, a server's tool. */
export function stepLabel(step: Pick<ToolStep, 'name' | 'arguments'>): string {
	const command = shellCommand(step.arguments);
	if (command) return clip(command.slice(2).split('   ')[0], 40);
	const a = args(step.arguments);
	if (typeof a.path === 'string' && a.path) return clip(baseName(a.path), 40);
	if (step.name.startsWith('mcp__')) return step.name.split('__').slice(2).join('__') || step.name;
	if (step.name === 'agent' && typeof a.agent === 'string') return a.agent;
	return step.name || 'tool';
}

/** "exit 1" from a failed command's output; "failed" when it says no code. */
export function failure(step: Pick<ToolStep, 'name' | 'content'>): string {
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

export type Flat = { step: ToolStep; agent?: string; parent?: string };

/** Every tool call of the answer in the order they appeared, a sub-agent's calls right after the call running it. */
export function flatten(parts: Part[] | undefined, agent?: string, parent?: string): Flat[] {
	const out: Flat[] = [];
	for (const p of parts ?? []) {
		if (p.kind !== 'tool') continue;
		out.push({ step: p.step, agent, parent });
		if (p.step.agent) out.push(...flatten(p.step.agent.parts, p.step.agent.name || 'sub-agent', p.step.id));
	}
	return out;
}

// stage layout: you on the left, Panic's home in the middle, the model above it, tools behind the gate on the right
const AT: Record<string, [number, number]> = {
	you: [0, 250],
	reviewer: [130, 90],
	home: [260, 250],
	model: [260, 70],
	gate: [470, 250],
	workspace: [680, 120],
	shell: [700, 280],
	memory: [40, 430],
	skills: [130, 460],
	todo: [220, 490]
};

/** The map of answer `m` (undefined: no answer yet, only the setup is drawn). */
export function scene(m: Message | undefined, ctx: SceneContext): Scene {
	const flat = flatten(m?.parts);
	const live = ctx.atStep === undefined && !!m?.streaming;
	const cursor = ctx.atStep === undefined ? -1 : Math.max(0, Math.min(ctx.atStep, flat.length - 1));
	const seen = ctx.atStep === undefined ? flat : flat.slice(0, cursor + 1);
	/** A step's status as the map shows it: the one at the cursor is the one happening. */
	const statusOf = (i: number): ToolStep['status'] => (i === cursor ? 'running' : seen[i].step.status);

	const nodes = new Map<string, SceneNode>();
	const add = (id: string, kind: NodeKind, label: string, at: [number, number], state: NodeState = 'ghost', small = false) => {
		let n = nodes.get(id);
		if (!n) {
			n = { id, kind, label, x: at[0], y: at[1], state, small, spot: spotOf(kind, at[0], at[1], small) };
			nodes.set(id, n);
		}
		return n;
	};
	const links = new Map<string, SceneLink>();
	const link = (from: string, to: string, state: LinkState = 'ghost') => {
		const id = `${from}>${to}`;
		const l = links.get(id) ?? { id, from, to, state };
		if (rank(state) > rank(l.state)) l.state = state;
		links.set(id, l);
		return id;
	};
	const set = (n: SceneNode, state: NodeState) => {
		if (rank(state) > rank(n.state)) n.state = state;
	};

	const you = add('you', 'you', 'you', AT.you, 'idle');
	const home = add('home', 'home', 'home', AT.home, m ? 'idle' : 'ghost');
	const model = add('model', 'model', ctx.model ? shortModel(ctx.model) : 'model', AT.model, m ? 'idle' : 'ghost');
	const gate = add('gate', 'gate', ctx.mode === 'plan' ? 'plan' : ctx.mode === 'auto' ? 'auto-run' : 'ask first', AT.gate);
	const workspace = add('workspace', 'workspace', 'workspace', AT.workspace);
	const shell = add('shell', 'shell', 'shell', AT.shell);
	link('you', 'home', m ? 'idle' : 'ghost');
	link('home', 'model', m ? 'idle' : 'ghost');
	link('home', 'gate');
	link('gate', 'workspace');
	link('gate', 'shell');

	if (!ctx.connected) {
		model.state = 'error';
		model.pill = { text: ctx.model ? 'NOT CONNECTED' : 'NO MODEL', tone: 'error' };
	}
	if (ctx.mode === 'plan') {
		gate.pill = { text: 'READ-ONLY', tone: 'info' };
		shell.state = 'dim';
		workspace.pill = { text: 'read-only', tone: 'muted' };
	} else if (ctx.mode === 'auto') gate.pill = { text: 'AUTO-RUN', tone: 'warn' };

	let mcpCount = 0;
	let toolCount = 0;
	const target = (r: Route): SceneNode => {
		if (r.node === 'workspace') return workspace;
		if (r.node === 'shell') return shell;
		if (r.node === 'you') return you;
		if (nodes.has(r.node)) return nodes.get(r.node)!;
		if (r.kind === 'mcp') return add(r.node, 'mcp', r.label, [700, 420 + 120 * mcpCount++], 'idle');
		if (r.kind === 'tool') return add(r.node, 'tool', r.label, [560 + 120 * toolCount++, 470], 'idle');
		return add(r.node, r.kind, r.label, AT[r.node], 'idle', true);
	};

	let reads = 0;
	let writes = 0;
	let commands = 0;
	let added = 0;
	let removed = 0;
	let focus = m ? 'home' : 'model';
	const calls = new Map<string, number>();
	// the rail lists every call, also those after the step replayed
	const steps: RailStep[] = flat.map(({ step, agent }) => ({
		id: step.id,
		name: step.name,
		status: step.status,
		node: route(step.name).node,
		label: stepLabel(step),
		agent
	}));

	// sub-agents are clones of Panic (see actors.ts): they leave from home as Panic does, and have no block
	seen.forEach(({ step }, i) => {
		const status = statusOf(i);
		const r = route(step.name);
		const from = home;
		if (step.name === 'agent') {
			if (status === 'running' || status === 'drafting') focus = 'agent';
			return;
		}
		const to = target(r);
		const gated = GATED.has(to.kind);
		const hops = gated ? [link(from.id, 'gate', 'idle'), link('gate', to.id, 'idle')] : [link(from.id, to.id, 'idle')];
		if (to !== you) set(to, 'idle');
		if (gated) set(gate, 'idle');
		calls.set(to.id, (calls.get(to.id) ?? 0) + 1);
		if (status !== 'drafting') {
			if (r.packet === 'read') reads++;
			if (r.packet === 'write' && to.kind === 'workspace') {
				writes++;
				const d = lineDelta(step);
				added += d.added;
				removed += d.removed;
			}
			if (r.packet === 'command') commands++;
		}
		const label = stepLabel(step);
		if (status === 'running' || status === 'drafting') {
			for (const h of hops) link(...ends(h), 'active');
			if (gated) set(gate, 'active');
			set(to, 'active');
			if (to.kind === 'workspace' || to.kind === 'mcp' || to.kind === 'tool') to.pill = { text: label, tone: 'info' };
			if (to.kind === 'shell') to.pill = { text: clip(label, 24), tone: 'info' };
			focus = to.id;
		} else if (status === 'error') {
			for (const h of hops) link(...ends(h), 'error');
			if (to !== you) {
				to.state = 'error';
				to.pill = { text: to.kind === 'shell' ? failure(step) : clip(label, 24), tone: 'error' };
			}
			focus = to.id;
		} else {
			// a later success clears an earlier failure on the same block and its links
			if (to.state === 'error') to.state = 'idle';
			for (const h of hops) {
				const l = links.get(h)!;
				if (l.state === 'error') l.state = 'idle';
			}
			if (to.kind === 'workspace') to.pill = { text: label, tone: 'muted' };
			else if (to.kind === 'shell') to.pill = { text: 'exit 0', tone: 'ok' };
			else if (to !== you) to.pill = undefined;
		}
	});

	for (const [id, n] of calls) {
		const node = nodes.get(id)!;
		if (node.kind === 'workspace') {
			const counts = [reads ? `${reads}R` : '', writes ? `${writes}W` : ''].filter(Boolean).join(' ');
			node.badge = added || removed ? `+${added} −${removed}` : counts || undefined;
		} else if (node.kind !== 'you') node.badge = `${n}×`;
	}

	// the reviewer checks the answer before it reaches you
	const reviews = ctx.atStep === undefined ? (m?.parts ?? []).filter((p) => p.kind === 'review') : [];
	if (reviews.length) {
		const last = reviews.at(-1)!;
		const rev = add('reviewer', 'reviewer', 'reviewer', AT.reviewer, 'idle');
		link('home', 'reviewer', 'idle');
		link('reviewer', 'you', 'idle');
		if (last.kind === 'review') {
			const pass = last.pass || last.error !== undefined;
			rev.pill = { text: last.error !== undefined ? 'UNCHECKED' : pass ? '✓ PASSED' : '✕ REJECTED', tone: pass ? 'ok' : 'error' };
			if (!pass) rev.state = 'error';
		}
	}

	// what is happening now, live only
	let caption = m ? (m.streaming ? activity(m) : m.error ? 'Ended with an error' : 'Done') : ctx.connected ? 'Ready' : 'No model connected';
	if (live && m) {
		// home is busy while the answer runs: its light turns while Panic is out
		set(home, 'active');
		const waiting = m.approval ?? undefined;
		if (waiting) {
			gate.state = 'waiting';
			gate.pill = { text: 'WAITING FOR YOU', tone: 'warn' };
			link('gate', 'you', 'waiting');
			focus = 'gate';
		} else if (m.plan || m.question) {
			you.state = 'waiting';
			you.pill = { text: m.plan ? 'PLAN TO APPROVE' : 'QUESTION FOR YOU', tone: 'warn' };
			link('you', 'home', 'waiting');
			focus = 'you';
		}
		const last = m.parts?.at(-1);
		const running = flat.some((f) => f.step.status === 'running' || f.step.status === 'drafting');
		if (!running && !waiting && !m.plan && !m.question) {
			if (!last || last.kind === 'reasoning') {
				set(model, 'active');
				link('home', 'model', 'active');
				model.pill = { text: last ? 'thinking' : 'reading the prompt', tone: 'info' };
				focus = 'model';
			} else if (last.kind === 'text') {
				set(model, 'active');
				link('home', 'model', 'active');
				link('you', 'home', 'active');
				model.pill = { text: 'writing', tone: 'info' };
				focus = 'model';
			}
		}
	}
	if (m?.error && ctx.atStep === undefined && !m.streaming) {
		const lastError = m.parts?.at(-1);
		if (lastError?.kind === 'error' && !flat.some((f) => f.step.status === 'error')) {
			model.state = 'error';
			model.pill = { text: 'FAILED', tone: 'error' };
		}
	}
	if (cursor >= 0 && steps[cursor]) {
		const s = steps[cursor];
		caption = `${s.agent ? `${s.agent}: ` : ''}${verb(s)} ${s.label}`;
	}

	// replayed: the time when the step shown ended, and no tokens (they are only known for the whole answer)
	const shownStep = cursor >= 0 ? seen[cursor]?.step : undefined;
	const end = shownStep ? (shownStep.endedAt ?? shownStep.startedAt) : (m?.endedAt ?? (m?.streaming ? ctx.now : undefined));
	const hud: Hud = {
		elapsed: m?.startedAt !== undefined && end !== undefined ? Math.max(0, end - m.startedAt) : null,
		steps: seen.length,
		reads,
		writes,
		commands,
		tokens: m?.usage && cursor < 0 ? m.usage.promptTokens + m.usage.completionTokens : null,
		mode: ctx.mode
	};
	const current = cursor >= 0 ? cursor : live ? lastRunning(flat) : -1;
	const todo = todos(seen.map((f) => f.step));
	return { nodes: [...nodes.values()], links: [...links.values()], hud, steps, current, caption, focus, todo };
}

/**
 * What the model is thinking now, for the narration: the newest line of the unfinished thought at the end of answer
 * `m`, else of a running sub-agent (with its name). Kept out of `scene` on purpose: it changes with every frame of
 * streamed thinking, and the map should not be rebuilt that often.
 */
export function liveThought(m: Message | undefined): Thought | undefined {
	if (!m?.streaming) return undefined;
	const thinking = (parts: Part[] | undefined) => {
		const last = parts?.at(-1);
		return last?.kind === 'reasoning' && last.endedAt === undefined ? thoughtTail(last.text, 1, 140) : undefined;
	};
	const text = thinking(m.parts);
	if (text) return { text };
	for (let i = (m.parts?.length ?? 0) - 1; i >= 0; i--) {
		const p = m.parts![i];
		if (p.kind !== 'tool' || p.step.status !== 'running' || !p.step.agent) continue;
		const inner = thinking(p.step.agent.parts);
		if (inner) return { text: inner, agent: p.step.agent.name || 'sub-agent' };
	}
	return undefined;
}

/** The items of the last `todo_write` call among `steps`. */
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

/** What the narration panel says a block is, and why it matters. */
export const ROLES: Record<NodeKind, { title: string; idea: string }> = {
	you: { title: 'You', idea: 'Every turn starts and ends with you. The agent stops and asks when a step needs your call.' },
	home: {
		title: 'Panic',
		idea: 'The agent loop: it walks the prompt to the model, carries each tool call it gets back through the gate, and brings the results home, until the model answers.'
	},
	model: { title: 'The model', idea: 'It only writes text and tool calls. Everything it does to your machine goes through Panic and the gate.' },
	gate: {
		title: 'Permission gate',
		idea: 'Each call that writes, runs a command or uses the network passes here. Ask first stops for you, plan mode is read-only, auto-run lets it through.'
	},
	workspace: { title: 'Workspace', idea: 'The project folder. Reads are free; writes and edits change your files and count as changes.' },
	shell: { title: 'Shell', idea: 'Commands run on your machine. A non-zero exit is a failure the model sees and can react to.' },
	mcp: { title: 'MCP server', idea: 'An outside service plugged in through MCP: the only way the agent reaches the network.' },
	agent: {
		title: 'Sub-agent',
		idea: 'A small clone of Panic with its own context. It works on one task, then walks back and merges, handing a summary to Panic.'
	},
	memory: { title: 'Memory', idea: 'Notes kept between chats: what the agent learned about you and the project.' },
	skills: { title: 'Skills', idea: 'Packaged instructions the agent loads when a task needs them.' },
	todo: { title: 'Todo', idea: 'The agent’s own checklist for a longer task, so steps are not forgotten.' },
	reviewer: { title: 'Reviewer', idea: 'Checks the answer before you get it; a rejection sends the agent back to try again.' },
	tool: { title: 'Tool', idea: 'A tool offered by a plugin. Its result goes back to the model as the next input.' }
};

function verb(s: RailStep): string {
	const r = route(s.name);
	if (r.packet === 'read') return 'reads';
	if (r.packet === 'write') return 'writes';
	if (r.packet === 'command') return 'runs';
	if (r.packet === 'network') return 'calls';
	if (r.packet === 'waiting') return 'asks you:';
	return 'starts';
}

function lastRunning(flat: Flat[]): number {
	for (let i = flat.length - 1; i >= 0; i--) if (flat[i].step.status === 'running' || flat[i].step.status === 'drafting') return i;
	return -1;
}

function ends(id: string): [string, string] {
	const [a, b] = id.split('>');
	return [a, b];
}

const RANK = { ghost: 0, dim: 1, idle: 2, active: 3, waiting: 4, error: 5 };
function rank(s: NodeState | LinkState): number {
	return RANK[s];
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

/** "anthropic/claude-x-5" → "claude-x-5". */
function shortModel(model: string): string {
	return clip(model.split('/').pop() || model, 22);
}
