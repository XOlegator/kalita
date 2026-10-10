# Releases on GitHub

## Goal

This repository uses a semi-automated GitHub release flow, same mechanism as the sibling
`pinba_extension` project:

- version bumps are calculated from Conventional Commits;
- a release PR is opened or updated automatically by **release-please**;
- `CHANGELOG.md` is updated automatically;
- a Git tag and GitHub Release are created automatically once the release PR is merged.

## What Is Automated

Configured via `.github/workflows/release-please.yml` and `release-please-config.json`:

- watches pushes to `main`;
- opens or updates a release PR **once `main` contains at least one release-triggering
  commit** since the last release;
- calculates the next SemVer version:
  - `major`: a commit/PR title with `!`, or a `BREAKING CHANGE:` footer;
  - `minor`: at least one `feat`;
  - `patch`: `fix`, `perf`, `revert`;
- rewrites `CHANGELOG.md` for the release, grouped by type;
- creates a tag `vX.Y.Z` and a GitHub Release after the release PR is merged.

### Commits That Do Not Trigger a Release

Only `feat`, `fix`, `perf`, and `revert` (plus any breaking-change marker) are
release-triggering. `refactor`, `style`, `docs`, `test`, `build`, `ci`, and `chore` never
open a release PR by themselves — merging one of those updates `main` but leaves the
released version unchanged until the next release-triggering commit lands.

### Everything Still Reaches `CHANGELOG.md`

Unlike a default release-please setup, `release-please-config.json` does **not** hide
any commit type (`changelog-sections` lists `refactor`, `style`, `docs`, `test`, `build`,
`ci`, and `chore` without `"hidden": true`). So once a release does happen, its
`CHANGELOG.md` section lists *every* commit merged since the previous release — tooling,
docs, and chores included — not just the `feat`/`fix` subset. The version number, tag,
and GitHub Release, however, still only move for `feat`/`fix`/`perf`/`revert`.

In short: **the release (version/tag) tracks the executable only; the changelog history
tracks everything.**

## Version Discipline: What Warrants a Release

Pick a commit type by *what the change actually affects*, not by how big it feels:

- **Code that ships** — the Kotlin core, the QtJambi desktop UI, the Compose Android UI,
  the agent-api/MCP layer, or anything that changes runtime behavior. Use `feat:` (a
  genuinely new user-facing capability → `minor`) or `fix:` (a bug or behavior correction
  → `patch`). These cut a new version, tag, and release.
- **Everything else** — CI and workflows (`.github/`), build tooling (Gradle config,
  `tools/`), documentation (`docs/`, `README.md`, `AGENTS.md`, `*.md`), and tests. Use
  `ci:`, `build:`, `chore:`, `docs:`, or `test:`. These do **not** bump the version: the
  shipped application is unchanged, so there is no reason to cut a new release.

Two rules of thumb:

- **Prefer `fix` over `feat` for corrections.** `feat` is for new capability only; making
  previously incorrect or surprising behavior correct is a `fix` (a `patch`).
- A `fix`/`feat` whose scope is clearly non-executable (e.g. `fix(docs): ...`,
  `feat(ci): ...`) is a smell — retitle it `docs:`/`ci:` instead. The PR title check does
  not catch this mechanically; reviewers (i.e. yourself, before merging) are the gate.

Examples:

- Add recurring transaction rules → `feat(core): ...` (`minor`, release).
- Fix a Jet4 index write regression → `fix(core): ...` (`patch`, release).
- Add a GitHub Actions workflow → `ci: ...` (no release).
- Reword the README → `docs: ...` (no release).
- Add unit tests for the sync engine → `test: ...` (no release).

## Required Project Rules

- Work must be merged into `main` only through Pull Requests (enforced by branch
  protection: no direct pushes, no force-pushes, no branch deletion on `main`).
- Merges always squash to a single commit and delete the source branch (enforced by
  repository merge settings).
- PR titles must follow Conventional Commits (enforced by
  `.github/workflows/pr-title-conventional.yml`).

See [CONTRIBUTING.md](../CONTRIBUTING.md) for branch naming and the exact title grammar.

## Branch Model

- `main` is the release branch, protected, linear history only.
- All feature/fix/docs/maintenance work happens on a short-lived topic branch and merges
  through a squashed PR.
- release-please watches `main`.

## Release Lifecycle

1. Contributors merge regular PRs into `main`.
2. `release-please` opens or updates a release PR once `main` contains at least one
   release-triggering commit since the last release.
3. The maintainer reviews the proposed version and changelog in that PR.
4. Merging the release PR triggers automation that creates the `vX.Y.Z` tag, the GitHub
   Release, and the finalized `CHANGELOG.md` entry.

## Files Used by the Release System

- `.github/workflows/pr-title-conventional.yml`
- `.github/workflows/release-please.yml`
- `release-please-config.json`
- `.release-please-manifest.json`
- `CHANGELOG.md`

As the Gradle project is scaffolded, add an `extra-files` entry to
`release-please-config.json` so the version is also stamped into `build.gradle.kts` (or a
dedicated `version.txt`), the way `pinba_extension` stamps `php_pinba.h`.

## One-Time GitHub Repository Settings

Already configured for this repository:

1. `Settings → General → Pull Requests`
   - squash merging only (merge commit and rebase merge disabled);
   - automatically delete head branches.
2. `Settings → Branches → main`
   - require a pull request before merging;
   - require linear history;
   - require conversation resolution before merging;
   - disallow force pushes and branch deletion.
3. `Settings → Actions → General → Workflow permissions`
   - `Read and write permissions` (release-please needs to push the release PR and tags).

No required status checks are configured yet — add the future Gradle build/test workflow
to branch protection once it exists.

## Baseline Version

The starting point for the automated release history is `.release-please-manifest.json`
(`0.0.0`, i.e. no release has shipped yet). The first `feat`/`fix` merged to `main` will
propose `v0.1.0`.
