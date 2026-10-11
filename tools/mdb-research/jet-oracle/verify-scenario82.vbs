' SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
' SPDX-License-Identifier: GPL-3.0-only

' Stage 0.4 CI (dev_plan.md §15 stage 0, 0.4): opens the .mdb the Android emulator job wrote via
' Jackcess with the real Microsoft Jet engine and checks it against the sidecar counts that same
' run computed. "DAO.DBEngine.36" (true Jet/DAO 3.6, used by the original 8.2 research under
' Wine) isn't installed on a stock windows-2025 runner; "DAO.DBEngine.120" is what the Access
' Database Engine 2016 Redistributable registers — the ACE engine that is Microsoft's own, still
' Jet-format-compatible successor, and is what actually reads legacy .mdb on current Windows.
' usage: cscript //nologo verify-scenario82.vbs <db.mdb> <expected.txt>

Dim fso, expected, line, parts, e, db
Set fso = CreateObject("Scripting.FileSystemObject")
Set expected = CreateObject("Scripting.Dictionary")

Dim inFile
Set inFile = fso.OpenTextFile(WScript.Arguments(1), 1)
Do While Not inFile.AtEndOfStream
  line = Trim(inFile.ReadLine)
  If Len(line) > 0 Then
    parts = Split(line, "=")
    expected.Add Trim(parts(0)), CLng(Trim(parts(1)))
  End If
Loop
inFile.Close

Set e = CreateObject("DAO.DBEngine.120")
Set db = e.OpenDatabase(WScript.Arguments(0))

Dim failures
failures = 0

Sub CheckEquals(label, actual, expectedValue)
  If actual <> expectedValue Then
    WScript.Echo "FAIL " & label & ": expected " & expectedValue & ", got " & actual
    failures = failures + 1
  Else
    WScript.Echo "OK   " & label & " = " & actual
  End If
End Sub

CheckEquals "DvizhenieDeneg.RecordCount", db.TableDefs("DvizhenieDeneg").RecordCount, expected.Item("operations")
CheckEquals "GroupBudget.RecordCount", db.TableDefs("GroupBudget").RecordCount, expected.Item("groupBudget")
CheckEquals "Account.RecordCount", db.TableDefs("Account").RecordCount, expected.Item("accounts")
CheckEquals "Course.RecordCount", db.TableDefs("Course").RecordCount, expected.Item("courses")

' Walk both of Course's secondary indexes (one DESC) end to end — proves Jet can traverse the
' indexes Jackcess built, not just read the raw rows.
Dim td, idx, rs, cnt
Set td = db.TableDefs("Course")
For Each idx In td.Indexes
  Set rs = db.OpenRecordset("Course", 1)
  rs.Index = idx.Name
  cnt = 0
  Do While Not rs.EOF
    cnt = cnt + 1
    rs.MoveNext
  Loop
  rs.Close
  CheckEquals "Course index " & idx.Name & " traversal count", cnt, expected.Item("courses")
Next

' Spot-check the row-level mutations scenario 8.2 makes, and the soft-delete flag Kalita itself
' never resurrects (dev_plan.md's countOperations convention) — not just table-level counts.
Set rs = db.OpenRecordset("SELECT COUNT(*) AS n FROM DvizhenieDeneg WHERE del = True", 4)
CheckEquals "soft-deleted operations", rs.Fields("n").Value, expected.Item("softDeletedOperations")
rs.Close

Set rs = db.OpenRecordset("SELECT COUNT(*) AS n FROM DvizhenieDeneg WHERE Summa = 12345.67 AND Comment Is Null", 4)
CheckEquals "nulled-comment operations", rs.Fields("n").Value, expected.Item("nulledOperations")
rs.Close

' Cyrillic round-trip through Android's ART/JVM string handling, read back by a non-JVM engine.
Set rs = db.OpenRecordset("SELECT COUNT(*) AS n FROM GroupBudget WHERE Name LIKE 'Тест статья*'", 4)
CheckEquals "GroupBudget Cyrillic rows", rs.Fields("n").Value, expected.Item("groupBudget")
rs.Close

db.Close

If failures > 0 Then
  WScript.Echo failures & " check(s) failed"
  WScript.Quit 1
End If
WScript.Echo "all checks passed"
WScript.Quit 0
