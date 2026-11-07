/** @type {import('tailwindcss').Config} */
export default {
	content: ["./index.html", "./src/**/*.{ts,tsx}"],
	darkMode: "class",
	theme: {
		extend: {
			fontFamily: {
				sans: ["Plus Jakarta Sans", "Inter", "system-ui", "sans-serif"],
				mono: ["JetBrains Mono", "ui-monospace", "SFMono-Regular", "Consolas", "monospace"],
			},
			colors: {
				// Paired light/dark palette (Japanese Minimalist Architecture):
				// each token is a CSS variable that flips under `.dark`, so one
				// utility class covers both themes.
				canvas: "var(--panic-canvas)",
				surface: "var(--panic-surface)",
				"surface-raised": "var(--panic-surface-raised)",
				edge: "var(--panic-edge)",
				"edge-soft": "var(--panic-edge-soft)",
				fg: "var(--panic-fg)",
				"fg-body": "var(--panic-fg-body)",
				"fg-dim": "var(--panic-fg-dim)",
				"fg-mute": "var(--panic-fg-mute)",
				"fg-faint": "var(--panic-fg-faint)",
				// Single hue of the system: terracotta accent.
				accent: "#C84B31",
			},
			animation: {
				"cursor-blink": "cursor-blink 700ms ease-in-out infinite",
				shimmer: "shimmer-sweep 1.5s linear infinite",
				"shimmer-text": "shimmer-text-sweep 1.4s linear infinite",
				rise: "rise 0.32s cubic-bezier(0.22, 1, 0.36, 1) both",
			},
			keyframes: {
				"cursor-blink": {
					"0%, 100%": { opacity: "1" },
					"50%": { opacity: "0.1" },
				},
				// Band sweep for background overlays: the band enters and exits the element.
				"shimmer-sweep": {
					from: { backgroundPosition: "200% 0" },
					to: { backgroundPosition: "-100% 0" },
				},
				// Sweep for gradient-clipped text: with a 200% background the window must
				// always stay inside the image, so the letters never lose their paint.
				"shimmer-text-sweep": {
					from: { backgroundPosition: "100% 0" },
					to: { backgroundPosition: "0% 0" },
				},
				rise: {
					from: { opacity: "0", transform: "translateY(6px)" },
					to: { opacity: "1", transform: "translateY(0)" },
				},
			},
		},
	},
	plugins: [],
};
