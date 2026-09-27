#!/usr/bin/env python3
"""Liste blanche de l'Atelier = comptes Minecraft liés à Lobbik (mc_permis, même liste que La Tour et le Cube)."""
import json, os, re, sys, urllib.request, subprocess
sys.path.insert(0, '/root/bin'); import rconlib
A = '/srv/minecraft/atelier'
cle = re.search(r'^cle: *"?([^"\n]*)"?', open('/srv/minecraft/serveur/plugins/KrpTour/config.yml').read(), re.M).group(1)
txt = urllib.request.urlopen(urllib.request.Request('https://lobbik.com/auth/steam.php?a=mc_permis&cle=' + cle, headers={'User-Agent': 'lobbik-atelier/1.0'}), timeout=15).read().decode()
uuids = sorted({l.strip() for l in txt.splitlines() if re.fullmatch(r'[0-9a-f-]{36}', l.strip())})
if not uuids: sys.exit('liste vide : on garde l ancienne')
noms = {}
try:
    for e in json.load(open(A + '/usercache.json')): noms[e['uuid']] = e['name']
except Exception: pass
neuf = [{'uuid': u, 'name': noms.get(u, '')} for u in uuids]
f = A + '/whitelist.json'
try: ancien = json.load(open(f))
except Exception: ancien = None
if ancien != neuf:
    json.dump(neuf, open(f + '.tmp', 'w'), indent=1); os.replace(f + '.tmp', f)
    subprocess.run(['chown', 'minecraft:minecraft', f])
    if '--sans-rcon' not in sys.argv:
        try: rconlib.commande('whitelist reload', open(A + '/.rcon').read().strip())
        except Exception: pass
