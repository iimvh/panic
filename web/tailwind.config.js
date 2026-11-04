/** @type {import('tailwindcss').Config} */
export default {
	content: ["./index.html", "./src/**/*.{ts,tsx}"],
	theme: {
		extend: {
			fontFamily: {
				sans: ["Inter", "Segoe UI", "Arial", "system-ui", "sans-serif"],
				serif: ["Newsreader", "Georgia", "serif"],
			},
			colors: {
				canvas: "#0B0F1C",
				ink: "#F4F6FB",
				"ink-soft": "#DCE1EE",
				"body-muted": "#A7B0C4",
				muted: "#8D97AD",
				"muted-soft": "#6E7890",
				"on-dark-muted": "#8D97AD",
				surface: "#0D1322",
				"surface-alt": "#111829",
				card: "#161D31",
				"surface-dark": "#161D31",
				"surface-dark-raised": "#2A3450",
				hairline: "rgba(255,255,255,0.08)",
				"hairline-strong": "rgba(255,255,255,0.16)",
				"soft-fill": "#1B2338",
				"soft-fill-hover": "#232C45",
				"cta-fill": "#7B84FF",
				accent: "#7B84FF",
				"accent-hover": "#969DFF",
				"accent-soft": "rgba(123,132,255,0.14)",
				"accent-border": "rgba(123,132,255,0.42)",
				danger: "#F97066",
				"danger-strong": "#F97066",
				"danger-soft": "rgba(249,112,102,0.12)",
				"danger-border": "rgba(249,112,102,0.35)",
			},
			borderRadius: {
				xs: "4px",
				sm: "6px",
				md: "8px",
				lg: "12px",
				xl: "16px",
			},
			boxShadow: {
				panel:
					"0px 2px 2px 0px rgba(0,0,0,0.012), 0px 4px 4px 0px rgba(0,0,0,0.02), 0px 16px 24px 0px rgba(0,0,0,0.04)",
				lift: "0px 4px 24px 0px rgba(0,0,0,0.05)",
			},
			maxWidth: {
				shell: "1400px",
			},
			keyframes: {
				rise: {
					from: { opacity: "0", transform: "translateY(8px)" },
					to: { opacity: "1", transform: "translateY(0)" },
				},
				blink: {
					"0%, 60%, 100%": { opacity: "0.3", transform: "translateY(0)" },
					"30%": { opacity: "1", transform: "translateY(-3px)" },
				},
			},
			animation: {
				rise: "rise 0.32s cubic-bezier(0.22, 1, 0.36, 1) both",
				blink: "blink 1.2s ease-in-out infinite",
			},
		},
	},
	plugins: [],
};
