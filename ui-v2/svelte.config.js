import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/**
 * A pure SPA: the Java web plugin serves build/ and falls back to index.html for client routes (/c/<id>).
 * Absolute asset paths, so that fallback page loads /_app/... from any depth.
 */
const config = {
	preprocess: vitePreprocess(),
	kit: {
		adapter: adapter({ fallback: 'index.html' }),
		paths: { relative: false },
		alias: { $features: 'src/features' }
	}
};

export default config;
