Dim cn, rs, f, sql
Set cn = CreateObject("ADODB.Connection")
cn.Open "Provider=Microsoft.Jet.OLEDB.4.0;Data Source=" & WScript.Arguments(0)
Set rs = cn.Execute(WScript.Arguments(1))
Do While Not rs.EOF
  s = ""
  For Each f In rs.Fields
    s = s & f.Name & "=" & f.Value & "; "
  Next
  WScript.Echo s
  rs.MoveNext
Loop
cn.Close
