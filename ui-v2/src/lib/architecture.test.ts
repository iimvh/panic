/**
 * The layer rules of ui-v2, checked on the sources (in the spirit of the Java ConventionsTest):
 *
 *   lib/core     pure logic: no Svelte, no browser state, no other layer
 *   lib/state    stores; the only layer that talks to the server (core/api)
 *   lib/ui       primitives: no state, no features
 *   features/*   screens; may use core, state and ui, never another feature's insides
 *   routes       compose features
 *
 * Do not weaken a rule to make a change pass: move the code to the layer it belongs to.
 */
import { describe, expect, it } from 'vitest';

const sources = import.meta.glob('/src/**/*.{ts,svelte}', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const files = Object.entries(sources).filter(([path]) => !path.endsWith('.test.ts'));

/** The import specifiers of a source file. */
function imports(text: string): string[] {
	return [...text.matchAll(/(?:import|export)[^'"`]*?from\s*['"]([^'"]+)['"]|import\s*['"]([^'"]+)['"]/g)].map((m) => m[1] ?? m[2]);
}

/** The layer and (for a feature) its name of a path or an import specifier, resolved from `from`. */
function layer(spec: string, from: string): { layer: string; feature?: string } | null {
	let p = spec;
	if (p.startsWith('$lib/')) p = '/src/lib/' + p.slice(5);
	else if (p.startsWith('$features/')) p = '/src/features/' + p.slice(10);
	else if (p.startsWith('.')) {
		const parts = from.split('/').slice(0, -1);
		for (const seg of p.split('/')) {
			if (seg === '..') parts.pop();
			else if (seg !== '.') parts.push(seg);
		}
		p = parts.join('/');
	} else return null; // a package
	const m = /^\/src\/(?:lib\/(core|state|ui)|(features)\/([^/]+)|(routes))/.exec(p);
	if (!m) return { layer: 'other' };
	if (m[2]) return { layer: 'features', feature: m[3] };
	return { layer: m[1] ?? m[4] };
}

function violations(rule: (from: { layer: string; feature?: string; path: string }, to: { layer: string; feature?: string; spec: string }) => boolean) {
	const out: string[] = [];
	for (const [path, text] of files) {
		const from = layer(path, path);
		if (!from) continue;
		for (const spec of imports(text)) {
			const to = layer(spec, path);
			if (to && rule({ ...from, path }, { ...to, spec })) out.push(`${path} -> ${spec}`);
		}
	}
	return out;
}

describe('architecture', () => {
	it('finds the sources', () => {
		expect(files.length).toBeGreaterThan(10);
	});

	it('keeps core pure', () => {
		expect(violations((f, t) => f.layer === 'core' && t.layer !== 'core')).toEqual([]);
		const svelte = files.filter(([p, t]) => p.startsWith('/src/lib/core/') && (/from\s+['"]svelte/.test(t) || /\$state|\$derived|\$effect/.test(t) || /localStorage|document\./.test(t)));
		expect(svelte.map(([p]) => p)).toEqual([]);
	});

	it('keeps ui primitives free of state and features', () => {
		expect(violations((f, t) => f.layer === 'ui' && (t.layer === 'state' || t.layer === 'features' || t.layer === 'routes'))).toEqual([]);
	});

	it('lets only state talk to the server', () => {
		// type-only imports of api types are fine anywhere; value imports (calls) only in state
		const bad: string[] = [];
		for (const [path, text] of files) {
			if (path.startsWith('/src/lib/state/') || path.startsWith('/src/lib/core/')) continue;
			for (const m of text.matchAll(/import\s+(type\s+)?[^;]*?from\s*['"]([^'"]*core\/api)['"]/g)) if (!m[1]) bad.push(`${path} -> ${m[2]}`);
		}
		expect(bad).toEqual([]);
	});

	it('keeps features apart', () => {
		expect(violations((f, t) => f.layer === 'features' && t.layer === 'features' && t.feature !== f.feature && !/\/features\/[^/]+$|\$features\/[^/]+$/.test(t.spec))).toEqual([]);
	});

	it('never lets lower layers reach up', () => {
		expect(violations((f, t) => f.layer === 'state' && (t.layer === 'ui' || t.layer === 'features' || t.layer === 'routes'))).toEqual([]);
		expect(violations((f, t) => f.layer === 'features' && t.layer === 'routes')).toEqual([]);
	});
});
