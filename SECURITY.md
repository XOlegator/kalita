<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Security Policy

## Supported Branch

Security fixes are developed on the active `main` branch. Kalita has not reached a 1.0
release yet (AGENTS.md / dev_plan.md §1.1) — there is no older release line to backport to.

## Threat Model

Kalita reads and writes `Data.mdb`, a Jet 4 / Access 2000 database holding a user's personal
financial records, in place — the same file the original X-Cash keeps using. The areas treated
as security-relevant, in order of impact:

- **Data integrity of `Data.mdb`.** Any write path that could corrupt, truncate, or silently
  lose rows in a user's real database is the project's most serious class of bug, regardless of
  whether it's reachable by an attacker (dev_plan.md R1). Atomic commit (`docs/adr/0002-atomic-mdb-commit.md`)
  exists specifically to bound this.
- **Parsing untrusted `.mdb` files.** Opening a file the user picked (not necessarily one they
  created) means the Jet4 parser (Jackcess) and `SchemaFingerprint` check are processing
  untrusted input; a malformed file should fail to open cleanly, not crash or corrupt memory.
- **Nextcloud sync credentials and traffic** (from dev_plan.md stage 9 onward). Tokens are kept
  in the OS credential store (NFR-8), never in plain settings files; sync traffic is HTTPS-only.
- **Agent/MCP access** (from dev_plan.md stage 7a onward). An AI agent with write access to the
  database is a prompt-injection and over-reach surface — see dev_plan.md R17/R19 and
  `docs/adr/` once the agent-api ADR lands.

## Reporting a Vulnerability

Please do not open a public issue for a suspected security vulnerability.

Report it privately via GitHub's
[private vulnerability reporting](https://github.com/XOlegator/kalita/security/advisories/new),
or by email to:

- Oleg Ekhlakov <o.ekhlakov@protonmail.com>

Include, when possible:

- affected platform (Windows, Linux, Android) and version;
- affected Kalita version or commit;
- reproduction steps, ideally a fixture `.mdb` rather than a real database;
- whether the issue affects confidentiality, integrity, availability, or data correctness;
- whether a crafted `.mdb` file, a crafted sync payload, or local access is required to trigger it.

Never attach or paste a real `Data.mdb` or other personal financial data to a report — use a
generated fixture (dev_plan.md §14.2) that reproduces the issue instead.

## Response Policy

- Acknowledgement target: reasonable best effort (single maintainer, pre-1.0 project).
- Fixes should preserve `Data.mdb` compatibility with the original X-Cash (AGENTS.md) unless
  the vulnerability itself is in that compatibility layer.
- Public release notes are published after a fix is prepared or released.
