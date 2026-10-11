<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Agent Notes

## Project Context

Kalita is a Windows/Linux/Android rewrite of the abandoned X-Cash 2.1
(Delphi) budgeting app. It must read and write the original's `Data.mdb` (Jet4 / Access
2000) in place, so the original X-Cash can still open it.

Stack: Kotlin core (JDK 25) shared between a Qt Widgets desktop UI via QtJambi and a
Jetpack Compose Android UI (distributed through F-Droid), using Jackcess for `Data.mdb`
access, with Nextcloud sync and an agent/MCP integration layer. License: GPL-3.0-only.

The full design is in `dev_plan.md` (outside this repo, in the parent working directory
during early development).

## Working Rules

- Preserve `Data.mdb` (Jet4) read/write compatibility with the original X-Cash at all
  times — this is the hard constraint behind the whole rewrite.
- Jackcess must not be given a forced charset (windows-1251 breaks the catalog index
  lookup) — see research notes in `tools/mdb-research/`.
- ChangeSets (operations storing base values plus tempKey references) belong in the data
  layer from the MVP onward; bank reconciliation must never overwrite operations.
- Do not commit build artifacts, local experiments, or internal-only planning documents.

## Commit and PR Hygiene

- Write commit messages, PR titles, and public-facing documentation in English.
- Always branch from the latest `main`; never develop directly on `main` — it is
  protected (PR-only, linear history, no force-push/deletion).
- Merge into `main` only through Pull Requests. Merges squash to one commit and delete
  the source branch automatically.
- Use Conventional Commits for commit messages and PR titles so release automation can
  update `CHANGELOG.md` and cut releases. A GitHub Action rejects non-conforming PR
  titles.
- Choose the commit type by what the change affects, so the version only moves for real
  application changes:
  - Kotlin core, desktop UI, Android UI, agent-api/MCP layer → `feat:`/`fix:`, which cut
    a new version, tag, and GitHub Release.
  - CI, build tooling, research scripts, docs, tests (`.github/`, `tools/`, `docs/`,
    `*.md`, test code) → `ci:`/`build:`/`chore:`/`docs:`/`test:`, which do not bump the
    version but still land in `CHANGELOG.md`.
  - Prefer `fix` over `feat` for behavior corrections. See `docs/releasing.md` →
    "Version Discipline: What Warrants a Release" for the full rule and reasoning.
- Keep changes focused and verifiable.

## GitHub Actions

- When adding or editing a workflow, pin every third-party action to the commit SHA of its
  current release, with the human-readable tag as a trailing comment — not a mutable major
  tag (supply-chain hardening: a moved tag can't silently change what runs). Verify the
  latest release and resolve its SHA before committing instead of reusing one from memory:

  ```bash
  tag=$(gh api repos/actions/checkout/releases/latest --jq .tag_name)
  gh api repos/actions/checkout/git/refs/tags/$tag --jq .object.sha
  ```

  (if `.object.type` is `tag` rather than `commit`, dereference once more via
  `gh api repos/<owner>/<repo>/git/tags/<that sha> --jq .object.sha`).

- Reference actions as `owner/repo@<sha> # <tag>`, e.g. `actions/checkout@3d3c42e... # v7`.
  Renovate (`renovate.json`) keeps these pins current — don't hand-edit a SHA without also
  updating its comment.

## Related References

- Release flow and version discipline: [docs/releasing.md](docs/releasing.md)
- Branching, PR titles, and expectations: [CONTRIBUTING.md](CONTRIBUTING.md)
- Jet4/MDB research: [tools/mdb-research/README.md](tools/mdb-research/README.md)
