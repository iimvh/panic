# ui-v1 (deprecated)

The first Panic web UI: a SvelteKit SPA with the isometric agent map, the step inspector and the trace view.
It is replaced by `ui-v2/`, which the web jar packs by default.

This folder is **deprecated and frozen** at commit `76572c3`. CI does not check it and it is not maintained:
when the `web` plugin's JSON + SSE API changes, this UI is not updated and may stop working. Do not fix bugs
here; change `ui-v2/` instead.

It can still be packed into the jar, as long as the API it was written for holds:

```bash
./gradlew :app:installDist -Pui=ui-v1
```

Or run it beside a running server: `npm install && npm run dev` (proxies `/api` to `127.0.0.1:8080`).
