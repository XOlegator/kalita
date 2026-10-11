# SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
# SPDX-License-Identifier: GPL-3.0-only

import sys, struct
def rc4(key, data):
    S=list(range(256)); j=0
    for i in range(256):
        j=(j+S[i]+key[i%len(key)])&255; S[i],S[j]=S[j],S[i]
    i=j=0; out=bytearray()
    for b in data:
        i=(i+1)&255; j=(j+S[i])&255; S[i],S[j]=S[j],S[i]; out.append(b^S[(S[i]+S[j])&255])
    return bytes(out)
d=open(sys.argv[1],'rb').read()
print("size",len(d),"pages",len(d)/4096)
h=bytearray(d[:4096])
dec=rc4(struct.pack('<I',0x6b39dac7), h[0x18:0x18+126])
h[0x18:0x18+126]=dec
print(h[0:0x100].hex(' '))
print("codepage(0x3c)",struct.unpack('<H',h[0x3c:0x3e]),"dbkey(0x3e)",hex(struct.unpack('<I',h[0x3e:0x42])[0]))
print("pwd raw",h[0x42:0x42+40].hex())
print("sortorder 0x6e?", h[0x6e:0x72].hex(), "0x72", h[0x72:0x78].hex())
# page 1/2 type bytes
for p in range(0,8): print(p, d[p*4096:p*4096+8].hex())
