<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# MCP client configuration

Every snippet below launches the same thing: `kalita mcp --db /path/to/Data.mdb`. Replace that path
with your own database, and add `--connection NAME` (a connection set up with `kalita agent add` or
the desktop app's «Агенты» dialog) or `--mode`/`--max-amount`/`--max-daily` flags if you want
anything other than the `draft`-mode default. If `kalita` is not on your `PATH` (e.g. a portable
install), use its full path as `command` instead.

Client support and config file formats shift over time — if a snippet below stops working, check
that client's own current MCP documentation; this is not maintained in lockstep with every client's
releases.

## Claude Desktop

Edit `claude_desktop_config.json` (Settings → Developer → Edit Config) and add an entry under
`mcpServers`:

```json
{
  "mcpServers": {
    "kalita": {
      "command": "kalita",
      "args": ["mcp", "--db", "/path/to/Data.mdb"]
    }
  }
}
```

Restart Claude Desktop afterwards. (A one-click `kalita.mcpb` package that avoids hand-editing this
file is planned — dev_plan.md §12.16 — but not built yet.)

## Cursor

Same `mcpServers` shape as Claude Desktop, in `~/.cursor/mcp.json` (every project) or
`.cursor/mcp.json` inside one project:

```json
{
  "mcpServers": {
    "kalita": {
      "command": "kalita",
      "args": ["mcp", "--db", "/path/to/Data.mdb"]
    }
  }
}
```

## VS Code

VS Code's own MCP support uses a different shape — a top-level `servers` object with an explicit
`"type": "stdio"` — in `.vscode/mcp.json` (one workspace) or via the "MCP: Open User Configuration"
command (every workspace):

```json
{
  "servers": {
    "kalita": {
      "type": "stdio",
      "command": "kalita",
      "args": ["mcp", "--db", "/path/to/Data.mdb"]
    }
  }
}
```

(The Command Palette's "MCP: Add Server" walks through the same thing interactively.)

## Codex CLI

```bash
codex mcp add kalita -- kalita mcp --db /path/to/Data.mdb
```

Or by hand, in `~/.codex/config.toml`:

```toml
[mcp_servers.kalita]
command = "kalita"
args = ["mcp", "--db", "/path/to/Data.mdb"]
```

## Gemini CLI

In `~/.gemini/settings.json` (every project) or `.gemini/settings.json` (one project):

```json
{
  "mcpServers": {
    "kalita": {
      "command": "kalita",
      "args": ["mcp", "--db", "/path/to/Data.mdb"]
    }
  }
}
```

## LM Studio

Program tab (right sidebar) → Install → Edit `mcp.json`, same `mcpServers` shape as Cursor:

```json
{
  "mcpServers": {
    "kalita": {
      "command": "kalita",
      "args": ["mcp", "--db", "/path/to/Data.mdb"]
    }
  }
}
```

## Open WebUI

Open WebUI's native MCP support only speaks Streamable HTTP, not stdio — `kalita mcp` cannot be
added directly. Bridging it through a stdio-to-HTTP proxy such as [`mcpo`](https://github.com/open-webui/mcpo)
(`mcpo -- kalita mcp --db /path/to/Data.mdb`, then add the proxy's HTTP endpoint in Open WebUI) is
possible but untested with Kalita specifically — treat it as unsupported until someone verifies it.
