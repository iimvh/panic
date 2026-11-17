# plugins/planner

Plan mode, as in Claude Code or Cursor. While a conversation is in plan mode, the agent only reads and researches,
then proposes a plan. You approve it, or send it back with feedback. Once approved, the mode ends, the plan becomes
the conversation's task list and acceptance criteria (`plugins/todo`), and the agent carries it out with all its
tools in the same turn. Every plan is also saved as a markdown file in the project the conversation works in.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `planner` | `PlannerConfig { byDefault = false, allow = [], approveTimeoutSeconds = 900, dir = ".panic/plans", root = "." }` | none | answers `Tools.GATE`; tool `exit_plan_mode` (read); context sections `Plan mode`, `Approved plan`; hook `Planner.MODE`; event `Planner.CHANGED`; command `plan`. Asks hooks `Planner.APPROVE`, `Workspace.ROOT`, `Todo.WRITE` |

- `byDefault`: every conversation starts in plan mode. A conversation's own switch (command `plan`) overrides
  this default in both directions.
- `allow`: tools allowed in plan mode besides the read-risk ones, by name. The default is none. The approved
  plan's steps become the task list by themselves, and a task list written while planning tempted a model to mark
  steps done that never ran.
- `approveTimeoutSeconds`: how long a proposed plan waits for the user. After that it counts as no answer.
- `dir`: where plan files go, relative to the conversation's project folder.
- `root`: the folder used instead of a project when the conversation has none (relative to the app's working
  directory).

## How it works

1. **Switch it on.** Run `plan on` (the web UI has a header switch, and the REPL prompt reads `[plan] > `).
2. **Read-only.** `planner` answers `Tools.GATE` (`plugins/tools`) for the conversation. Tools of risk `read`,
   plus those listed in `allow`, pass.
   - **Every other call is refused**, even in bypass mode, with `<tool>: refused: plan mode is on: nothing can be
     changed until the user approves a plan. Call exit_plan_mode with your plan now ...`.
   - **Those tools stay offered** (the refusal does not `hide` them). A model that tries to act learns why it
     cannot and what to do, instead of hunting for another way, such as a sub-agent or marking tasks done.
   - **Sub-agents get the same refusal.** The gate checks the conversation the user talks to (`origin`), so
     sub-agents started from it are read-only too.
3. **Instructions.** The section `Plan mode` (order 5) of the system prompt tells the model to:
   - research, then call `exit_plan_mode`;
   - give sub-agents no work that changes anything;
   - report no step as done before the plan is approved;
   - list the shell commands it will run (`exec` of `plugins/tools`) as steps, since they only run once the plan
     is approved.

   A question that needs no change is simply answered.
4. **Propose.** `exit_plan_mode {plan, items, criteria?}` is offered only in plan mode and only to the main agent
   (a sub-agent's call is refused). `plan` is markdown (findings, approach, files to change), `items` holds the
   ordered steps, and `criteria` the checkable acceptance criteria. The tool asks `Planner.APPROVE` and waits at
   most `approveTimeoutSeconds`. It declares its own time limit (`Tool.timeoutSeconds`), so
   `chat.toolTimeoutSeconds` does not cut the wait.
5. **Approved.** The plan file is marked `approved` and plan mode is switched off for the conversation. The steps
   are written as pending tasks with the criteria through `Todo.WRITE` (when `todo` runs; the review of
   `plugins/verify` then judges the answer against them), and the result tells the model to carry the plan out.
   `chat` reads the tools again before every model call, so the write tools are offered on the very next call of
   the same turn. While plan mode stays off, the section `Approved plan` (order 45) puts the plan's text (up to 8000
   characters) and its file's path in the system prompt, so the agent works from the whole plan, not only its steps.
6. **Sent back with feedback.** The file is marked `rejected` with the feedback, and the error result carries it.
   The conversation stays in plan mode, so the model revises the plan and proposes it again.
7. **Sent back without a reason.** The result tells the model to stop calling tools and end its answer by asking
   what to change, instead of guessing. The web UI asks for the feedback, so this happens in the REPL (`n` or an
   empty line).
8. **No answer** (nobody to ask, the browser left, or `approveTimeoutSeconds`). The result tells the model to end
   its answer with a summary of the plan and ask for approval. The file stays `proposed`.

## Plan files

Every proposal is written to `<project>/<dir>/<slug>-<id>.md`. `<project>` is the folder of the conversation's
project (`Workspace.ROOT`, from `plugins/workspace`), or `root` when it has none. `<slug>` comes from the plan's
first heading, with accents removed (`# Trang chủ` → `trang-chu`). `<id>` is the first 8 characters of the
conversation id, or a hash of it when the id is short or unusual.

- **One file per conversation.** A conversation keeps its file when the title changes, and each proposal replaces
  it.
- **The status line.** The first line records it:
  `<!-- panic plan: conversation=<id> status=proposed|approved|rejected updated=<instant> -->`.
- **The body.** Then come the plan, `## Steps`, `## Acceptance criteria`, and `## Feedback` for a plan sent back.
- **Writing.** A file is replaced through a temporary file and an atomic rename.
- **Failures are notes.** A file that cannot be written is mentioned in the tool's result and never fails the
  call.
- **Deleting.** The files belong to the project, so deleting a conversation leaves its plan file in place.

`plan show` prints the file's path, status and text.

The switches live in memory only. They are not config (a plugin never updates its own), so they are forgotten
when `planner` restarts, for example when its section changes. Cancelling the turn, or disposing the plugin,
cancels a proposal that is waiting.

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Planner.MODE` | hook `ModeQuery(conversationId) → Mode(on, byDefault)` | Whether a conversation is in plan mode, for front-ends to show. No answer when `planner` is not running |
| `Planner.APPROVE` | hook `Proposal(id, conversationId, plan, items, criteria, signal) → Answer(approved, feedback)` | Asks the user to approve a plan. Front-ends answer only for their own conversations and return null otherwise. No answer means not approved. A blocking answer must honour `signal` (cancelled when the turn stops or the wait times out) |
| `Planner.CHANGED` | event `ModeChanged(conversationId, on)` | A conversation entered or left plan mode (by command, or by an approved plan) |
| `Planner.EXIT_TOOL` | `"exit_plan_mode"` | The tool's name, for front-ends that render its call |

## Keys used

| Key / helper | From | Use |
| --- | --- | --- |
| `Tools.GATE` | `plugins/tools` | Keeps non-read tools out of a conversation in plan mode |
| `Workspace.ROOT` | `plugins/tools` (answered by `plugins/workspace`) | The project folder plan files go in |
| `Tools.register` | `plugins/tools` | Registers `exit_plan_mode` |
| `Chat.contribute` | `plugins/chat` | Adds the section `Plan mode` while a conversation is in it, and `Approved plan` once its plan is approved |
| `Todo.WRITE` | `plugins/todo` | Saves an approved plan as the task list and criteria |

It requires no service, and nothing requires it. Without `planner`, `plan` is not a command and the gate is open.

## Commands

`plan [on|off|status|show]` switches plan mode for this session's conversation, or shows its state and plan file
(the default is `status`). `show` prints the saved plan.
