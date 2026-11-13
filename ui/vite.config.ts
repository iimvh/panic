import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
	plugins: [sveltekit()],
	server: {
		// `npm run dev` next to `panic --serve`: the API comes from the Java server.
		proxy: { '/api': 'http://127.0.0.1:8080' }
	},
	test: {
		include: ['src/**/*.test.ts']
	}
});
