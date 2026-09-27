#!/usr/bin/env python3
"""Nombre de joueurs en ligne (ping Minecraft) : joueurs_en_ligne.py hôte port → affiche un nombre, ou -1 si injoignable."""
import json, socket, struct, sys
def varint(n):
    o = b''
    while True:
        b = n & 0x7f; n >>= 7; o += bytes([b | (0x80 if n else 0)])
        if not n: return o
try:
    h, p = sys.argv[1], int(sys.argv[2]); s = socket.create_connection((h, p), timeout=5)
    hs = varint(0) + varint(776) + varint(len(h)) + h.encode() + struct.pack('>H', p) + varint(1)
    s.sendall(varint(len(hs)) + hs + varint(1) + varint(0)); d = b''
    while True:
        c = s.recv(65536)
        if not c: break
        d += c
        try:
            i = 0
            def rv():
                global i
                n = 0; k = 0
                while True:
                    b = d[i]; i += 1; n |= (b & 0x7f) << (7 * k); k += 1
                    if not b & 0x80: return n
            L = rv()
            if len(d) < i + L: continue
            rv(); sl = rv(); print(json.loads(d[i:i + sl])['players']['online']); break
        except IndexError: continue
except Exception:
    print(-1)
