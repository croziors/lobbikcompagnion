#!/bin/bash
# Met les mods des serveurs Fabric (Oasis + test moddé) à la liste atelier-mods.json, sans rien toucher d'autre (27/09/2026).
# Les mods « cote: client » (animations) ne vont jamais sur un serveur. lobbik.jar et FabricProxy-Lite sont gardés.
# Oasis n'est relancé que s'il tourne ET qu'il est vide ; les serveurs de test sont relancés.
set -e
L=${1:-/root/mctest-fabric-stage/atelier-mods.json}
sync() {  # dossier-mods
python3 - "$L" "$1" <<'PY'
import json,sys,os,hashlib,urllib.request
d=json.load(open(sys.argv[1])); dos=sys.argv[2]; garder={'lobbik.jar'}
for f in os.listdir(dos):
    if f.startswith('FabricProxy-Lite'): garder.add(f)
for m in [m for m in d['mods'] if m.get('cote')!='client']:
    f=os.path.join(dos,m['fichier']); garder.add(m['fichier'])
    if os.path.exists(f) and hashlib.sha512(open(f,'rb').read()).hexdigest()==m['sha512']: continue
    data=urllib.request.urlopen(urllib.request.Request(m['url'],headers={'User-Agent':'lobbik-atelier/1.0 (lobbik.com)'})).read()
    if hashlib.sha512(data).hexdigest()!=m['sha512']: raise SystemExit('empreinte différente : '+m['fichier'])
    open(f+'.part','wb').write(data); os.replace(f+'.part', f); print(dos, '+', m['fichier'])
for f in os.listdir(dos):
    if f.endswith('.jar') and f not in garder: os.remove(os.path.join(dos,f)); print(dos, '-', f)
PY
}
for D in /srv/minecraft/atelier /srv/mctest/f-hub /srv/mctest/f-tour /srv/mctest/f-cube; do
  [ -d $D/mods ] || continue
  sync $D/mods
  chown -R --reference=$D $D/mods
done
if systemctl is-active -q minecraft-atelier.service; then
  N=$(python3 -c "import sys; sys.path.insert(0,'/root/mctest-fabric-stage'); import rconlib; print(rconlib.commande('list', open('/srv/minecraft/atelier/.rcon').read().strip(), 25577))" | sed -n 's/There are \([0-9]*\).*/\1/p')
  if [ "${N:-1}" = 0 ]; then systemctl restart minecraft-atelier.service; echo "Oasis relancé (vide)"; else echo "Oasis occupé ($N joueurs) : mods pris au prochain démarrage"; fi
fi
for s in f-hub f-tour f-cube; do systemctl is-active -q mctest-$s && systemctl restart mctest-$s && echo "relancé mctest-$s"; done
true
