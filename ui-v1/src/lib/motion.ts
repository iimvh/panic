import type { Actor, ActorKind, Layout } from './actors';
import type { PacketKind } from './scene';

/**
 * How Panic and its clones walk on the agent map: `actors` says where each should be, this moves them there along
 * the links, one hop at a time, with a short stop at the gate. It never runs behind the answer by much: the more
 * targets are queued, the faster a character walks, and when the rest of its way would take longer than `LAG` (twice
 * that for a single way) it skips to the last stop. A clone that is no longer listed walks back to Panic and is gone when it gets there.
 * Plain TypeScript with the clock passed in, so it can be tested; the map calls `update` when the actors change and
 * `tick` on each animation frame while `tick` says something still moves.
 */

/** A character as drawn now. */
export type Body = {
	id: string;
	kind: ActorKind;
	name?: string;
	x: number;
	y: number;
	carry?: PacketKind;
	waiting?: boolean;
	note?: string;
	thinking?: boolean;
	/** The node it stands at, or the last one it passed. */
	at: string;
	/** Where it is going (its actor's target), or Panic's node while it merges. */
	target: string;
	moving: boolean;
	merging: boolean;
	/** Which way it walks: 1 right, -1 left. */
	facing: 1 | -1;
	/** Distance walked so far: the bounce of its steps follows it. */
	walked: number;
};

type Leg = { node: string; x: number; y: number; pause: number; goal: boolean };
type State = Body & { queue: Leg[]; wait: number; slot: number };

/** Stage units per second, before catching up. */
export const SPEED = 340;
/** ms: queued ways that would take longer than this are cut to their last stop. */
export const LAG = 2000;
/** ms to stop at the gate on the way through. */
export const GATE_PAUSE = 220;
/** Where clones stand around the spot of a block, so they do not cover Panic or each other. */
const SLOTS: [number, number][] = [
	[0, 0],
	[24, 10],
	[-24, 10],
	[2, 22],
	[26, -8],
	[-26, -8],
	[48, 2]
];

export class Motion {
	/** Jump straight to the targets, without walking: reduced motion, or a page that is not shown. */
	jump = false;
	private all = new Map<string, State>();
	private last: number | undefined;

	/** Forgets everyone: another answer, or between live and replay. */
	reset() {
		this.all.clear();
		this.last = undefined;
	}

	/** Takes the actors as they are now: new ones appear, moved targets are walked to, missing clones merge. */
	update(cast: Actor[], map: Layout, now: number) {
		this.last ??= now;
		const listed = new Set(cast.map((a) => a.id));
		for (const a of cast) {
			let b = this.all.get(a.id);
			if (!b) {
				// Panic starts at home; a clone pops out of Panic where Panic is
				const panic = this.all.get('panic');
				const from = a.kind !== 'main' && panic ? { x: panic.x, y: panic.y, at: panic.at } : { ...(map.spot('home') ?? { x: 0, y: 0 }), at: 'home' };
				b = {
					id: a.id,
					kind: a.kind,
					x: from.x,
					y: from.y,
					at: from.at,
					target: from.at,
					moving: false,
					merging: false,
					facing: 1,
					walked: 0,
					queue: [],
					wait: 0,
					slot: a.kind === 'main' ? 0 : this.freeSlot()
				};
				this.all.set(a.id, b);
			}
			b.name = a.name;
			b.carry = a.carry;
			b.waiting = a.waiting;
			b.note = a.note;
			b.thinking = a.thinking;
			if (b.merging) {
				// back to work before it merged: walk on from where it is
				b.merging = false;
				b.queue = [];
				b.target = '';
			}
			if (b.target !== a.target) this.plan(b, a.target, map);
		}
		for (const b of this.all.values()) {
			if (listed.has(b.id) || b.kind === 'main' || b.merging) continue;
			b.merging = true;
			b.queue = [];
			b.wait = 0;
		}
		if (this.jump) this.settle();
	}

