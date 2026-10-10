import sys, logging
logging.disable(logging.CRITICAL)
from access_parser import AccessParser
T={1:'Boolean',2:'Byte',3:'Integer(16)',4:'Long(32)',5:'Currency',6:'Single',7:'Double',8:'DateTime',9:'Binary',10:'Text',11:'OLE',12:'Memo',15:'GUID',16:'Numeric'}
db = AccessParser(sys.argv[1])
id2name={}
for name in db.catalog:
    at=db.get_table(name)
    if at is None or at.columns is None: print("=== ",name,"FAIL"); continue
    id2name[at.table.offset//db.page_size]=name
for name in db.catalog:
    if len(sys.argv)>2 and name not in sys.argv[2:]: continue
    at=db.get_table(name)
    if at is None or at.columns is None: continue
    th=at.table_header
    print(f"\n=== {name}  (tdef page {at.table.offset//db.page_size}) cols={th.column_count} idx={th.index_count} realidx={th.real_index_count}")
    byid={}
    for i,c in sorted(at.columns.items()):
        f=c.column_flags
        fl=[k for k,v in dict(f).items() if v is True]
        byid[c.column_id]=c.col_name_str
        extra=""
        if c.type in (10,12): extra=f" collation={c.various.collation}"
        if c.type==16: extra=f" prec={c.various.prec} scale={c.various.scale}"
        props = c.extra_props or {}
        print(f"  COL #{c.column_index} id={c.column_id} {c.col_name_str!r}: {T.get(c.type,c.type)} len={c.length} flags={fl}{extra} props={props}")
    for k,ai in enumerate(th.all_indexes):
        nm=th.index_names[k].idx_name_str
        ri=th.real_index_2[ai.idx_col_num]
        cols=[(byid.get(x.col_id,x.col_id), 'ASC' if x.idx_flags==1 else 'DESC') for x in ri.unk_struct if x.col_id!=0xFFFF]
        fl=ri.flags
        flags=[n for b,n in ((1,'UNIQUE'),(2,'IGNORE_NULLS'),(8,'REQUIRED')) if fl&b]
        typ={1:'PK',2:'FK'}.get(ai.idx_type,'')
        rel=f" rel_tbl={id2name.get(ai.rel_tbl_page,ai.rel_tbl_page)} rel_type={ai.rel_tbl_type} casc_upd={ai.cascade_ups} casc_del={ai.cascade_dels}" if ai.rel_idx_num!=-1 else ""
        print(f"  IDX {nm!r} {typ} {cols} {flags}{rel}")
