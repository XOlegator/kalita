<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# `kalita` CLI cheat sheet

For an agent that has a terminal but no MCP connection. Every command below takes `--db <path to
Data.mdb>` (omitted from the examples for brevity) and `--json` for machine-readable output; without
`--json` it prints a short line for a human instead. Exit codes: `0` success, `1` a business-logic
failure (`"ok": false`, or any rejected draft), `2` a usage error (bad flags, missing file).

```bash
# Context: today's date, base currency, dictionaries.
kalita context --json --db Data.mdb

# Name lookup — --kind is account|budget-item|currency|counterparty|type (NOT the MCP spelling,
# see reference/fields.md).
kalita resolve "Кафе" --kind budget-item --json --db Data.mdb

# One operation, with a preview first.
kalita op add --type expense --amount 350 --category "Кафе" --account "Карта" \
  --date 2026-11-04 --dry-run --request-id <uuid> --json --db Data.mdb
# ...then for real, same --request-id, --mode draft (default) or --mode write:
kalita op add --type expense --amount 350 --category "Кафе" --account "Карта" \
  --date 2026-11-04 --mode write --request-id <uuid> --json --db Data.mdb

# A whole batch (e.g. a receipt) from a JSON file of draft objects (reference/fields.md's shape):
kalita op add --batch items.json --mode draft --request-id <uuid> --json --db Data.mdb

# Search with an aggregate sum.
kalita op list --from 2026-11-01 --to 2026-11-30 --category "Кафе" --sum --json --db Data.mdb

# Balances per account/currency, plus ИТОГО.
kalita balance --json --db Data.mdb

# A pending draft (from a draft-mode op add) awaiting human review:
kalita inbox list --json --db Data.mdb
kalita inbox approve <inboxId> --json --db Data.mdb
kalita inbox reject <inboxId> --json --db Data.mdb

# Write-mode-only: edit/delete/restore one known operation by id, or add a dictionary item —
# never as a side effect of op add, only on explicit request.
kalita op update <id> --type expense --amount 400 --category "Кафе" --account "Карта" \
  --date 2026-11-04 --request-id <uuid> --json --db Data.mdb
kalita op delete <id> --request-id <uuid> --json --db Data.mdb
kalita op restore <id> --request-id <uuid> --json --db Data.mdb
kalita dict add --kind budget-item --name "Подарки" --parent "Расход" --request-id <uuid> --json --db Data.mdb

# Undo a change set this same device created (the changeSetId a write call returned).
kalita undo <changeSetId> --request-id <uuid> --json --db Data.mdb

# Every tool, as a structured list (useful for a one-off overview, not a substitute for this skill).
kalita schema --json
```

A fresh random `--request-id` per *new* write call, but the **same** one on a retry of a call that
may have already gone through (crash, timeout) — that is what makes the retry idempotent instead of
a duplicate.
