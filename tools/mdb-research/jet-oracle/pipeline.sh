#!/bin/bash
# usage: pipeline.sh <variant-dir>
set -u
S=/tmp/claude-1000/-mnt-projects-sites-ycash/081c7167-895d-4dc3-b2f8-8d6e13d99f97/scratchpad; O=~/.cache/ycash-oracle; W=~/.local/share/StartWine/data/wine/GE-Proton10-34/bin/wine
TB=DvizhenieDeneg,GroupBudget,Account,Course,People,Currency,TypeOper
v=$1; D=$S/$v; WK=$O/pfx/drive_c/work
jet(){ (cd $WK && WINEPREFIX=$O/pfx WINEDEBUG=-all timeout 300 $W 'C:\windows\syswow64\cscript.exe' //nologo "$@" 2>&1 | grep -v wineserver | iconv -f cp1251 -t utf8); }
jack(){ (cd $D && java -cp "*" $( [ -f $D/Export.java ] && echo Export.java || echo ../Export.java ) "$1" $TB 2>/dev/null | LC_ALL=C sort); }
jetexp(){ rm -f $WK/out.txt; jet 'C:\work\verify.vbs' "C:\\work\\$1" 'C:\work\out.txt' $TB | awk '{split($3,a,"=");split($4,b,"="); if(a[2]!=b[2]) print "INDEX MISMATCH",$0}' >&2; iconv -f utf-16 -t utf-8 $WK/out.txt | tr -d '\r' | LC_ALL=C sort; }
cmp_(){ local n=$(diff "$1" "$2" | grep -c '^[<>]'); echo "  $3: lines=$(wc -l <"$1")/$(wc -l <"$2") diffs=$n"; [ $n -gt 0 ] && diff "$1" "$2" | head -4 | cut -c1-200; }
echo "== $v"
cp $D/test.mdb $WK/$v.mdb
jetexp $v.mdb > $D/jet1.txt; jack $D/test.mdb > $D/jack1.txt; cmp_ $D/jet1.txt $D/jack1.txt "Jackcess-write: Jet vs Jackcess read"
jet 'C:\work\jetops.vbs' "C:\\work\\$v.mdb"
cp $WK/$v.mdb $D/afterjet.mdb
jetexp $v.mdb > $D/jet2.txt; jack $D/afterjet.mdb > $D/jack2.txt; cmp_ $D/jet2.txt $D/jack2.txt "after Jet writes: Jet vs Jackcess read"
rm -f $WK/$v.c.mdb; jet 'C:\work\compact.vbs' "C:\\work\\$v.mdb" "C:\\work\\$v.c.mdb"
jetexp $v.c.mdb > $D/jet3.txt; cmp_ $D/jet2.txt $D/jet3.txt "Jet CompactDatabase preserves data"
