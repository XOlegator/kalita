<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Connecting an AI agent to Kalita

Kalita's agent-facing tools (dev_plan.md §12.12-§12.16) are reachable two ways:

- **`kalita mcp`** — a local MCP server over stdio, for any client that can launch one. See
  [`mcp-clients.md`](mcp-clients.md) for ready configuration snippets (Claude Desktop, Cursor,
  VS Code, Codex CLI, Gemini CLI, LM Studio) and [`claude-code.md`](claude-code.md) for Claude Code
  specifically.
- **The `kalita` CLI** — for an agent with a terminal but no MCP client at all. See the
  [`kalita-bookkeeping` skill's cheat sheet](../../agent/skills/kalita-bookkeeping/reference/cli-cheatsheet.md).

Either way, pair the connection with the **[`kalita-bookkeeping` skill](../../agent/skills/kalita-bookkeeping/SKILL.md)**
(the Agent Skills open standard — supported by Claude, Codex, Gemini CLI, GitHub Copilot, Cursor,
and others) so the agent knows the workflow (resolve names → preview → confirm → write) and the
domain rules (operation types and signs, currency exchange, opening balances) before it touches a
real database. How to install the skill itself depends on the client — e.g. Claude Code reads it
straight from a project's checked-out `agent/skills/` directory or a `~/.claude/skills/` copy;
consult your client's own docs for where it expects a skill folder.

Every client below needs the full path to the Kalita database file (`Data.mdb`) and, ideally, a
named agent connection set up first (`kalita agent add --db <path> --name ... --mode ...`, or the
desktop app's «Агенты» settings dialog) so the write mode and limits are not re-typed into every
client's config — see dev_plan.md §12.14/§12.15.

## Claude Desktop: `kalita.mcpb` (one-click, Linux)

Every [GitHub Release](https://github.com/XOlegator/kalita/releases) carries a `kalita.mcpb` —
double-click it (or drag it onto Claude Desktop) to install the `kalita mcp` server without
installing anything else first: it bundles its own self-contained runtime, the same way the
`.tar.gz`/AppImage desktop packages do. Claude Desktop will ask for the database file path and an
agent connection name (set one up first — see above) through its own install-time settings UI.

Linux-only for now — the bundled server binary is the Linux CLI app-image; Windows/macOS support
needs a `platform_overrides` entry in `modules/app-cli/packaging/mcpb/manifest.json` and a CLI
app-image built on those OSes, not yet done (dev_plan.md §12.16).

**Not yet available:** a packaged Claude Code plugin bundling this skill with the MCP setup — still
requires the manual steps in `claude-code.md`. Tracked as a follow-up (dev_plan.md §12.16).
