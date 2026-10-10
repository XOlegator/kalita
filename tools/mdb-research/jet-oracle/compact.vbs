Dim e: Set e = CreateObject("DAO.DBEngine.36")
e.CompactDatabase WScript.Arguments(0), WScript.Arguments(1)
WScript.Echo "compact ok"
