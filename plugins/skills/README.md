# plugins/skills

Skills: folders of instructions and files written for one kind of task (fill a PDF form, write a release note,
follow the team's review checklist). The agent sees only each skill's name and description, and loads the
whole skill when a task needs it; the user runs one with `/name args`.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `skills` | `SkillsConfig { enabled = true, global = ".panic/skills", dirs = [".panic/skills", ".agents/skills", ".claude/skills"], root = ".", maxListChars = 6000, maxSkillChars = 40000 }` | none | tool `skill` (read); context section `Skills`; answers `Prompts.EXPAND`; command `skills` |

It provides no service and nothing requires it, so changing its config restarts only this plugin (a call still
running finishes first), and chat runs the same without it. `root` is the folder of a chat that has no project
(plugin `workspace`); `global` is relative to the app's working directory and `dirs` to the chat's project.

## Why it looks like this

The format is [Agent Skills](https://agentskills.io): one folder per skill holding a `SKILL.md`, which starts with
a front matter (`name`, `description`) and goes on with the instructions, next to the files it uses (references,
scripts, templates). Every leading harness loads skills in steps: only the names and descriptions go in every
prompt, and the rest when needed.

| Harness | Where | The model loads one | The user runs one |
| --- | --- | --- | --- |
| [Claude Code](https://code.claude.com/docs/en/skills) | `~/.claude/skills`, `.claude/skills` | tool `Skill` | `/name args` (`$ARGUMENTS`); `disable-model-invocation`, `user-invocable` |
| [Codex](https://developers.openai.com/codex/skills) | `~/.codex/skills`, `.agents/skills` | reads `SKILL.md` with the shell | `$name`, `/skills` |
| [opencode](https://opencode.ai/docs/skills) | `.opencode/skills`, `.claude/skills`, `.agents/skills` | tool `skill` | asks in words |
| [Pi](https://www.npmjs.com/package/@mariozechner/pi-coding-agent) | `~/.pi/agent/skills`, `.pi/skills` | reads the file | `/skill:name` |

So Panic follows Claude Code: a `skill` tool, `/name args` and the same front matter keys. It also reads
`.agents/skills` and `.claude/skills`, so skills written for those harnesses work as they are. Panic's own choices:

- **A tool rather than the shell or `read_file`.** `read_file` only sees the chat's project, so it cannot read a
  skill from the global folder. A `read` tool also works in plan mode and needs no approval.
- **`/name` goes through the normal ask** (see `Prompts` in `sdk` and the chat README). Running the turn inside a
  command would lose what the web's `/api/chat` guarantees: one answer per session, an answer that goes on after
  a page reload, and the steps shown as they happen.
- **No `allowed-tools`.** In Claude Code that key lets a skill run tools without asking. Here a repository you
  cloned could use it to give itself the shell, so it is ignored and `skills` says so. The permission policy decides
  as for any call.
- **Sub-agents get no list.** As in Claude Code, a sub-agent does not inherit the skills: every one of its requests
  would pay for the list, and its profile may not offer `skill`. It can still load a skill its task names.

## Finding skills

For a chat, `skills` looks in each folder of `dirs` under the chat's project, in order, then in `global`. Each
sub-folder holding a `SKILL.md` is a skill. When two have the same name, the first one wins: the project's own
over the user's, and `.panic/skills` over `.agents/skills` over `.claude/skills`. A warning names the one left out.
Files are read again only when their time or size changes, so a new or edited skill counts from the next ask, with
no restart.

### `SKILL.md`

```markdown
---
name: release-notes
description: Writes release notes from the commits since the last tag. Use when the user asks for release notes or a changelog.
argument-hint: "[version]"
---
# Release notes
1. List the commits since the last tag: `git log $(git describe --tags --abbrev=0)..HEAD --oneline`.
2. Group them as in `template.md` for version $ARGUMENTS.
```

| Key | Use |
| --- | --- |
| `name` | Lowercase letters, digits and dashes, at most 64. It must equal the folder's name; left out = the folder's name |
| `description` | Required. What the skill does and when to use it: the model chooses from this alone |
| `disable-model-invocation: true` | Only the user runs it (`/name`): it is not listed for the model, and `skill` refuses it |
| `user-invocable: false` | Only the model loads it: `/name` is not answered |
| `argument-hint` | Shown by `skills` after `/name` |
| `license`, `compatibility`, `metadata` | Accepted, unused |
| anything else (`allowed-tools`, `model`, `context`, `hooks`...) | Ignored; `skills` lists them |

The front matter is read as simple `key: value` lines: a value may be quoted, or `>` / `|` followed by indented
lines. Nested values and lists are skipped. Panic keeps to plain Java and Jackson, and the keys it uses need
nothing more. A skill whose name is wrong or that has no description is skipped with a warning, once.

`$ARGUMENTS` in the instructions is replaced with the arguments (`/name args`, or `args` of the tool). With no
`$ARGUMENTS` and some arguments, they are added at the end as `ARGUMENTS: ...`, as in Claude Code.

## The section `Skills`

For the main agent, under `## Skills`: a short guide (load a matching skill with `skill` before working, and a
`/name` line followed by a `skill` result is a skill the user ran) and one line `- name: description` per skill
the model may load. At most `maxListChars` characters; the rest is counted in a note. No skill: no section.

**Cost.** The list is in the system message of every request of the main agent. Its order (4, after `Memory`,
before `Plan mode`) keeps the start of the prompt the same from one request to the next. A skill's instructions
cost tokens only once loaded.

**Trust.** A repository's skills put text in front of the model and may suggest running their scripts. The
permission policy still asks before the shell and writes, and `enabled: false` turns skills off.

## The `skill` tool

`skill {name, args?, file?}`, risk `read`:

- Without `file`: `Skill: <name>`, `Base directory: <absolute folder>`, the skill's files (paths relative to that
  folder, with their size; at most 100, symbolic links left out), then the instructions without the front
  matter, `$ARGUMENTS` replaced and cut at `maxSkillChars`.
- With `file`: the text of that file of the skill, cut at `maxSkillChars`. Refused: a path out of the folder (also
  through a symbolic link), a missing or binary file (run it from the shell), more than 4 × `maxSkillChars` bytes.
- An unknown name gets the list of names; a skill with `disable-model-invocation` is refused.

Scripts are run with the `shell` tool, from the base directory, and are approved like any command.

## `/name args`

A front-end runs `/name` as a command first. When no command has that name, it asks `Prompts.EXPAND`, and this
plugin answers for a skill of the chat's project (unless `user-invocable: false`). The front-end then sends the line
as a plain question, and the chat puts it in the history as written, followed by a `skill` call (arguments
`{name, args?}`) whose result is the skill as the tool would return it. So the model and the user loading a skill
leave the same history, which compaction summarises like any tool result.

A command always wins over a skill of the same name. `skills` says so; rename the skill's folder to run it.
`help` and the web page's `/` suggestions list commands only; `skills` lists the skills.

## Keys used

| Key / helper | From | Use |
| --- | --- | --- |
| `Prompts.EXPAND` | `sdk` | Answers `/name args` with the skill (`tool = "skill"`) |
| `Commands.command`, `Commands.find` | `sdk` | `skills`; whether a command hides a skill |
| `Chat.contribute`, `Chat.ContextQuery.delegated()` | `plugins/chat` | Section `Skills` (order 4), none for a sub-agent |
| `Tools.register`, `ToolContext.origin` | `plugins/tools` | The `skill` tool; the project of the caller (a sub-agent's origin) |
| `Workspace.root` | `plugins/tools` | The folder of a conversation's project (answered by `workspace`; `root` without it) |

## Commands

| Line | Does |
| --- | --- |
| `skills` | The skills of this chat: name, folder (`.claude/skills`, `global`...), description, and what differs (only the user or only the agent runs it, hidden by a command, ignored keys) |
| `skills <name>` | One skill: its `SKILL.md`, description, how to run it and its files |
| `/<name> [args]` | Runs the skill (not a command: answered through `Prompts.EXPAND`) |
