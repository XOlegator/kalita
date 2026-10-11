<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

## Summary

- describe the change clearly
- explain why it is needed

## Checks

- [ ] Branch is based on the latest `main`
- [ ] PR title follows Conventional Commits (see `docs/releasing.md`)
- [ ] Commit type matches what the change actually affects (core vs. tooling/docs)
- [ ] Local build and tests were run when relevant
- [ ] `Data.mdb` compatibility with the original X-Cash is unaffected, or explicitly re-verified (AGENTS.md)
- [ ] UI screenshots attached for both shells, if this changes desktop or Android UI
- [ ] Docs were updated when behavior or workflow changed
- [ ] No local build artifacts, internal-only planning docs, or vendor files (`Data.mdb`, screenshots of the original — dev_plan.md §13.1/§13.2) are included

## Validation

List the local or CI checks that confirm the change.
