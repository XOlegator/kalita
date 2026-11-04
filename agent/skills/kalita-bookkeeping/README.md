<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# kalita-bookkeeping

A Claude Code plugin for [Kalita](https://github.com/XOlegator/kalita), a free personal-finance
app. It bundles the `kalita-bookkeeping` Agent Skill (how to log income/expense/transfer
operations: resolve names, preview a batch, get the human's go-ahead, then write) together with a
ready-to-use `kalita mcp` server connection, so Claude Code can read and edit a Kalita database
(`Data.mdb`) without a manual `claude mcp add` step.

Install:

```
/plugin install kalita-bookkeeping --marketplace XOlegator/kalita
```

Needs the `kalita` CLI already installed and on `PATH`, and a named agent connection set up first
(the desktop app's «Агенты» dialog, or `kalita agent add`) — you'll be asked for the database path
and connection name at install time. See
[`docs/agents/claude-code.md`](../../../docs/agents/claude-code.md) for details and a manual
fallback.
