import type { Message } from './chat.svelte';
import { flatten, route, stepLabel, type Flat, type PacketKind, type Scene } from './scene';

/**
 * Who is where on the agent map now: Panic and its clones, and the block each of them should be at. A pure function
 * of the answer, like `scene`; how they walk there is `motion.ts`.
 *
 * - Panic goes to the block of the call it runs, through the gate when the block is behind it. It waits at the gate
 *   for an approval and at your screen for a plan or a question. Between calls it is at the model, and at home when
 *   the answer is done or only its helpers work.
 * - Each more call running at once gets a small clone (`parallel`), each sub-agent a small named clone (`agent`) that
 *   goes to the block of its own call, or to the model while it thinks. A clone whose work ended is no longer listed:
 *   motion walks it back to Panic, where it merges.
 */

export type ActorKind = 'main' | 'agent' | 'parallel';
export type Actor = {
	/** `panic`, `a:<call id>` for a sub-agent, `p:<call id>` for a call running beside Panic's. */
	id: string;
	kind: ActorKind;
	/** A sub-agent's name. */
	name?: string;
	/** The node to stand at. */
	target: string;
	/** The packet held over its head. */
	carry?: PacketKind;
	/** Waiting for you: at the gate (approval) or at your screen (plan, question). */
	waiting?: boolean;
	/** What it waits on, for its bubble: the call to approve. */
	note?: string;
	/** At the model while the model thinks: its bubble shows the thought. */
	thinking?: boolean;
};

const running = (f: Flat) => f.step.status === 'running' || f.step.status === 'drafting';

/** Who stands where in answer `m` (live), or at step `atStep` of it (replay). */
export function actors(m: Message | undefined, ctx: { atStep?: number }): Actor[] {
	if (!m) return [{ id: 'panic', kind: 'main', target: 'home' }];
	const flat = flatten(m.parts);
	const replay = ctx.atStep !== undefined;
	const live = !replay && !!m.streaming;
	let busy: Flat[];
	if (replay) {
		const at = flat[Math.max(0, Math.min(ctx.atStep!, flat.length - 1))];
		busy = at ? [at] : [];
	} else busy = live ? flat.filter(running) : [];

	// a sub-agent works while its own call runs, or (replayed) while one of its calls is the step shown
	const agentIds = new Set<string>();
	for (const f of busy) {
		if (f.step.name === 'agent' && !f.parent) agentIds.add(f.step.id);
		// live, a call left running inside a sub-agent that ended does not keep its clone out
		if (f.parent && (replay || flat.some((g) => g.step.id === f.parent && running(g)))) agentIds.add(f.parent);
	}
	const approval = live ? m.approval : undefined;
	const visit = (f: Flat): Pick<Actor, 'target' | 'carry' | 'waiting' | 'note'> => {
		const r = route(f.step.name);
		if (approval?.callId === f.step.id) return { target: 'gate', carry: r.packet, waiting: true, note: stepLabel(f.step) };
		return { target: r.node === 'agent' ? 'home' : r.node, carry: r.packet };
	};

	const out: Actor[] = [];
	const own = busy.filter((f) => !f.parent && f.step.name !== 'agent');
	let main: Actor;
	if (live && (m.plan || m.question)) main = { id: 'panic', kind: 'main', target: 'you', carry: 'waiting', waiting: true };
	else if (own.length) main = { id: 'panic', kind: 'main', ...visit(own[0]) };
	else if (live && agentIds.size === 0) {
		const last = m.parts?.at(-1);
		const thinking = last?.kind === 'reasoning' && last.endedAt === undefined;
		main = { id: 'panic', kind: 'main', target: 'model', carry: 'prompt', thinking };
	} else main = { id: 'panic', kind: 'main', target: 'home' };
	out.push(main);

	for (const f of own.slice(1)) out.push({ id: `p:${f.step.id}`, kind: 'parallel', ...visit(f) });
	for (const id of agentIds) {
		const call = flat.find((f) => f.step.id === id);
		if (!call) continue;
		const name = call.step.agent?.name || stepLabel(call.step) || 'sub-agent';
		const work = busy.find((f) => f.parent === id);
		if (work) out.push({ id: `a:${id}`, kind: 'agent', name, ...visit(work) });
		else {
			const last = call.step.agent?.parts?.at(-1);
			const thinking = live && last?.kind === 'reasoning' && last.endedAt === undefined;
			out.push({ id: `a:${id}`, kind: 'agent', name, target: 'model', carry: 'prompt', thinking });
		}
	}
	return out;
}

/** The blocks of a scene as places to walk between: where to stand at each, and the way from one to another. */
export type Layout = {
	spot(id: string): { x: number; y: number } | undefined;
	/** The nodes to walk through from `from` to `to`, both included; just `[to]` when no way is known. */
	path(from: string, to: string): string[];
};

/**
 * The ways of scene `s`: its links, both directions, but not the dashed ones that only show someone waiting for
 * you. Blocks behind the gate are only linked to the gate, so every way to them goes through it.
 */
export function layout(s: Pick<Scene, 'nodes' | 'links'>): Layout {
	const spots = new Map(s.nodes.map((n) => [n.id, n.spot]));
	const next = new Map<string, string[]>();
	const join = (a: string, b: string) => next.set(a, [...(next.get(a) ?? []), b]);
	for (const l of s.links) {
		if (l.state === 'waiting' || !spots.has(l.from) || !spots.has(l.to)) continue;
		join(l.from, l.to);
		join(l.to, l.from);
	}
	return {
		spot: (id) => spots.get(id),
		path(from, to) {
			if (from === to) return [to];
			// breadth first: the fewest hops
			const prev = new Map<string, string>([[from, from]]);
			const queue = [from];
			while (queue.length) {
				const at = queue.shift()!;
				if (at === to) break;
				for (const n of next.get(at) ?? []) {
					if (prev.has(n)) continue;
					prev.set(n, at);
					queue.push(n);
				}
			}
			if (!prev.has(to)) return [to];
			const way = [to];
			while (way[0] !== from) way.unshift(prev.get(way[0])!);
			return way;
		}
	};
}
