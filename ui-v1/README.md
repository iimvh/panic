# ui-v1 (archived)

The first Panic web UI: a SvelteKit SPA with the isometric agent map, the step inspector and the trace view.

It is **frozen** at commit `76572c3` and no longer maintained. The `web` plugin's JSON + SSE API may change
without this folder following it. Its successor is `ui-v2/`.

Until `ui-v2` becomes the default, the web jar still packs this folder. Afterwards it can be packed with:

```bash
./gradlew :app:installDist -Pui=ui-v1
```

Development as before: `npm install && npm run dev` (proxies `/api` to `127.0.0.1:8080`),
`npm run check && npm test`.
