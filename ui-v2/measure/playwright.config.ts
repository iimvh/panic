import { defineConfig, devices } from '@playwright/test';

/** `npm run measure`: compares UIs on the same scripted conversation (see compare.spec.ts). Starts nothing. */
export default defineConfig({
	testDir: '.',
	workers: 1,
	reporter: 'list',
	projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
});
