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

**Not yet available:** a one-click **MCPB** package (`kalita.mcpb`) for Claude Desktop, and a
packaged Claude Code plugin bundling this skill with the MCP setup — both still require the manual
steps in `mcp-clients.md`/`claude-code.md` below. Tracked as a follow-up (dev_plan.md §12.16).
