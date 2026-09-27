#!/bin/bash
# Lobbik — à lancer sur la VM du site JUSTE APRÈS bascule.sh (27/09/2026) : une seule adresse Minecraft, minecraft.lobbik.com
# (le hub ; La Tour et Le Cube s'atteignent par les ponts). Page Jouer + liste des serveurs installée par le Compagnon.
set -e
B=/srv/aoe/sauvegardes-code/minecraft-reseau-$(date +%Y%m%d-%H%M); mkdir -p $B
cp -p /etc/aoe4/minecraft_site.json /srv/aoe/web/aoe/assets/app.js /srv/aoe/web/aoe/index.html /srv/aoe/web/aoe/sw.js $B/
python3 - <<'PY'
import json,re,time
p='/etc/aoe4/minecraft_site.json'; d=json.load(open(p))
d['hote']='minecraft.lobbik.com'; d['cube_hote']='minecraft.lobbik.com'; d['cube_port']=25565; d['nom']='Lobbik — hub, La Tour, Le Cube'
json.dump(d, open(p,'w'), ensure_ascii=False)
p='/srv/aoe/web/aoe/assets/app.js'; s=open(p).read()
old="serveurs: [{ nom: 'Lobbik · La Tour', adresse: d.adresse || 'tour.lobbik.com' }, { nom: 'Lobbik · Le Cube', adresse: d.cube_adresse || 'cube.lobbik.com' }] });"
if old in s: s=s.replace(old, "serveurs: [{ nom: 'Lobbik', adresse: d.adresse || 'minecraft.lobbik.com' }] });   // 27/09/2026 : une seule entrée, le hub (ponts vers La Tour et Le Cube)")
open(p,'w').write(s)
p='/srv/aoe/web/aoe/index.html'; s=open(p).read(); s=re.sub(r'/assets/app\.js\?v=\d+', '/assets/app.js?v=%d' % int(time.time()), s, count=1); open(p,'w').write(s)
p='/srv/aoe/web/aoe/sw.js'; s=open(p).read(); m=re.search(r"const V = 'aoe4-v(\d+)';", s); s=s.replace(m.group(0), "const V = 'aoe4-v%d';" % (int(m.group(1))+1)); open(p,'w').write(s)
PY
rm -f /tmp/hub-mc-srv-*.json /tmp/hub-mc-etat.json 2>/dev/null || true
echo "Site : adresse Minecraft = minecraft.lobbik.com (sauvegarde : $B)"
