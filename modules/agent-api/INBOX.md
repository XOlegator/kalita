<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

### Inbox recovery for UI integrations

`ListInboxTool.execute()` continues to return only pending entries. Before submitting an approval,
the inbox persists `applying`. An interrupted submission, a failed or conflicting result, or a
failure saving the final status leaves the entry blocked, even if the operation was never committed.
Neither approve nor reject can process it again automatically.

Use `InboxRecovery(session).list()` to show these entries to a human. After checking the actual
database and change history, call `resolve(id, committed = true)` if the operation was committed,
or `resolve(id, committed = false)` only if it definitely was not. The former marks it approved;
the latter makes it pending again. Resolution never writes the database. A wrong decision can
produce a duplicate or hide an uncommitted draft. Do not expose resolution to an untrusted agent.
The inbox does not store a change-set ID before submission, so matching history may require manual
inspection; identical operations can be ambiguous.

All cooperating instances must use the same local data directory and `inbox.lock`; do not delete or
replace the lock file while running, or edit the inbox externally. The advisory lock does not
coordinate older versions, non-cooperating programs, separate synced copies, or filesystems with
unreliable locks. Atomic replacement must be supported by the filesystem; otherwise writes fail.
The file is flushed before replacement and the directory is flushed where supported. On platforms
where directory flush is unavailable or fails, power loss can roll back a status update; the
fail-closed guarantee therefore covers process crashes, not arbitrary power loss in that case.
Backups, sync rollback and external edits can likewise restore an obsolete pending status. Temporary
files may remain after a crash. Approvals serialize all inbox access while submission is in progress.