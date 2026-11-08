import path from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
	plugins: [react(), tailwindcss()],
	resolve: {
		alias: {
			"@": path.resolve(__dirname, "./src"),
		},
	},
	server: {
		port: 5173,
		proxy: {
			"/api": "http://localhost:8080",
			"/actuator": "http://localhost:8080",
		},
	},
	build: {
		outDir: "../panic-app/src/main/resources/static",
		emptyOutDir: true,
	},
});
