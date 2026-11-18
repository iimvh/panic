# plugins/memory

What the agent knows across conversations: the instruction files people write for agents (`AGENTS.md`,
`CLAUDE.md`) and the memories the agent saves itself, for the user and for each project.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `instructions` | `InstructionsConfig { enabled = true, global = ".panic/AGENTS.md", names = ["AGENTS.md", "CLAUDE.md"], root = ".", maxChars = 12000 }` | none | context section `Instructions`; command `instructions` |
| `memory` | `MemoryConfig { enabled = true, dir = ".panic/memory", root = ".", maxIndexChars = 6000, maxFileChars = 20000 }` | none | tools `memory_view` (read), `memory_edit` (write); context section `Memory`; command `memory` |

Neither provides a service and nothing requires them, so changing their config restarts only that plugin (a
memory call still running finishes first), and chat runs the same without them. `root` is the folder of a chat
that has no project (plugin `workspace`); paths are relative to the app's working directory.

## Why it looks like this

The leading harnesses keep long-term memory out of the agent loop:

| Harness | Instruction files | Memory the agent writes |
| --- | --- | --- |
| [Pi](https://www.npmjs.com/package/@mariozechner/pi-coding-agent) | `~/.pi/agent/AGENTS.md`, then `AGENTS.md`/`CLAUDE.md` of every parent folder and the current one, joined | None built in; left to extensions |
| [opencode](https://jczhu.com/blog/opencode-memory-internals/) | `AGENTS.md`, else `CLAUDE.md` (global and project) | None built in; plugins add memory blocks |
| [Claude Code](https://code.claude.com/docs/en/memory) | `CLAUDE.md` of the user, the project and its parents | Auto memory: a folder per repository outside it, an index `MEMORY.md` loaded every session (first 200 lines / 25KB), topic files read on demand |
| [Anthropic memory tool](https://docs.claude.com/en/docs/agents-and-tools/tool-use/memory-tool) | | A client-side tool over `/memories` (`view`, `create`, `str_replace`, `insert`, `delete`, `rename`) |

So `instructions` follows Pi and opencode, and `memory` follows Claude Code's auto memory, with the paths and
commands of Anthropic's memory tool. Panic's own choices:

- **Two tools instead of one**: `memory_view` is a read and `memory_edit` a write. That way plan mode (which only
  offers reads) can still consult memory, and the permission policy can ask before a write.
- **Three edit commands**: `create`, `str_replace`, `delete`. `insert` and `rename` can be done with these.
- **No automatic extraction** after each turn, which would cost a model call per turn, and **no vector search**.

## `instructions`

For each ask it sends, under `## Instructions`, the global file `global` and then, for each folder from the
farthest parent down to the chat's project, the first of `names` found there. The nearest file comes last,
because it is the most specific. The walk up stops at the folder that holds `.git`, below the user's home, or at
the file system root. A sub-agent gets the files of the project of the conversation it works for
(`ContextQuery.origin`). A file is read again only when its time or size changes, so an edit shows on the next
ask.

The section is at most `maxChars` characters. Over that, the parents are cut first (farthest first), then the
global file, and the project's own file last. A cut is marked in the text, and the command `instructions` shows it.

**Cost and trust.** The system message is always sent whole (see the chat README's context window), so these files
take room from the history on every request, every sub-agent's included. Keep `maxChars` well under
`chat.contextTokens` × 4. A repository you did not write can put instructions in front of the model this way:
the permission policy still asks before writes and commands, and `enabled: false` turns the section off.

## `memory`

### Scopes and storage

| Path the model uses | On disk | Shared by |
| --- | --- | --- |
| `/memories/user/...` | `dir/user/` | every chat |
| `/memories/project/...` | `dir/projects/<name>-<8 hex of sha256(path)>/` | the chats of one project |

A project's memory is keyed by the git root of the project's folder, or by the folder itself when it is not in a
repository. So the folders of one repository share it, and nothing is written into the repository. Moving or
renaming the folder leaves its memory behind (`memory where` shows where it is). Removing a project, deleting a
conversation or `forget <id>` does not delete memories, because they are shared across chats.

Each scope's index, `MEMORY.md`, is sent under `## Memory`, at most `maxIndexChars` each, cut at a line with a
note. The other files are read with `memory_view`. The main agent also gets a short guide, after Claude Code's: one
fact per file, front matter `name` / `description` / `type` (`user`, `feedback`, `project`, `reference`), one line
`- [Title](file.md) — hook` in the index, update an existing file instead of adding a second one, delete what turned
out wrong, and do not save what the project's files already say. Memories are notes, not instructions: the user
wins over a memory. A sub-agent gets only the indexes, and no section when both are empty.

Writes replace the file through a temporary file and an atomic rename, under one lock per plugin. One Panic per
`dir`, as for conversations: another process is not locked out, but its stale `str_replace` fails (the text it
expects is gone) instead of losing a write.

### Tools

| Tool | Risk | Arguments | Result |
| --- | --- | --- | --- |
| `memory_view` | read | `{path, view_range?: [first, last]}` | A folder (`/memories` = both scopes) lists its `.md` files with their size and `description`; a file comes with numbered lines (`last` -1 = to the end) |
| `memory_edit` | write | `{command: create \| str_replace \| delete, path, file_text?, old_str?, new_str?}` | `created` / `replaced` / `edited` / `deleted` and the path. `old_str` must occur exactly once |

Refused, with an error result the model can act on: a path outside `/memories/user` and `/memories/project`, a `..`
segment, a symbolic link, a name that does not end in `.md`, a file over `maxFileChars`, and any `memory_edit`
from a sub-agent (`only the main agent saves memories`). Sub-agents run side by side on unchecked findings, so
they report what they learned and the main agent decides what to keep.

**Asked by default.** `memory_edit` is a write, and unlike `todo_write`, what it writes outlives the chat. A page
the agent read could try to plant an instruction that every later chat would then see. So `panic.example.json`
does not allow it, and each memory the agent saves is shown to the user for approval. To stop being asked, add
`"permissions": {"tools": {"memory_edit": "allow"}}`.

## Keys used

| Key / helper | From | Use |
| --- | --- | --- |
| `Chat.contribute` | `plugins/chat` | Sections `Instructions` (order 2) and `Memory` (order 3), after `Workspace` (1). They change less often than `Tasks` (50) and come before it, so the start of the prompt stays the same between asks |
| `Chat.ContextQuery.origin`, `delegated()` | `plugins/chat` | A sub-agent's project; a sub-agent gets no guide |
| `Tools.register`, `ToolContext.origin`, `ToolContext.agent` | `plugins/tools` | The two tools; the project of the caller; refuse writes from sub-agents |
| `Workspace.root` | `plugins/tools` | The folder of a conversation's project (answered by `workspace`; `root` without it) |

## Commands

| Line | Does |
| --- | --- |
| `instructions` | The instruction files of this chat, their size and what was left out |
| `memory` / `memory list [path]` | The memory files of both scopes (or of `path`), with their descriptions |
| `memory show <path>` | A memory file with numbered lines (`user/x.md` = `/memories/user/x.md`) |
| `memory forget <path>` | Deletes a memory and the index lines that link to it |
| `memory where` | The two folders on disk for this chat |
