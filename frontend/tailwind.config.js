/** @type {import('tailwindcss').Config} */
export default {
	content: ["./index.html", "./src/**/*.{ts,tsx}"],
	theme: {
		extend: {
			fontFamily: {
				sans: [
					"Inter",
					"Segoe UI",
					"system-ui",
					"-apple-system",
					"Helvetica Neue",
					"Arial",
					"sans-serif",
				],
			},
			colors: {
				ink: "#0b1020",
				panel: "#10162a",
				accent: {
					50: "#eef2ff",
					100: "#e0e7ff",
					300: "#a5b4fc",
					400: "#818cf8",
					500: "#6366f1",
					600: "#4f46e5",
					700: "#4338ca",
				},
			},
			boxShadow: {
				glow: "0 20px 60px -30px rgba(79, 70, 229, 0.55)",
			},
			keyframes: {
				rise: {
					from: { opacity: "0", transform: "translateY(10px)" },
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
