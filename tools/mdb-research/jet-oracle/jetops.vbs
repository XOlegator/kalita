' usage: jetops.vbs db   -> Jet inserts/updates on top of file, then compact
Dim e, db, rs, i
Set e = CreateObject("DAO.DBEngine.36")
Set db = e.OpenDatabase(WScript.Arguments(0))
Set rs = db.OpenRecordset("DvizhenieDeneg", 2)
For i = 1 To 50
  rs.AddNew
  rs.Fields("Editor").Value = 3: rs.Fields("Creator").Value = 3: rs.Fields("DateEdit").Value = Now: rs.Fields("DateCreate").Value = Now: rs.Fields("del").Value = False
  rs.Fields("Date").Value = DateSerial(2026, 10, 1 + (i Mod 9)): rs.Fields("Summa").Value = 10 * i: rs.Fields("Currency_ID").Value = 12
  rs.Fields("GroupBudget_ID").Value = 44: rs.Fields("TypeOper_ID").Value = 2: rs.Fields("AccountFrom").Value = 1
  rs.Fields("Comment").Value = "Jet запись " & i & String(i * 40, "x")
  rs.Update
Next
rs.Close
db.Execute "UPDATE DvizhenieDeneg SET Comment = 'Jet update ' & id WHERE id MOD 17 = 0", 128
db.Execute "DELETE FROM Course WHERE id MOD 19 = 0", 128
db.Close
WScript.Echo "jetops ok"
