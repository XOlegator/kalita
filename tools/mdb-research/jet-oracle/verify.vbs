' SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
' SPDX-License-Identifier: GPL-3.0-only

' usage: verify.vbs db out tables
Dim e, db, rs, s, i, fso, out, t, v, fld
Set e = CreateObject("DAO.DBEngine.36")
Set fso = CreateObject("Scripting.FileSystemObject")
Set out = fso.CreateTextFile(WScript.Arguments(1), True, True)
Set db = e.OpenDatabase(WScript.Arguments(0))
For Each t In Split(WScript.Arguments(2), ",")
  Set rs = db.OpenRecordset("SELECT * FROM [" & t & "]", 4)
  Do While Not rs.EOF
    s = t & "|"
    For i = 0 To rs.Fields.Count - 1
      Set fld = rs.Fields(i): v = fld.Value
      If IsNull(v) Then
        v = ""
      ElseIf fld.Type = 1 Then
        If v Then v = "1" Else v = "0"
      ElseIf fld.Type = 8 Then
        d = v
        v = Year(d) & "-" & Right("0" & Month(d),2) & "-" & Right("0" & Day(d),2) & " " & Right("0" & Hour(d),2) & ":" & Right("0" & Minute(d),2)
        If Second(d) <> 0 Then v = v & ":" & Right("0" & Second(d),2)
      ElseIf fld.Type = 7 Then
        v = Replace(FormatNumber(v, 6, -1, 0, 0), ",", ".")
      ElseIf fld.Type = 5 Then
        v = Replace(CStr(v), ",", ".")
      Else
        v = Replace(Replace(CStr(v), vbCr, ""), vbLf, "\n")
      End If
      s = s & fld.Name & "=" & v & ";"
    Next
    out.WriteLine s
    rs.MoveNext
  Loop
  rs.Close
  ' walk every index via table-type recordset
  Dim td, idx, cnt
  Set td = db.TableDefs(t)
  For Each idx In td.Indexes
    Set rs = db.OpenRecordset(t, 1)
    rs.Index = idx.Name
    cnt = 0
    Do While Not rs.EOF: cnt = cnt + 1: rs.MoveNext: Loop
    WScript.Echo "INDEX " & t & "." & idx.Name & " rows=" & cnt & " recordcount=" & td.RecordCount
    rs.Close
  Next
Next
db.Close
out.Close
