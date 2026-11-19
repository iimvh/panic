/**
 * Measures how much an interface asks of the eye, on the same scripted conversation, for each UI given in
 * MEASURE_URLS (name=url,...). Not part of CI: run it to compare versions (see the README).
 *
 *   MEASURE_URLS="v1=http://127.0.0.1:4174,v2=http://127.0.0.1:4173" npx playwright test -c measure
 */
import { test, type Page } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { FakeApi, scripts, type Ev, type Script } from '../e2e/fake-api';

const urls = (process.env.MEASURE_URLS ?? 'v2=http://127.0.0.1:4173').split(',').map((p) => p.split('=') as [string, string]);
const results: Record<string, Record<string, number | string>> = {};

/** A long run: 200 tool calls, to see what a big conversation costs. */
const big: Script = (q) => {
	if (!q.startsWith('Big run')) return scripts(q);
	const events: Ev[] = [];
	for (let i = 0; i < 200; i++) {
		const call = { id: `b${i}`, name: i % 3 ? 'read_file' : 'exec', arguments: i % 3 ? `{"path":"src/f${i}.ts"}` : `{"command":"npm test -- ${i}"}` };
		events.push({ event: 'tool', data: { ...call, status: 'running' } }, { event: 'tool', data: { ...call, status: 'ok', content: 'ok\n'.repeat(20) } });
		if (i % 20 === 19) events.push({ event: 'delta', data: { text: `Checkpoint ${i + 1}: all good so far.\n\n` } });
	}
	events.push({ event: 'done', data: { model: 'acme/fast-1', usage: { promptTokens: 90000, completionTokens: 4000, reasoningTokens: 0 }, toolCalls: 200 } });
	return { answer: 'done', segments: [() => events] };
};

/** What is on screen: elements, controls, and how many distinct colours, sizes, radii and shadows they use. */
async function census(page: Page) {
	return page.evaluate(() => {
		const all = [...document.querySelectorAll('body *')] as HTMLElement[];
		const visible = all.filter((el) => {
			const r = el.getBoundingClientRect();
			const s = getComputedStyle(el);
			return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight && s.visibility !== 'hidden' && s.opacity !== '0';
		});
		const sets = { color: new Set<string>(), fontSize: new Set<string>(), fontFamily: new Set<string>(), radius: new Set<string>(), shadow: new Set<string>(), uppercase: 0 };
		for (const el of visible) {
			const s = getComputedStyle(el);
			const hasText = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent!.trim());
			if (hasText) {
				sets.color.add(s.color);
				sets.fontSize.add(s.fontSize);
				sets.fontFamily.add(s.fontFamily.split(',')[0]);
				if (s.textTransform === 'uppercase') sets.uppercase++;
			}
			if (s.backgroundColor !== 'rgba(0, 0, 0, 0)') sets.color.add(s.backgroundColor);
			if (s.borderTopWidth !== '0px' && s.borderTopStyle !== 'none') sets.color.add(s.borderTopColor);
			if (s.borderRadius !== '0px') sets.radius.add(s.borderRadius);
			if (s.boxShadow !== 'none') sets.shadow.add(s.boxShadow);
		}
		const controls = visible.filter((el) => el.matches('button, a[href], input, textarea, select, [role="button"], [role="tab"], [role="radio"]'));
		const thread = document.querySelector('[role="log"], .thread, main') as HTMLElement | null;
		const tr = thread?.getBoundingClientRect();
		return {
			domElements: all.length,
			visibleElements: visible.length,
			visibleControls: controls.length,
			colours: sets.color.size,
			fontSizes: sets.fontSize.size,
			fontFamilies: sets.fontFamily.size,
			radii: sets.radius.size,
			shadows: sets.shadow.size,
			uppercaseTextElements: sets.uppercase,
			threadWidthPct: tr ? Math.round((Math.min(tr.right, innerWidth) - Math.max(tr.left, 0)) / innerWidth * 100) : -1
		};
	});
}

async function ask(page: Page, text: string) {
	const box = page.getByRole('textbox').last();
	await box.fill(text);
	await box.press('Enter');
}

for (const [name, url] of urls) {
	test(`measure ${name}`, async ({ page }) => {
		test.setTimeout(120_000);
		const api = new FakeApi(big);
		await api.install(page);
		const r: Record<string, number | string> = {};
		await page.setViewportSize({ width: 1440, height: 900 });

		// start: an empty new chat
		await page.goto(url);
		await page.waitForTimeout(800);
		for (const [k, v] of Object.entries(await census(page))) r[`empty.${k}`] = v;
		await page.screenshot({ path: `measure/out/${name}-empty.png` });

		// one answer with thinking, two tool calls and text
		await ask(page, 'Summarise this project');
		await page.getByText('The project is').first().waitFor();
		await page.waitForTimeout(800);
		for (const [k, v] of Object.entries(await census(page))) r[`answer.${k}`] = v;
		await page.screenshot({ path: `measure/out/${name}-answer.png` });
		await page.emulateMedia({ colorScheme: 'dark' });
		await page.waitForTimeout(300);
		await page.screenshot({ path: `measure/out/${name}-answer-dark.png` });
		await page.emulateMedia({ colorScheme: 'light' });

		// a long run in a new chat: 200 tool calls
		await page.goto(url);
		await page.waitForTimeout(500);
		const t0 = Date.now();
		await ask(page, 'Big run');
		await page.getByText('Checkpoint 200').first().waitFor({ timeout: 60_000 });
		r['big.renderMs'] = Date.now() - t0;
		await page.waitForTimeout(1500);
		const bigCensus = await census(page);
		r['big.domElements'] = bigCensus.domElements;
		r['big.visibleElements'] = bigCensus.visibleElements;
		results[name] = r;
	});
}

test.afterAll(() => {
	const names = Object.keys(results);
	if (!names.length) return;
	const keys = Object.keys(results[names[0]]);
	const rows = [`| metric | ${names.join(' | ')} |`, `| --- | ${names.map(() => '---').join(' | ')} |`, ...keys.map((k) => `| ${k} | ${names.map((n) => results[n][k]).join(' | ')} |`)];
	writeFileSync('measure/out/results.md', rows.join('\n') + '\n');
	console.log(rows.join('\n'));
});
