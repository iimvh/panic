import { describe, expect, it } from 'vitest';
import type { Actor, Layout } from './actors';
import { GATE_PAUSE, LAG, Motion, SPEED } from './motion';

// a line of stops 300 units apart: home, gate, shell (one second each at SPEED), and model above home
const SPOTS: Record<string, { x: number; y: number }> = {
	home: { x: 0, y: 0 },
	gate: { x: 300, y: 0 },
	shell: { x: 600, y: 0 },
	far: { x: 3000, y: 0 },
	model: { x: 0, y: -300 }
};
const map: Layout = {
	spot: (id) => SPOTS[id],
	path(from, to) {
		const line = ['model', 'home', 'gate', 'shell', 'far'];
		const [i, j] = [line.indexOf(from), line.indexOf(to)];
		if (i < 0 || j < 0) return [to];
		return i <= j ? line.slice(i, j + 1) : line.slice(j, i + 1).reverse();
	}
};
const panic = (target: string, more: Partial<Actor> = {}): Actor => ({ id: 'panic', kind: 'main', target, ...more });
const body = (m: Motion, id = 'panic') => m.bodies().find((b) => b.id === id)!;
const SEC = (300 / SPEED) * 1000;

describe('Motion', () => {
	it('walks hop by hop, stopping at the gate on the way', () => {
		const m = new Motion();
		m.update([panic('shell')], map, 0);
		expect(body(m)).toMatchObject({ x: 0, at: 'home', moving: true });
		m.tick(SEC / 2);
		expect(body(m).x).toBeCloseTo(150);
		m.tick(SEC);
		expect(body(m)).toMatchObject({ x: 300, at: 'gate', moving: true });
		m.tick(SEC + GATE_PAUSE);
		expect(body(m).x).toBeCloseTo(300);
		expect(m.tick(2 * SEC + GATE_PAUSE)).toBe(false);
		expect(body(m)).toMatchObject({ x: 600, at: 'shell', moving: false, facing: 1 });
	});

	it('walks faster when more targets are queued', () => {
		const m = new Motion();
		m.update([panic('gate')], map, 0);
		m.update([panic('home')], map, 0);
		// two targets queued: twice the speed on the first one
		m.tick(SEC / 4);
		expect(body(m).x).toBeCloseTo(150);
	});

	it('skips to the last stop when the way would take longer than the lag allowed', () => {
		const m = new Motion();
		m.update([panic('far')], map, 0);
		expect((3000 / SPEED) * 1000).toBeGreaterThan(LAG);
		// the stops in between are dropped: one straight walk, no pause at the gate
		m.tick((300 / SPEED) * 1000 + 1);
		expect(body(m).at).toBe('home');
		expect(body(m).x).toBeGreaterThan(300);
	});

	it('pops a clone out of Panic and merges it back when it is no longer listed', () => {
		const m = new Motion();
		m.update([panic('home')], map, 0);
		m.update([panic('home'), { id: 'p:b', kind: 'parallel', target: 'gate', carry: 'command' }], map, 0);
		const clone = body(m, 'p:b');
		expect(clone).toMatchObject({ at: 'home', carry: 'command' });
		m.tick(2 * SEC);
		expect(body(m, 'p:b').at).toBe('gate');
		m.update([panic('home')], map, 2 * SEC);
		expect(body(m, 'p:b').merging).toBe(true);
		expect(m.tick(2 * SEC + 50)).toBe(true);
		m.tick(4 * SEC);
		expect(m.bodies().map((b) => b.id)).toEqual(['panic']);
	});

	it('jumps straight there with reduced motion, and starts over on reset', () => {
		const m = new Motion();
		m.jump = true;
		m.update([panic('shell'), { id: 'p:x', kind: 'parallel', target: 'gate' }], map, 0);
		expect(body(m)).toMatchObject({ x: 600, at: 'shell', moving: false });
		expect(m.tick(1)).toBe(false);
		m.update([panic('shell')], map, 2);
		expect(m.bodies()).toHaveLength(1);
		m.reset();
		m.jump = false;
		m.update([panic('model')], map, 10);
		expect(body(m)).toMatchObject({ x: 0, y: 0, at: 'home', moving: true });
	});
});
