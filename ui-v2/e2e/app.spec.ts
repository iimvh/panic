import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { FakeApi, scripts } from './fake-api';

let api: FakeApi;

test.beforeEach(async ({ page }) => {
	api = new FakeApi(scripts);
	await api.install(page);
});

async function ask(page: Page, text: string) {
	const box = page.getByLabel('Message');
	await box.fill(text);
	await box.press('Enter');
}

test('asks, shows tool calls folded and a quiet meta line, and gets its own route', async ({ page }) => {
	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'What should Panic do?' })).toBeVisible();
	await ask(page, 'Summarise this project');
	await expect(page.getByText('The project is Panic.')).toBeVisible();
	await expect(page).toHaveURL(/\/c\/[0-9a-f]{24}$/);
	// two calls fold into one line; thinking is one line too
	await expect(page.getByRole('button', { name: '2 tool calls' })).toHaveAttribute('aria-expanded', 'false');
	await expect(page.getByRole('button', { name: 'Thought' })).toBeVisible();
	await expect(page.getByRole('button', { name: /acme\/fast-1 · 1\.3k tokens · 2 tools/ })).toBeVisible();
	// the chat is listed, current
	await expect(page.getByRole('link', { name: 'Summarise this project', exact: true })).toHaveAttribute('aria-current', 'page');
});

test('opens a step and the trace in the inspector, and keeps them across a reload', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Summarise this project');
	await page.getByRole('button', { name: '2 tool calls' }).click();
	await page.getByRole('button', { name: /read_file README\.md/ }).click();
	await expect(page).toHaveURL(/\?panel=step&step=t2$/);
	await expect(page.getByText('Step 2 of 2')).toBeVisible();
	await expect(page.locator('pre', { hasText: '# Panic' })).toBeVisible();
	// ← moves to the previous step
	await page.getByRole('region', { name: /Tool call 2 of 2/ }).focus();
	await page.keyboard.press('ArrowLeft');
	await expect(page.getByText('Step 1 of 2')).toBeVisible();

	await page.getByRole('button', { name: /tokens/ }).click();
	await expect(page).toHaveURL(/panel=trace/);
	await expect(page.getByRole('button', { name: /llm/ })).toBeVisible();

	await page.reload();
	await expect(page.getByRole('tab', { name: 'Trace' })).toHaveAttribute('data-state', 'active');
	await expect(page.getByText('The project is Panic.')).toBeVisible();
});

test('keeps what is on screen when a new chat gets its route', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Run the tests');
	// typed while the first answer starts: the route becomes /c/<id> under it, and nothing is rebuilt
	await page.getByLabel('Message').fill('a draft');
	await expect(page).toHaveURL(/\/c\/[0-9a-f]{24}$/);
	await expect(page.getByRole('region', { name: 'Approve tool call' })).toBeVisible();
	await expect(page.getByLabel('Message')).toHaveValue('a draft');
});

test('allows a tool call', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Run the tests');
	const card = page.getByRole('region', { name: 'Approve tool call' });
	await expect(card).toContainText('npm test');
	await expect(card).toContainText('Runs a command');
	await card.getByRole('button', { name: 'Allow' }).click();
	await expect(page.getByText('All 12 tests pass.')).toBeVisible();
	await expect(card).toBeHidden();
});

test('denies a tool call', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Run the tests');
	await page.getByRole('region', { name: 'Approve tool call' }).getByRole('button', { name: 'Deny' }).click();
	await expect(page.getByText('You denied running the tests.')).toBeVisible();
	await expect(page.getByText('denied by the user', { exact: false })).toHaveCount(0); // the result is in the inspector, not the thread
	await expect(page.getByRole('button', { name: /exec.*failed/ })).toBeVisible();
});

test('approves a plan, or sends it back with feedback', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Plan a cache');
	const card = page.getByRole('region', { name: 'Approve the plan' });
	await expect(card).toContainText('Write the cache');
	await card.getByRole('button', { name: 'Keep planning' }).click();
	await card.getByLabel('What should change in the plan?').fill('Use Redis');
	await card.getByRole('button', { name: 'Send back' }).click();
	await expect(page.getByText('Revising: Use Redis')).toBeVisible();
});

