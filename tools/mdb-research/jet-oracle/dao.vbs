Dim e, db, rs, s, f, i
Set e = CreateObject("DAO.DBEngine.36")
Set db = e.OpenDatabase(WScript.Arguments(0))
Set rs = db.OpenRecordset(WScript.Arguments(1))
Do While Not rs.EOF
  s = ""
  For i = 0 To rs.Fields.Count - 1
    s = s & rs.Fields(i).Name & "=" & rs.Fields(i).Value & "; "
  Next
  WScript.Echo s
  rs.MoveNext
Loop
db.Close
