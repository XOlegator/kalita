# SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
# SPDX-License-Identifier: GPL-3.0-only

import sys,struct
d=open(sys.argv[1],'rb').read(); PS=4096; N=len(d)//PS
def page(n): return d[n*PS:(n+1)*PS]
def row(ptr):
    pg=ptr>>8; r=ptr&0xff; p=page(pg)
    cnt=struct.unpack_from('<H',p,12)[0]
    offs=[struct.unpack_from('<H',p,14+2*i)[0] for i in range(cnt)]
    start=offs[r]&0x1fff; end=(offs[r-1]&0x1fff) if r>0 else PS
    return p[start:end], offs[r]
def umap(ptr):
    data,_=row(ptr); t=data[0]; pages=set()
    if t==0:
        start=struct.unpack_from('<I',data,1)[0]; bm=data[5:]
        for i in range(len(bm)*8):
            if bm[i//8]>>(i%8)&1: pages.add(start+i)
    else:
        for k in range((len(data)-1)//4):
            mp=struct.unpack_from('<I',data,1+4*k)[0]
            if mp==0: continue
            bm=page(mp)[4:]
            for i in range(len(bm)*8):
                if bm[i//8]>>(i%8)&1: pages.add(k*(PS-4)*8+i)
    return t,pages
for tdef in map(int,sys.argv[2:]):
    p=page(tdef); assert p[0]==2, p[:2]
    # jet4 tdef: usage maps ptrs at 0x37 (owned) and 0x3b (free)
    owned=struct.unpack_from('<I',p,0x37)[0]; free=struct.unpack_from('<I',p,0x3b)[0]
    nrows=struct.unpack_from('<I',p,16)[0]; autonum=struct.unpack_from('<I',p,20)[0]
    t,own=umap(owned)
    scan={n for n in range(N) if page(n)[0]==1 and struct.unpack_from('<I',page(n),4)[0]==tdef}
    print(f"tdef {tdef}: rows={nrows} nextAutoNum={autonum} umap type={t} owned={sorted(own)} scan={sorted(scan)} stale={sorted(scan-own)}")
