# ui-v2

The Panic web UI: a SvelteKit SPA (Svelte 5 runes) that talks only to the `web` plugin's JSON + SSE API.
It replaces `ui-v1/` (archived). Until it becomes the default, pack it with `-Pui=ui-v2`.

## Principles

The interface steps back so the agent's work is what you see.

- **Neutral greys carry the interface; one accent marks what you can act on.** Green, amber and red only
  ever mean status.
- **Transparent but quiet.** The model, tokens, tools and time of every answer are shown in one muted line.
  Steps, the trace and a run's totals are one click away in the inspector, never in the way.
- **One place per control.** There is one New chat, one Settings, and the run mode is shown once. What is
  used less often waits in a menu, and anything destructive is confirmed.
- **No decoration.** No gradients, offset shadows, uppercase labels or animation beyond a spinner.
- **The URL is the state.** `/` is a new chat and `/c/<id>` a stored one. `?panel=overview|step|trace`
  (with `&step=<call id>` or `&trace=<trace id>`) opens the inspector, so Back, a reload and a shared link
  keep the context.

## Layers

```
src/lib/core      pure logic (api types and calls, SSE, answer timeline, markdown, summary): no Svelte
src/lib/state     stores (chat, layout, inspector, theme); the only layer that calls the server
src/lib/ui        primitives on Bits UI: Button, IconButton, Dialog, Menu, Select, Tabs, Tooltip, Card, Icon
src/features/*    sidebar, thread, composer, inspector, settings, search, shell; each exports from index.ts
src/routes        +layout (frame, shortcuts), / and c/[id]
```

`src/lib/architecture.test.ts` enforces the direction:
- core imports nothing else;
- ui never imports state or features;
- features use each other only through `index.ts`;
- only state calls the API.

## Design tokens

All colours, type sizes (12/13/14/16/20), radii, spacing and the single shadow live in `src/app.css`, for
light and dark. `src/lib/tokens.test.ts` fails on any of these in a component:
- a colour literal;
- a font size off the scale;
- another shadow;
- uppercase or tracked labels;
- a gradient or a decorative animation.

It also checks WCAG AA contrast of every text token on every surface, in both themes.

## Commands

```bash
npm install
npm run dev          # http://localhost:5173, proxies /api to 127.0.0.1:8080 (run `panic --serve`)
npm run check        # svelte-check
npm test             # vitest: core logic, layer rules, tokens, components
npm run test:e2e     # Playwright on the built app with a scripted fake API (no server, no keys)
npm run budget       # bundle size budget, after npm run build
MEASURE_URLS="v1=http://127.0.0.1:4174,v2=http://127.0.0.1:4173" npm run measure
                     # compares UIs (vite preview of each) on one scripted chat: elements, controls,
                     # colours, type sizes, radii, shadows; writes measure/out/results.md and screenshots
```

Keyboard shortcuts:

| Keys | Action |
| --- | --- |
| Ctrl/Cmd+B | Sidebar |
| Ctrl/Cmd+I | Inspector |
| Ctrl/Cmd+K | Search chats |
| Ctrl/Cmd+Shift+O | New chat |
| Esc | Close |
| ← → | Move between steps in the Steps tab |
