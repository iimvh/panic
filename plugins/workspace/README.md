# plugins/workspace

Projects: the folders the user works in. Each conversation can work in one project; the file tools and the
checks then run in that folder instead of the one fixed in `panic.json`. The web sidebar groups chats by project
(Add project, "+" for a new chat in a project). Conversations, plans and traces are stored by Panic itself, so
nothing here moves or deletes them.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `workspace` | `WorkspaceConfig { dir = ".panic/workspace" }` | none | hooks `Workspace.ROOT`, `Workspace.LIST`; context section `Workspace`; command `workspace` |
| `folder-picker` | `FolderPickerConfig { enabled = true, timeoutSeconds = 120 }` | none | hook `Workspace.PICK` (none when `enabled` is false) |

## Storage

`projects.json` in `dir` (resolved against the app's working directory): the projects (absolute real path and
name) and the map conversation id → project path. Every change replaces the file through a temporary file and an
atomic rename. A damaged file is ignored with a warning (no projects). A project's name is its folder's name.

Removing a project only forgets the project: its chats keep their entry, are reported as `hidden` by
`Workspace.LIST` (front-ends do not list them) and come back when the same folder is added again. Deleting a
conversation drops its entry (`Conversations.DELETED`).

## The folder dialog

A browser cannot give a page the absolute path of a folder, so `workspace pick` asks the server, which runs next
to the user: Windows opens a PowerShell `FolderBrowserDialog`, macOS `osascript`, other systems `zenity`,
`kdialog` or `yad` (the first one installed). The call blocks until the dialog closes, `timeoutSeconds` pass or the command is cancelled, and then
the dialog is closed. No answer (cancelled, or no dialog program) makes the command say to type the path
(`workspace add <path>`), which front-ends show as a text field. Turn it off with `"folder-picker": {"enabled":
false}` when Panic runs on a machine the user does not sit at.

## Public keys

All keys live in `Workspace` (`plugins/tools`), so `files` and `checks` use them without depending on this module.

| Key | Answered by | Meaning |
| --- | --- | --- |
| `Workspace.ROOT` | `workspace` | The folder of a conversation's project; no answer when it has none or the project was removed |
| `Workspace.LIST` | `workspace` | The active projects with their conversations, and the hidden chats of removed projects |
| `Workspace.PICK` | `folder-picker` | A folder chosen in the system's dialog |

## Keys used

| Key / helper | From | Use |
| --- | --- | --- |
| `Chat.contribute` | `plugins/chat` | Adds the section `Workspace` (order 1): where the model works, or how the user can add a project. A sub-agent is told about the project of its `origin` |
| `Conversations.DELETED` | `plugins/chat` | Drops the entry of a deleted conversation |

It requires no service, and nothing requires it: without `workspace` every chat uses `files.root` and `checks.dir`.

## Commands

`workspace [action] [target...]`:

| Line | Does |
| --- | --- |
| `workspace` / `workspace list` | This chat's project and all projects with their chat counts |
| `workspace add <path>` | Adds a project (the folder must exist) |
| `workspace use <path>` | Makes this chat work in the folder, adding the project when needed |
| `workspace reset` | This chat goes back to the default workspace |
| `workspace remove [path]` | Removes a project (this chat's when no path); its chats are hidden |
| `workspace pick` | Opens the folder dialog and adds the chosen folder |
