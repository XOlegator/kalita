<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Contributing

## Workflow

1. Start from the latest `main`.
2. Create a dedicated topic branch.
3. Make focused changes.
4. Open a Pull Request against `main`.
5. Merge only through the PR — `main` is protected: no direct pushes, no force-pushes,
   no branch deletion.
6. Merging always **squashes** the PR into a single commit on `main`, and the source
   branch is deleted automatically afterwards.

```bash
git checkout main
git pull --ff-only origin main
git checkout -b feat/short-description
```

## Branch Naming

Prefix the branch with the Conventional Commit type it carries:

- `feat/`
- `fix/`
- `perf/`
- `refactor/`
- `style/`
- `docs/`
- `test/`
- `build/`
- `ci/`
- `chore/`
- `revert/`

## PR Titles (Conventional Commits — Enforced)

The PR title becomes the squash commit message, which release automation parses, so it
must follow [Conventional Commits](https://www.conventionalcommits.org/) **strictly**:

```
<type>(<optional scope>): <description>
```

- `type` is one of: `feat`, `fix`, `perf`, `refactor`, `style`, `docs`, `test`, `build`,
  `ci`, `chore`, `revert`.
- `description` is lower-case, imperative mood, no trailing period.
- A breaking change uses `!` after the type/scope (`feat!:` or `feat(core)!:`) and/or a
  `BREAKING CHANGE:` footer in the PR description.
- A GitHub Action (`pr-title-conventional.yml`) rejects any PR whose title does not
  match this format — fix the title and the check re-runs automatically.

Examples:

- `feat(core): add recurring transaction rules`
- `fix(sync): retry on Nextcloud 412 conflict`
- `docs(releasing): clarify version bump rules`
- `ci: pin actions/checkout to v7`

See [docs/releasing.md](docs/releasing.md) for exactly which types bump the version and
cut a release, versus which are changelog-only.

## Local Android SDK

`./gradlew build` compiles `app-android`, so it needs the Android SDK (CI installs it with
`android-actions/setup-android`). On Linux:

```bash
mkdir -p ~/Android/Sdk/cmdline-tools
# download "Command line tools only" from https://developer.android.com/studio#command-line-tools-only
unzip commandlinetools-linux-*_latest.zip -d /tmp/clt
mv /tmp/clt/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
export ANDROID_HOME=~/Android/Sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties   # git-ignored
```

Without an SDK, build only the JVM modules, e.g. `./gradlew :modules:storage-mdb:build`.

## Pre-commit Hooks

[lefthook](https://github.com/evilmartians/lefthook) runs `reuse lint` before each commit
(`lefthook.yml`) — more hooks (ktlint, detekt, typos) land as those tools are wired into
the build. Install once per clone:

```bash
# via npm (no separate lefthook install needed):
npx lefthook install
# or, if you already have the lefthook binary:
lefthook install
```

The `reuse` hook itself needs the [reuse](https://github.com/fsfe/reuse-tool) CLI on your
`PATH` (`pipx install reuse`, or any method from its docs).

## Expectations

- Keep changes focused and reviewable; prefer several small PRs over one large one.
- Do not commit local build artifacts, IDE files, or internal-only planning documents.
- Update `docs/` when behavior, architecture, or workflow changes.
- Preserve `Data.mdb` (Jet4) compatibility — see `AGENTS.md` for the hard constraints.

## Related References

- Release flow and version discipline: [docs/releasing.md](docs/releasing.md)
- Project context and working rules: [AGENTS.md](AGENTS.md)
