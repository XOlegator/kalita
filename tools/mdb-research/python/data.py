# SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
# SPDX-License-Identifier: GPL-3.0-only

import sys, logging
logging.disable(logging.CRITICAL)
from access_parser import AccessParser
db = AccessParser(sys.argv[1]); maxr=int(sys.argv[2]); skip=set(sys.argv[3].split(',')) if len(sys.argv)>3 else set()
for name in sys.argv[4:] if len(sys.argv)>4 else db.catalog:
    t=db.parse_table(name); cols=[c for c in t.keys() if c not in skip]
    n=len(t[cols[0]]) if cols and isinstance(t[cols[0]],list) else 0
    print(f"\n=== {name} rows={n}")
    seen=[]
    for r in range(n):
        d={c: t[c][r] for c in cols if t[c][r] not in (None,'')}
        if d in seen: continue
        seen.append(d)
    print(f"   unique={len(seen)}")
    for d in seen[:maxr]: print("  ", d)
