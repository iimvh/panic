# plugins/interact

The agent's ways to interact with the user during a turn. For now that is asking: like Claude Code or Cursor,
the agent can stop and put a few multiple-choice questions to the user (a popup in the web UI, numbered options
in the REPL), wait for the answers, and go on with them in the same turn. Otherwise it would have to end the
turn just to ask.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `ask-user` | `AskUserConfig { timeoutSeconds = 900, maxQuestions = 4, maxOptions = 6 }` | none | tool `ask_user` (read); context section `Asking the user`. Asks hook `AskUser.ASK` |

## Tool

| Tool | Risk | Arguments | Result |
| --- | --- | --- | --- |
| `ask_user` | read | `{questions: [{question, header?, options: [{label, description?}], multiSelect?}]}`: 1 to `maxQuestions` questions, each with 2 to `maxOptions` options whose labels are unique | `The user answered:` and one line per question, `"question" → label, label (other: text)`, or `(no answer)` for a question left blank |

- **The user can always answer in their own words.** Front-ends add an "Other" choice to every question; the
  model does not send it. The text comes back as `(other: ...)`.
- **Risk `read`.** The tool changes nothing, so the permission policy never asks about it, and it stays available
  in plan mode (`plugins/planner`), where asking before planning is most useful.
- **Main agent only.** A sub-agent's call is refused (`sub-agents cannot ask the user ...`), so no sub-agent
  blocks on the user. It reports what needs deciding, and the main agent asks.
- **Waiting.** The tool asks `AskUser.ASK` and waits at most `timeoutSeconds`. It declares its own time limit
  (`Tool.timeoutSeconds`), so `chat.toolTimeoutSeconds` does not cut the wait.
- **No answer.** When nobody answers (no front-end, one-shot mode, the browser left), the wait times out, or the
  user skips, the result is an error: `the user did not answer (no answer|skipped). Do not ask the same again: go
  on with stated assumptions where they are safe, or end your answer and ask in plain words.`
- **Cancellation.** Cancelling the turn, or disposing the plugin, cancels the wait.
- **Bad questions** (none or too many, fewer than 2 options, repeated or blank labels) are refused with an error
  result before anyone is asked.

The section `Asking the user` (order 6) tells the model to ask only for decisions that are the user's, and not
for what its tools can find out.

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `AskUser.ASK` | hook `Request(id, conversationId, questions: [Question(question, header, options: [Option(label, description)], multiSelect)], signal) → Answers(answers: [Answer(selected, other)])` | Puts the questions to the user. Front-ends (`cli`, `web`) answer only for their own conversations and return null otherwise. There is one `Answer` per question, in order; all empty means skipped. A blocking answer must honour `signal` (cancelled when the turn stops or the wait times out) |
| `AskUser.TOOL` | `"ask_user"` | The tool's name, for front-ends that render its call |

## Keys used

| Key / helper | From | Use |
| --- | --- | --- |
| `Tools.register` | `plugins/tools` | Registers `ask_user` |
| `Chat.contribute` | `plugins/chat` | Adds the section `Asking the user` |

It requires no service, and nothing requires it. Without `ask-user`, the agent can only ask in its answer.

## Commands

None.
