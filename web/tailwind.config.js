/** @type {import('tailwindcss').Config} */
export default {
	content: ["./index.html", "./src/**/*.{ts,tsx}"],
	theme: {
		extend: {
			fontFamily: {
				mono: ["JetBrains Mono", "ui-monospace", "SFMono-Regular", "Consolas", "monospace"],
				sans: ["Inter", "Segoe UI", "system-ui", "sans-serif"],
			},
			colors: {
				// Dark "multi-model workspace" palette (web/DESIGN.md vision: terminal dashboard).
				canvas: "#0A0E15",
				panel: "#0E131D",
				"panel-raised": "#121825",
				"panel-head": "#101724",
				edge: "#1C2433",
				"edge-strong": "#2A3444",
				fg: "#DFE7F2",
				"fg-dim": "#8B97AA",
				"fg-faint": "#5B6678",
				// Role tints.
				amber: "#E8A54B",
				blue: "#6FA8FF",
				purple: "#B88CF2",
				green: "#53C48C",
				cyan: "#63C3E4",
				red: "#E5646C",
				// Fills for tinted rows / selections.
				"green-block": "rgba(83,196,140,0.09)",
				"amber-soft": "rgba(232,165,75,0.10)",
				"blue-soft": "rgba(111,168,255,0.10)",
				"purple-soft": "rgba(184,140,242,0.10)",
			},
			animation: {
				"pulse-soft": "pulse 1.6s cubic-bezier(0.4, 0, 0.6, 1) infinite",
				rise: "rise 0.32s cubic-bezier(0.22, 1, 0.36, 1) both",
			},
			keyframes: {
				rise: {
					from: { opacity: "0", transform: "translateY(6px)" },
					to: { opacity: "1", transform: "translateY(0)" },
				},
			},
		},
	},
	plugins: [],
};
