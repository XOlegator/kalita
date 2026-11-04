<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Claude Code

## Plugin (recommended)

```
/plugin install kalita-bookkeeping --marketplace XOlegator/kalita
```

Run from inside Claude Code. This adds the `kalita` repository as a plugin marketplace and
installs `kalita-bookkeeping` — the skill below plus a `kalita mcp` server connection — from
[`agent/skills/kalita-bookkeeping/`](../../agent/skills/kalita-bookkeeping/), in one step. You'll
be asked for the database path and a connection name at install time; `/plugin configure
kalita-bookkeeping` changes them later. Needs the `kalita` CLI already installed and on `PATH`
(see the root [`README.md`](../../README.md) for install options), and a named agent connection
set up first (desktop app's «Агенты» dialog, or `kalita agent add` — see §12.14/§12.15), same as
the manual setup below.

The rest of this page is that manual setup — use it if you'd rather not add a plugin marketplace,
or need `--scope project` for a repository-local MCP registration.

## MCP server

```bash
claude mcp add kalita -- kalita mcp --db /path/to/Data.mdb
```

This registers a local (stdio) server scoped to the current project; add `--scope user` to make it
available everywhere instead. `kalita mcp` itself defaults to `draft` mode (every write lands in
«Входящие» for a human to approve later) — pass a named connection or explicit flags for anything
else:

```bash
claude mcp add kalita -- kalita mcp --db /path/to/Data.mdb --connection my-laptop
# or, without a saved connection:
claude mcp add kalita -- kalita mcp --db /path/to/Data.mdb --mode write --max-daily 5000
```

Run `claude mcp list` to confirm it connected, and `/mcp` inside a session to see Kalita's tools.

## The skill

Claude Code discovers Agent Skills from a project's own `agent/skills/` directory (this repository
ships `agent/skills/kalita-bookkeeping/` at its root) or from `~/.claude/skills/` for every project.
If you are not working inside a checkout of this repository, copy the
`agent/skills/kalita-bookkeeping/` folder from a release archive (`kalita-bookkeeping.zip`, dev_plan.md
§12.16) into one of those locations instead.

## No MCP, just a terminal

Claude Code can also just run the `kalita` CLI directly — see the skill's own
[`reference/cli-cheatsheet.md`](../../agent/skills/kalita-bookkeeping/reference/cli-cheatsheet.md).
