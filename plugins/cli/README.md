# plugins/cli

The terminal front-end. It owns no feature: it runs the commands that other plugins declare.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `cli` | `CliConfig { repl }` | `App.KEY` (provided last, so the REPL starts once the tree is complete) | commands `help`, and `exit` when `repl`; hook `Tools.APPROVE` when `repl` |

With `repl=true` it reads lines from `App.in()`: `/name args` runs a command, plain text runs `ask`. At
end of input or on `exit` it calls `App.exit()`. The app sets `repl` from its flags (`--serve` and one-shot
commands turn it off).

In the REPL it answers `Tools.APPROVE` (from `plugins/tools`) for its own session (`repl`): it prints
`[approve] <tool> (<risk>) <arguments>` (`[approve] <agent>: <tool> …` for a sub-agent's call) and
`allow? [y/N]`, then reads one line. `y` or `yes` allows the call. Anything else, or end of input, denies it. The
REPL thread is blocked in the command that made the call, so only approvals read the terminal meanwhile. Calls
running in parallel (`chat.parallelTools`, sub-agents) may ask at the same time: their questions are put one
after the other, in arrival order. Each wait, for its turn and for the answer, honours the approval's signal
(the caller gave up, or `askTimeoutSeconds` passed). Calls from other sessions are not answered here, and one-shot
mode answers nothing, so its calls that need approval are denied.

`Cli.once(ctx, argv, out, err, signal)` runs a single command for `panic <command> [args]` and returns
the process exit code: `0` ok, `1` failed, `2` unknown command or bad arguments.