	/** Moves everyone by the time since the last call; true while someone still moves. */
	tick(now: number): boolean {
		const dt = Math.max(0, now - (this.last ?? now));
		this.last = now;
		if (this.jump) this.settle();
		const panic = this.all.get('panic');
		for (const b of [...this.all.values()]) {
			if (b.merging) {
				const to = panic ?? b;
				b.target = panic?.at ?? b.at;
				if (this.walk(b, to.x, to.y, (SPEED * 1.5 * dt) / 1000) < 6) this.all.delete(b.id);
				continue;
			}
			let time = dt;
			while (time > 0 && (b.wait > 0 || b.queue.length)) {
				if (b.wait > 0) {
					const w = Math.min(b.wait, time);
					b.wait -= w;
					time -= w;
					continue;
				}
				const leg = b.queue[0];
				const speed = SPEED * (1 + backlog(b.queue));
				const left = Math.hypot(leg.x - b.x, leg.y - b.y);
				const reach = (speed * time) / 1000;
				if (reach < left - 0.01) {
					this.walk(b, leg.x, leg.y, reach);
					time = 0;
				} else {
					this.walk(b, leg.x, leg.y, left);
					time -= (left / speed) * 1000;
					b.at = leg.node;
					b.wait = leg.pause;
					b.queue.shift();
				}
			}
			b.moving = b.queue.length > 0 || b.wait > 0;
		}
		return [...this.all.values()].some((b) => b.moving || b.merging);
	}

	/** Everyone as drawn now, the ones further down last so they stand in front. */
	bodies(): Body[] {
		return [...this.all.values()]
			.map(({ queue: _q, wait: _w, slot: _s, ...b }) => ({ ...b, moving: b.moving || b.merging }))
			.sort((p, q) => p.y - q.y);
	}

	/** Queues the way to `target` after the stops already queued, cut short when it would take too long. */
	private plan(b: State, target: string, map: Layout) {
		b.target = target;
		const from = b.queue.at(-1)?.node ?? b.at;
		const way = map.path(from, target);
		const [dx, dy] = SLOTS[b.slot % SLOTS.length];
		// the way starts where the last stop is; a way of one node (already there, or no way known) is one step
		const stops = way.length > 1 && way[0] === from ? way.slice(1) : way;
		stops.forEach((node, i) => {
			const p = map.spot(node);
			if (!p) return;
			const goal = i === stops.length - 1;
			b.queue.push({ node, x: p.x + dx, y: p.y + dy, pause: node === 'gate' && !goal ? GATE_PAUSE : 0, goal });
		});
		// behind the answer (targets pile up): at most LAG; one long way on its own may take twice that
		const limit = backlog(b.queue) > 0 ? LAG : 2 * LAG;
		if (b.queue.length > 1 && remaining(b) > limit) b.queue = [{ ...b.queue.at(-1)!, pause: 0 }];
		b.moving = b.queue.length > 0;
	}

	/** Puts everyone where they are going at once. */
	private settle() {
		for (const b of [...this.all.values()]) {
			if (b.merging) {
				this.all.delete(b.id);
				continue;
			}
			const end = b.queue.at(-1);
			if (end) {
				b.x = end.x;
				b.y = end.y;
				b.at = end.node;
			}
			b.queue = [];
			b.wait = 0;
			b.moving = false;
		}
	}

	/** Steps `b` up to `by` units towards (x, y); returns the distance still left. */
	private walk(b: State, x: number, y: number, by: number): number {
		const left = Math.hypot(x - b.x, y - b.y);
		if (left <= by || left === 0) {
			b.walked += left;
			b.x = x;
			b.y = y;
			return 0;
		}
		const k = by / left;
		if (Math.abs(x - b.x) > 0.5) b.facing = x > b.x ? 1 : -1;
		b.x += (x - b.x) * k;
		b.y += (y - b.y) * k;
		b.walked += by;
		return left - by;
	}

	private freeSlot(): number {
		const used = new Set([...this.all.values()].filter((b) => !b.merging).map((b) => b.slot));
		let i = 1;
		while (used.has(i)) i++;
		return i;
	}
}

/** Targets queued after the one walked to now: each one more makes the walk faster. */
function backlog(queue: Leg[]): number {
	return Math.max(0, queue.filter((l) => l.goal).length - 1);
}

/** ms the queued way of `b` takes at its current speed, stops included. */
function remaining(b: State): number {
	const speed = SPEED * (1 + backlog(b.queue));
	let x = b.x;
	let y = b.y;
	let ms = b.wait;
	for (const l of b.queue) {
		ms += (Math.hypot(l.x - x, l.y - y) / speed) * 1000 + l.pause;
		x = l.x;
		y = l.y;
	}
	return ms;
}
