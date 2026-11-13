# plugins/cli

The terminal front-end. It owns no feature: it runs the commands that other plugins declare.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `cli` | `CliConfig { repl }` | `App.KEY` (provided last, so the REPL starts once the tree is complete) | commands `help`, and `exit` when `repl` |

With `repl=true` it reads lines from `App.in()`: `/name args` runs a command, plain text runs `ask`. At
end of input or on `exit` it calls `App.exit()`. The app sets `repl` from its flags (`--serve` and one-shot
commands turn it off).

`Cli.once(ctx, argv, out, err, signal)` runs a single command for `panic <command> [args]` and returns
the process exit code: `0` ok, `1` failed, `2` unknown command or bad arguments.
