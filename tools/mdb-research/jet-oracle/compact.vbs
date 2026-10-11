' SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
' SPDX-License-Identifier: GPL-3.0-only

Dim e: Set e = CreateObject("DAO.DBEngine.36")
e.CompactDatabase WScript.Arguments(0), WScript.Arguments(1)
WScript.Echo "compact ok"
