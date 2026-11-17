# plugins/cli

The terminal front-end. It owns no feature: it runs the commands that other plugins declare.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `cli` | `CliConfig { repl }` | `App.KEY` (provided last, so the REPL starts once the tree is complete) | commands `help`, and `exit` when `repl`; hooks `Tools.APPROVE`, `Planner.APPROVE`, `AskUser.ASK` when `repl` |

With `repl=true` it reads lines from `App.in()`: `/name args` runs a command, plain text runs `ask`. At
end of input or on `exit` it calls `App.exit()`. The app sets `repl` from its flags (`--serve` and one-shot
commands turn it off).

In the REPL it answers `Tools.APPROVE` (from `plugins/tools`) for its own session (`repl`): it prints
`[approve] <tool> (<risk>) <arguments>` (`[approve] <agent>: <tool> …` for a sub-agent's call) and
`allow? [y/N]`, then reads one line. `y` or `yes` allows the call. Anything else, or end of input, denies it.
While the session is in bypass mode (`/bypass on`, hook `Tools.BYPASS`) the policy allows those calls, so no
`[approve]` question appears, and the prompt reads `[bypass] > ` instead of `> ` so the mode is never silent.
One-shot mode has no approver (such calls are denied); set `permissions.bypassPermissions` for scripts. The
REPL thread is blocked in the command that made the call, so only approvals read the terminal meanwhile. Calls
running in parallel (`chat.parallelTools`, sub-agents) may ask at the same time: their questions are put one
after the other, in arrival order. Each wait, for its turn and for the answer, honours the approval's signal
(the caller gave up, or `askTimeoutSeconds` passed). Calls from other sessions are not answered here, and one-shot
mode answers nothing, so its calls that need approval are denied.

It answers `Planner.APPROVE` (from `plugins/planner`) for its session the same way, in the same queue: it prints
`[plan]`, the plan, its numbered steps and its criteria, then `approve the plan? [y/N, or what to change]`. `y` or
`yes` approves it. Any other text goes back to the agent as feedback (`n` or an empty line send it back without
any, so the agent stops and asks), and the conversation stays in plan mode. End of input answers nothing. While the session is in plan mode (`/plan on`, hook `Planner.MODE`)
the prompt reads `[plan] > ` (`[plan] [bypass] > ` with bypass too).

It answers `AskUser.ASK` (the agent's questions, `plugins/interact`) for its session too, in the same queue. Each
question is printed as `[question] [header] text`, then its numbered options (`1. label - description`) and
`0. Other (type your own)`. One line answers it:

- `2` picks an option, and `1,3` (or `1 3`) picks several when the question allows it.
- `0` asks for the user's own text.
- Any other text is the user's own answer.
- An empty line leaves the question unanswered.
- End of input answers nothing.

`Commands.once(ctx, argv, out, err, signal)` (in `sdk`, so the app does not depend on this module) runs a single
command for `panic <command> [args]` and returns the process exit code: `0` ok, `1` failed, `2` unknown command or bad arguments.
