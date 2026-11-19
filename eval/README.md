# eval: measuring Panic on real tasks

Changes to the agent loop (context management, tools, prompts) are judged by numbers, not by impressions. Two
layers:

- **E1, simulation** (`plugins/chat/src/test/java/panic/chat/ContextPolicyBench.java`): the context-clearing
  policies on a simulated agent, deterministic, no model, runs in `./gradlew check` and writes
  `plugins/chat/build/reports/context-bench.md`.
- **E2, real runs** (this folder): Panic with a real model on fixed tasks, several times each, compared by variant.
  Needs API keys and money, so it never runs in CI.

## Running

```bash
./gradlew :app:installDist && cp -r app/build/install/app eval/.builds/new     # the build to measure
node eval/run.mjs --app eval/.builds/new --variant full --reps 3 --parallel 2  # results in eval/results/full/
node eval/report.mjs base full fix                                             # markdown table
```

- `--variant` picks `variants/<name>.json`, merged over the repo's `panic.json` (its providers, `llm`, `chat` and
  `agents`, so a run uses the model and settings that are being judged) and `base.json` (the fixed environment:
  `maxSteps` 150, permissions bypassed, no web, no MCP).
- `--model provider/model` overrides the model; `--tasks t1,t3` picks tasks; `--label` names the result folder.
- Each run copies its fixture to the system temp folder (outside this repo: Panic reads the `AGENTS.md`/`CLAUDE.md`
  of every parent folder), sends the prompt on stdin (Java reads Windows command lines in the ANSI code page),
  and keeps `result.json`, the traces, the conversations, stdout and the answer. A run that ends on a provider
  error (`[error]` in the output: rate limit, broken stream) is retried up to 3 times and, if it still fails, is
  reported apart (`infra`), never as a failure of the agent.

## Tasks (`tasks.mjs`)

| Task | Fixture | What it measures | Check |
| --- | --- | --- | --- |
| `t1` | `fixtures/mywps` | The prompt that failed on 2026-11-18 (a blog page; 247 steps, 795 reads, no code) | a `*Blog*.jsx` exists, App renders it, `vite build` passes |
| `t2` | `fixtures/mywps` | A small change across three files (a hotline from `content.js` into Header and Footer) | the field exists, both components use it, no hard-coded number, build passes |
| `t3` | `fixtures/mywps` + a planted bug | Finding a cause away from its symptom (`formatPrice` divides by 10) | the division is gone, the data untouched, build passes |
| `t4` | this repo at `07940cd` | Read-only exploration of a large repo | the answer names `clearToolResults`, `ChatPlugin.java`, the threshold and the kept count |

`fixtures/mywps` is a React + Vite landing page (about 66 KB of source) as it was before the run of 2026-11-18
(the blog data that run added to `content.js` was taken out). Its `node_modules` is installed once into
`.cache/` and linked into each run.

## Metrics (`metrics.mjs`)

From the traces (every tool call is a span, kept even when a turn is not saved) and the conversations: `steps`
(model calls, sub-agents included), `reads`, `redundantReads` (the same part of the same file version read again
with no write in between), `writes`, `editFailures`, `searches` (grep/glob), `promptTokens` (the turn's, sub-agents
included), `cachedTokens` (what the provider served from its prompt cache), `refetches` and `unchangedHits` (newer
builds), wall time and `stop`.

## Variants (`variants/`)

| Variant | Build | Overrides |
| --- | --- | --- |
| `base` | before the change (old clearing: every result but 3, once the uncleared turn passed half the budget) | – |
| `fix` | clearing fix | `chat.editing` all off: clearing by age with hysteresis only (what other harnesses do) |
| `full` | clearing fix | `chat.editing` `resources`, `compact` on (its results in `results/full` are from the build that still had pinning and ranking, with `contextTokens` 32000) |
| `no-compact` | clearing fix | `full` without outlines (ablation) |
| `auto` | budget from the model, summaries within a turn | none: `chat.contextTokens` left out, so the budget is the model's window (`llm.contextWindow` in `panic.json` where the provider does not list it) |
| `tight` | same as `auto` | `chat.contextTokens` 32000: the old budget, to measure summaries within a turn under pressure |

Every build after the change also has `grep`/`glob`, the 2000-line `read_file` default and the stall stop, so
`base` → `fix` measures those together; `fix` → `full` and the ablations measure the context mechanisms alone;
`full` → `tight` the removal of pinning and ranking plus summaries within a turn at the same budget; `tight` →
`auto` the budget alone. `metrics.mjs` also reads `contextTokens` (the main turn's budget) and `turnCompactions`;
`report.mjs` shows `peak K`, the largest prompt one call sent.
