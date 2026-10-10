#!/bin/bash
# usage: xshot.sh db.mdb out.png
O=~/.cache/ycash-oracle; W=~/.local/share/StartWine/data/wine/GE-Proton10-34/bin
WINEPREFIX=$O/pfx $W/wineserver -k; sleep 2
X="$O/pfx/drive_c/Program Files (x86)/Business Control/X-Cash"; rm -f "$X/Data.ldb"; cp "$1" "$X/Data.mdb"
cd "$X" && (WINEPREFIX=$O/pfx WINEDEBUG=-all setsid $W/wine xCash.exe >/dev/null 2>&1 &)
sleep 22; spectacle -b -n -a -o "$2" 2>/dev/null
