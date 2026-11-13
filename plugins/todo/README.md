# plugins/todo

A plan per conversation: the task list the agent works through and the acceptance criteria its final answer
must meet (the definition of done). It is kept on disk, so it survives a restart, and it is shown to the model
in the system prompt on every ask. The reviewer (`plugins/verify`) judges answers against the criteria.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `todo` | `TodoConfig { dir = ".panic/todo", maxItems = 50 }` | none | tools `todo_read` (read), `todo_write` (write); context section `Tasks`; hook `Todo.PLAN`; command `todo` |

## Tools

| Tool | Risk | Arguments | Result |
| --- | --- | --- | --- |
| `todo_read` | read | `{}` | The list as numbered lines, `1. [ ] text` (`[ ]` pending, `[~]` in progress, `[x]` done), then `Acceptance criteria:` and one `- criterion` per line, or `no tasks` |
| `todo_write` | write | `{items: [{text, status: pending \| in_progress \| done}], criteria?: [string]}` | Replaces the whole list and returns the plan. `criteria` left out keeps the current ones (so a status update never loses them), `[]` clears them; blank criteria are dropped. More than `maxItems` tasks or criteria, or a blank task text, is refused and changes nothing |

`todo_write` only changes the agent's own list, never workspace files, but it is still a write, so the default
policy asks the user. `panic.example.json` allows it with `"permissions": {"tools": {"todo_write": "allow"}}`.

## Storage

One JSON file per conversation in `dir` (resolved against the app's working directory), named after the
conversation id, or `~` + the id in base64url when the id is not a short `[A-Za-z0-9_-]` name. A write replaces the
file through a temporary file and an atomic rename, under a per-conversation lock. A damaged file reads as an
empty list with a warning; the next write replaces it.

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Todo.PLAN` | hook `Query(conversationId) → Plan(items, criteria)` | The plan of a conversation. Answered by `todo`; empty (no answer) when the plan is empty or `todo` is not running |

## Keys used

| Key / helper | From | Use |
| --- | --- | --- |
| `Tools.register` | `plugins/tools` | Registers the two tools; they go away with the plugin |
| `Chat.contribute` | `plugins/chat` | Adds the section `Tasks` (order 50) with the current plan; nothing when it is empty |
| `Conversations.DELETED` | `plugins/chat` | Deletes the plan of a deleted conversation |

It requires no service, and chat does not require it: without `todo` the agent simply has no task list.

## Commands

`todo` prints this session's list (the same text as `todo_read`).
