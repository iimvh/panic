/**
 * The design system's rules, checked on the sources so noise cannot creep back:
 * colours, type sizes and shadows come from the tokens in app.css only, there is no SHOUTING (uppercase), and
 * the text colours meet WCAG AA contrast on every surface, in both themes.
 */
import { describe, expect, it } from 'vitest';
const css = Object.values(import.meta.glob('/src/app.css', { query: '?raw', import: 'default', eager: true }) as Record<string, string>)[0] ?? '';

const sources = import.meta.glob('/src/**/*.svelte', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

/** The <style> blocks and style="" attributes of a component. */
function styles(text: string): string {
	const blocks = [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]);
	const inline = [...text.matchAll(/style(?::[\w-]+)?="([^"]*)"/g)].map((m) => m[1]);
	return [...blocks, ...inline].join('\n');
}

/**
 * The rules, each a pattern of what is not allowed in a component's styles. The lookaheads sit right after the
 * colon, so `\s*` cannot backtrack around them.
 */
const RULES: Record<string, RegExp> = {
	'colour literal': /#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(|\boklch\(/g,
	'font size off the scale': /font-size\s*:(?!\s*(?:var\(--fs-\d\)|inherit)\s*(?:;|$))[^;\n]+/gm,
	'font shorthand off the scale': /\bfont\s*:(?!\s*(?:inherit|var\(--fs-\d\)))[^;\n]+/gm,
	'shadow off the token': /box-shadow\s*:(?!\s*(?:none|var\(--shadow-pop\)|inset 0 0 0 1px var\(--[\w-]+\))\s*(?:;|$))[^;\n]+/gm,
	shouting: /text-transform\s*:\s*uppercase|letter-spacing\s*:\s*0?\.\d+em/g,
	decoration: /gradient\(|@keyframes\s+(?!spin\b)[\w-]+/g
};

/**
 * The one sanctioned exception: the shimmer of status text while the system works (lib/ui/Shimmer.svelte) may
 * use its gradient and its keyframes, and nothing else may. Only those exact pieces are allowed there.
 */
const SHIMMER = '/src/lib/ui/Shimmer.svelte';
const SHIMMER_ALLOWED = [/background:\s*linear-gradient\(90deg, var\(--shimmer-base\)[^;]*var\(--shimmer-glow\)[^;]*\);/, /@keyframes shimmer\b/];

function offenders(rule: RegExp): string[] {
	const out: string[] = [];
	for (const [path, text] of Object.entries(sources)) {
		let style = styles(text);
		if (path === SHIMMER) for (const ok of SHIMMER_ALLOWED) style = style.replace(ok, '');
		const found = [...style.matchAll(rule)];
		if (found.length) out.push(`${path}: ${found.map((m) => m[0]).join(', ')}`);
	}
	return out;
}

const hits = (rule: RegExp, style: string) => [...style.matchAll(rule)].length > 0;

describe('tokens', () => {
	it('finds the components', () => {
		expect(Object.keys(sources).length).toBeGreaterThan(5);
	});

	for (const [name, rule] of Object.entries(RULES)) {
		it(`has no ${name}`, () => {
			expect(offenders(rule)).toEqual([]);
		});
	}

	it('keeps the shimmer in its primitive', () => {
		// a copy elsewhere (text clipped to a moving background) would spread motion past what was agreed
		const copies = Object.entries(sources).filter(([path, text]) => path !== SHIMMER && /background-clip\s*:\s*text|ui-shimmer/.test(styles(text)));
		expect(copies.map(([p]) => p)).toEqual([]);
		expect(styles(sources[SHIMMER])).toMatch(/prefers-reduced-motion: reduce/);
	});

	it('catches what it should, and only that', () => {
		const bad: [string, string][] = [
			['colour literal', 'color: #fff;'],
			['colour literal', 'background: rgb(0 0 0 / .3);'],
			['font size off the scale', 'font-size: 13px;'],
			['font size off the scale', 'font-size: 0.9em;'],
			['font shorthand off the scale', 'font: 12px var(--mono);'],
			['shadow off the token', 'box-shadow: 1px 2px 0 var(--text);'],
			['shadow off the token', 'box-shadow: var(--shadow-pop), 0 0 1px red;'],
			['shouting', 'text-transform: uppercase;'],
			['decoration', 'background: linear-gradient(red, blue);'],
			['decoration', '@keyframes pulse {']
		];
		const good: [string, string][] = [
			['colour literal', 'color: var(--text);'],
			['font size off the scale', 'font-size: var(--fs-2);'],
			['font shorthand off the scale', 'font: inherit;'],
			['shadow off the token', 'box-shadow: var(--shadow-pop);'],
			['shadow off the token', 'box-shadow: inset 0 0 0 1px var(--border);'],
			['decoration', '@keyframes spin {']
		];
		for (const [name, style] of bad) expect(hits(RULES[name], style), `${name} should catch ${style}`).toBe(true);
		for (const [name, style] of good) expect(hits(RULES[name], style), `${name} should allow ${style}`).toBe(false);
	});
});

/** The value of `--name` in the CSS block that starts with `selector`. */
function token(selector: string, name: string): string {
	const start = css.indexOf(selector);
	const block = css.slice(start, css.indexOf('}', start));
	const m = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`).exec(block);
	if (!m) throw new Error(`no --${name} in ${selector}`);
	return m[1];
}

function luminance(hex: string): number {
	const [r, g, b] = [1, 3, 5].map((i) => {
		const c = parseInt(hex.slice(i, i + 2), 16) / 255;
		return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
	});
	return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
	const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
	return (x + 0.05) / (y + 0.05);
}

describe('contrast (WCAG AA, 4.5:1 for text)', () => {
	const themes = { light: ':root {', dark: ":root[data-theme='dark']" };
	for (const [theme, selector] of Object.entries(themes)) {
		it(`reads well in ${theme}`, () => {
			const t = (n: string) => token(selector, n);
			const low: string[] = [];
			for (const bg of ['bg', 'surface', 'surface-2', 'accent-soft'])
				// --shimmer-glow is not here on purpose: it is the light passing over --shimmer-base for a moment
				for (const fg of ['text', 'text-2', 'muted', 'accent', 'ok', 'warn', 'danger', 'shimmer-base']) {
					const c = contrast(t(fg), t(bg));
					if (c < 4.5) low.push(`${fg} on ${bg}: ${c.toFixed(2)}`);
				}
			const button = contrast(t('accent-fg'), t('accent-bg'));
			if (button < 4.5) low.push(`accent-fg on accent-bg: ${button.toFixed(2)}`);
			expect(low).toEqual([]);
		});

		it(`makes the shimmer's light visible in ${theme}`, () => {
			// the band has to stand out from the text it crosses, or nothing seems to move
			const t = (n: string) => token(selector, n);
			expect(contrast(t('shimmer-glow'), t('shimmer-base'))).toBeGreaterThan(2.5);
		});
	}

	it('keeps the two dark blocks identical', () => {
		const block = (sel: string) => {
			const start = css.indexOf(sel);
			return css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start)).replace(/\s+/g, ' ').trim();
		};
		expect(block(":root:not([data-theme='light'])")).toBe(block(":root[data-theme='dark']"));
	});
});