test('answers the questions the agent asks', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Ask me which database');
	const card = page.getByRole('region', { name: 'Panic asks' });
	await expect(card.getByRole('button', { name: 'Submit' })).toBeDisabled();
	await card.getByLabel('SQLite').check();
	await card.getByRole('button', { name: 'Submit' }).click();
	await expect(page.getByText('Using SQLite.')).toBeVisible();
});

test('offers Continue after a limit', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Work long');
	await page.getByRole('button', { name: 'Continue' }).click();
	await expect(page.getByText('Hello.')).toBeVisible();
});

test('switches the run mode, confirming Auto-run', async ({ page }) => {
	await page.goto('/');
	await page.getByRole('button', { name: /Mode of this chat: Ask first/ }).click();
	await page.getByRole('menuitemradio', { name: /Auto-run/ }).click();
	const dialog = page.getByRole('dialog', { name: 'Run tools without asking?' });
	await expect(dialog).toBeVisible();
	await dialog.getByRole('button', { name: 'Auto-run' }).click();
	await expect(page.getByRole('button', { name: /Mode of this chat: Auto-run/ })).toBeVisible();
});

test('runs commands and suggests them', async ({ page }) => {
	await page.goto('/');
	await page.getByLabel('Message').fill('/he');
	await expect(page.getByRole('option', { name: /\/help/ })).toBeVisible();
	await page.getByLabel('Message').press('Tab');
	await page.getByLabel('Message').press('Enter');
	await expect(page.getByText('Commands: /help, /models, /plan')).toBeVisible();
});

test('deletes a chat after confirming, and searches chats', async ({ page }) => {
	await page.goto('/');
	await ask(page, 'Hello there');
	await expect(page.getByText('Hello.')).toBeVisible();
	await page.keyboard.press('Control+k');
	const search = page.getByRole('dialog', { name: 'Search chats' });
	await search.getByRole('combobox').fill('hello');
	await expect(search.getByRole('option', { name: /Hello there/ })).toBeVisible();
	await page.keyboard.press('Escape');

	await page.getByRole('link', { name: 'Hello there' }).hover();
	await page.getByRole('button', { name: 'Delete "Hello there"' }).click();
	await page.getByRole('dialog', { name: 'Delete chat?' }).getByRole('button', { name: 'Delete' }).click();
	await expect(page).toHaveURL(/\/$/);
	await expect(page.getByRole('link', { name: 'Hello there' })).toHaveCount(0);
});

test('changes the model in Settings', async ({ page }) => {
	await page.goto('/');
	await page.getByRole('button', { name: 'acme/fast-1' }).click();
	const dialog = page.getByRole('dialog', { name: 'Settings' });
	await dialog.getByRole('button', { name: 'Browse' }).click();
	await dialog.getByRole('button', { name: /acme\/deep-2/ }).click();
	await expect(dialog.getByLabel('Model', { exact: true })).toHaveValue('acme/deep-2');
	await dialog.getByRole('radio', { name: 'Dark' }).click();
	await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});

test('a deep link with an unknown id falls back cleanly; a bad one goes home', async ({ page }) => {
	await page.goto('/c/not!valid');
	await expect(page).toHaveURL(/\/$/);
	await page.goto('/c/0123456789abcdef01234567?panel=overview');
	await expect(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('data-state', 'active');
});

test('tells when the server is gone, and recovers', async ({ page }) => {
	await page.goto('/');
	api.offline = true;
	await page.getByLabel('Message').fill('Hello');
	await page.getByLabel('Message').press('Enter');
	await expect(page.getByRole('alert').filter({ hasText: /Cannot reach the Panic server/ })).toBeVisible();
	api.offline = false;
	await page.getByRole('button', { name: 'Retry' }).click();
	await expect(page.getByRole('alert').filter({ hasText: /Cannot reach/ })).toHaveCount(0);
});

for (const scheme of ['light', 'dark'] as const) {
	test(`has no accessibility violations (${scheme})`, async ({ page }) => {
		await page.emulateMedia({ colorScheme: scheme });
		await page.goto('/');
		await ask(page, 'Summarise this project');
		await expect(page.getByText('The project is Panic.')).toBeVisible();
		await page.keyboard.press('Control+i');
		await expect(page.getByRole('tab', { name: 'Overview' })).toBeVisible();
		const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
		expect(result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
	});
}
