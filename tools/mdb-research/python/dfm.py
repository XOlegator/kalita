import sys, struct
d=open(sys.argv[1],'rb').read()
class P:
    def __init__(s,b,o): s.b=b; s.o=o
    def u8(s): v=s.b[s.o]; s.o+=1; return v
    def n(s,k,f): v=struct.unpack_from(f,s.b,s.o)[0]; s.o+=k; return v
    def ss(s): l=s.u8(); v=s.b[s.o:s.o+l]; s.o+=l; return v.decode('cp1251','replace')
def val(p,t=None):
    t=p.u8() if t is None else t
    if t==0: return None
    if t==1:
        L=[]
        while p.b[p.o]!=0: L.append(val(p))
        p.o+=1; return L
    if t==2: return p.n(1,'<b')
    if t==3: return p.n(2,'<h')
    if t==4: return p.n(4,'<i')
    if t==5: p.o+=10; return 'ext'
    if t in (6,7): return p.ss()
    if t==8: return False
    if t==9: return True
    if t==10: l=p.n(4,'<I'); p.o+=l; return f'<bin {l}>'
    if t==11:
        L=[]
        while True:
            s=p.ss()
            if not s: break
            L.append(s)
        return L
    if t==12: l=p.n(4,'<I'); v=p.b[p.o:p.o+l].decode('cp1251','replace'); p.o+=l; return v
    if t==13: return 'nil'
    if t==14:
        L=[]
        while p.b[p.o]!=0:
            # collection item
            t2=p.b[p.o]
            if t2 in (2,3,4): val(p)
            p.o+=1 if p.b[p.o]==1 else 0
            item={}
            while p.b[p.o]!=0:
                k=p.ss(); item[k]=val(p)
            p.o+=1; L.append(item)
        p.o+=1; return L
    if t==15: return p.n(4,'<f')
    if t==16: return p.n(8,'<q')
    if t==17: return p.n(8,'<d')
    if t==18: l=p.n(4,'<I'); v=p.b[p.o:p.o+2*l].decode('utf-16le','replace'); p.o+=2*l; return v
    if t==19: return p.n(8,'<q')
    if t==20: l=p.n(4,'<I'); v=p.b[p.o:p.o+l].decode('utf8','replace'); p.o+=l; return v
    raise ValueError(f"type {t} at {p.o}")
KEEP=('Caption','Hint','ShortCut','Text','Items.Strings','Lines.Strings','Title','DisplayLabel','FieldName')
def obj(p,depth,out):
    b=p.b[p.o]
    if b&0xF0==0xF0:
        p.o+=1
        if b&2: val(p)
    cls=p.ss(); name=p.ss()
    props={}
    while p.b[p.o]!=0:
        k=p.ss(); v=val(p)
        if any(k.endswith(x) for x in KEEP) or k in ('Visible','Enabled','Align','Width','Height'): props[k]=v
    p.o+=1
    out.append('  '*depth+f"{cls} {name} "+(str({k:v for k,v in props.items()}) if props else ''))
    while p.b[p.o]!=0: obj(p,depth+1,out)
    p.o+=1
i=0
while True:
    i=d.find(b'TPF0',i)
    if i<0: break
    p=P(d,i+4); out=[]
    try:
        obj(p,0,out); print(f"\n##### DFM @{i:#x}"); print('\n'.join(out))
    except Exception as e:
        print(f"\n##### DFM @{i:#x} PARSE ERROR {e}"); print('\n'.join(out[:50]))
    i+=4
