<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# ADR-0003: Kotlin + Jackcess (spannm fork) for Jet4

- Status: Accepted
- Date: 2026-10-10
- See also: dev_plan.md §8.1–§8.3

## Context

The hardest requirement is reliable **write** access to Jet 4 / Access 2000, on Windows, Linux, and
Android, without losing data. A October 2026 survey of everything that reads or writes this format
(dev_plan.md §8.1) found exactly one mature option with proven write support across all three target
platforms: the Jackcess family (Java, Apache-2.0). Everything else either can't write
(jetdb, mdbtools, `access_parser`), is too young to trust with real money (JackcessDotNet,
UCanAccessCs — both had data-loss bugs fixed within the last few months), or is Windows-only
(Jet/ACE itself). dev_plan.md §8.2 ran a direct validation: 600 synthetic operations written by
Jackcess, cross-read and cross-written by a real Microsoft Jet 4.0 engine (DAO 3.6 under Wine), and
opened by the original X-Cash — zero discrepancies on the spannm fork.

## Decision

- **Language: Kotlin.** Jackcess is JVM-only, and the JVM is the only realistic path to reliable
  Jet4 writes on Linux and Android, so the core is written in Kotlin (null-safety for the
  database's nullable fields, data/sealed classes for the model and ChangeSets, coroutines/`Flow`
  for background work and sync).
- **Jackcess fork: `io.github.spannm:jackcess` 5.1.x**, not the upstream `com.healthmarketscience`
  line. Zero dependencies (important for Android), targets current Java versions, is the base for
  UCanAccess, and gets correct date rounding where upstream showed sub-second drift against real
  Jet. The project still cross-tests against upstream 5.0.x as insurance (§8.3) — all access goes
  through a `MdbEngine` interface so the active line is a build-time choice, not a code change.

## Consequences

- All database access funnels through `storage-mdb`, and Jackcess only ever gets imported there
  (ADR-0006) — a future engine swap (e.g. if the fork stalls) stays contained to one module.
- Upgrades to either Jackcess line only land through Renovate PRs that re-run the full reference
  test suite (§8.3), not ad hoc version bumps.
- Jackcess must never be given a forced charset (windows-1251 breaks the catalog index lookup —
  `tools/mdb-research/`), and dates use `DateTimeType.LOCAL_DATE_TIME` with `autoSync=false` and an
  explicit `flush()` (§9.5) — these are load-bearing settings, not defaults to "clean up" later.
