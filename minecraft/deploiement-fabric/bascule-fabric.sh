#!/bin/bash
# Réseau Minecraft Lobbik → MODDÉ (Fabric) en production — 27/09/2026.
# Kripy : « sur le site on joue qu'avec le moddé, pourquoi tu as laissé l'ancien ». Même adresse (minecraft.lobbik.com), même proxy ;
# hub, La Tour et Le Cube passent en Fabric (mod « lobbik » + CC: Tweaked + Macaw's), et la Mer de lave arrive (4e jeu).
# Les anciens serveurs Paper ne sont PAS effacés : retour arrière = retour-paper.sh <dossier de sauvegarde>.
# Lancer sur le serveur de jeu : bash /root/bascule-fabric/bascule-fabric.sh   (FORCE=1 si des joueurs sont en ligne)
set -euo pipefail
S=$(cd "$(dirname "$0")" && pwd)
V=/mnt/HC_Volume_106906374/minecraft
J=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
B=/mnt/HC_Volume_106906374/sauvegardes/avant-fabric-$(date +%Y%m%d-%H%M)
. /srv/minecraft/secrets-reseau.env          # CLE (billets), FWD (transfert moderne du proxy)
CLE_SITE=$(sed -n 's/^cle_site: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' /srv/minecraft/hub/plugins/KrpReseau/config.yml | head -1)
[ -n "$CLE_SITE" ] && [ -n "$CLE" ] && [ -n "$FWD" ] || { echo "clés introuvables"; exit 1; }
N=$(python3 $S/joueurs_en_ligne.py 127.0.0.1 25565)
if [ "$N" != 0 ] && [ "${FORCE:-}" != 1 ]; then echo "$N joueur(s) en ligne : rien n'est fait (FORCE=1 pour passer outre)"; exit 1; fi

echo "== 1. serveurs de test arrêtés (mémoire)"
for u in mctest-proxy mctest-f-hub mctest-f-tour mctest-f-cube mctest-f-lave; do systemctl stop $u 2>/dev/null || true; systemctl disable $u 2>/dev/null || true; done

echo "== 2. anciens serveurs arrêtés, sauvegarde dans $B"
systemctl stop minecraft-hub minecraft minecraft-cube
mkdir -p $B/units
cp -p /etc/systemd/system/minecraft-hub.service /etc/systemd/system/minecraft.service /etc/systemd/system/minecraft-cube.service $B/units/
cp -p /srv/minecraft/proxy/velocity.toml $B/velocity.toml; cp -p /srv/minecraft/proxy/plugins/lobbikreseau/reseau.properties $B/reseau.properties
cp -p /root/rcon-pont.py $B/rcon-pont.py
tar -C $V -czf $B/paper-hub-serveur-cube.tgz hub serveur cube
echo "sauvegarde : $(du -sh $B | cut -f1)"

RCON_TOUR=$(sed -n 's/^rcon.password=//p' $V/serveur/server.properties); RCON_CUBE=$(sed -n 's/^rcon.password=//p' $V/cube/server.properties)
RCON_LAVE=$( [ -f $V/f-lave/.rcon ] && cat $V/f-lave/.rcon || openssl rand -hex 12 )

serveur() {   # nom port contrôle places rôle nom-affiché couleur rcon-port rcon-mdp xmx
  local n=$1 port=$2 ctl=$3 places=$4 role=$5 nom=$6 coul=$7 rport=$8 rmdp=$9 xmx=${10} D=$V/f-$1
  mkdir -p $D/mods $D/config $D/world/datapacks
  ln -sfn $D /srv/minecraft/f-$n
  cp $S/fabric-server.jar $D/
  python3 - "$S" "$D/mods" <<'PY'
import json,sys,os,hashlib,urllib.request
S,dos=sys.argv[1],sys.argv[2]; d=json.load(open(S+'/atelier-mods.json'))
l=[m for m in d['mods'] if m.get('cote')!='client']+[json.load(open(S+'/fabricproxy-lite.json'))]
garder={'lobbik.jar'}
for m in l:
    f=os.path.join(dos,m['fichier']); garder.add(m['fichier'])
    if os.path.exists(f) and hashlib.sha512(open(f,'rb').read()).hexdigest()==m['sha512']: continue
    data=urllib.request.urlopen(urllib.request.Request(m['url'],headers={'User-Agent':'lobbik/1.0'})).read()
    if hashlib.sha512(data).hexdigest()!=m['sha512']: raise SystemExit('empreinte '+m['fichier'])
    open(f,'wb').write(data)
for f in os.listdir(dos):
    if f.endswith('.jar') and f not in garder: os.remove(os.path.join(dos,f))
PY
  cp $S/lobbik.jar $D/mods/lobbik.jar
  echo "eula=true" > $D/eula.txt
  cat > $D/server.properties <<P
server-ip=127.0.0.1
server-port=$port
online-mode=false
enforce-secure-profile=false
prevent-proxy-connections=false
view-distance=12
simulation-distance=4
max-players=$places
level-name=world
level-seed=565165537861651668
level-type=minecraft\:flat
generator-settings={"layers"\:[],"biome"\:"minecraft\:plains"}
allow-nether=false
spawn-protection=0
gamemode=adventure
force-gamemode=true
difficulty=peaceful
pvp=false
network-compression-threshold=-1
sync-chunk-writes=false
max-tick-time=-1
motd=$nom
enable-rcon=$([ -n "$rport" ] && echo true || echo false)
rcon.port=${rport:-25574}
rcon.password=$rmdp
broadcast-rcon-to-ops=false
P
  cat > $D/config/FabricProxy-Lite.toml <<C
hackOnlineMode = true
hackEarlySend = true
hackMessageChain = true
disconnectMessage = "Passez par Lobbik (minecraft.lobbik.com)."
secret = "$FWD"
C
  cat > $D/config/lobbik.properties <<C
role=$role
nom=$nom
couleur=$coul
hub_nom=hub
port_controle=$ctl
cle_reseau=$CLE
site=https://lobbik.com
cle_site=$CLE_SITE
envoi_site=true
pont_chat_site=true
lire_chat_site=$([ "$role" = hub ] && echo true || echo false)
membres_seulement=true
heure=18000
ouverture=45
tour.graine=20260919
tour.reseau=true
cube.graine=20260923
lave.graine=20260927
lave.longueur=800
lave.reseau=true
ecrans.tour_adresse=127.0.0.1:25571
ecrans.cube_adresse=127.0.0.1:25572
ecrans.lave_adresse=127.0.0.1:25573
C
  rm -rf $D/world/datapacks/krp-tour; cp -r $S/datapack/krp-tour $D/world/datapacks/
  chown -R minecraft:minecraft $D
  local unite=$([ "$n" = tour ] && echo minecraft || echo minecraft-$n)
  cat > /etc/systemd/system/$unite.service <<U
[Unit]
Description=Minecraft Lobbik (moddé, Fabric) — $nom
After=network.target
[Service]
User=minecraft
WorkingDirectory=$D
ExecStart=$J -Xms384M -Xmx$xmx -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -jar fabric-server.jar nogui
Restart=on-failure
RestartSec=10
SuccessExitStatus=0 1 130 143
[Install]
WantedBy=multi-user.target
U
}

echo "== 3. serveurs moddés"
serveur hub  25570 25580 1000 hub  "Lobbik"      "#7CFF4F" ""    ""          1024M
serveur tour 25571 25581 100  tour "La Tour"     "#FFB347" 25575 "$RCON_TOUR" 1024M
serveur cube 25572 25582 30   cube "Le Cube"     "#5AB4FF" 25576 "$RCON_CUBE" 1024M
serveur lave 25573 25583 50   lave "Mer de lave" "#FF7A1A" 25579 "$RCON_LAVE" 896M
echo -n "$RCON_LAVE" > $V/f-lave/.rcon; chmod 600 $V/f-lave/.rcon; chown minecraft:minecraft $V/f-lave/.rcon

echo "== 4. données des joueurs reprises (La Tour : niveaux, records, positions ; Le Cube : records)"
mkdir -p $V/f-tour/lobbik/tour $V/f-cube/plugins/KrpCube
cp -p $V/serveur/plugins/KrpTour/joueurs.json $V/serveur/plugins/KrpTour/positions.json $V/f-tour/lobbik/tour/ 2>/dev/null || true
cp -p $V/cube/plugins/KrpCube/records.yml $V/f-cube/plugins/KrpCube/ 2>/dev/null || true
chown -R minecraft:minecraft $V/f-tour/lobbik $V/f-cube/plugins

echo "== 5. proxy : 4e serveur (Mer de lave)"
P=/srv/minecraft/proxy/velocity.toml; R=/srv/minecraft/proxy/plugins/lobbikreseau/reseau.properties
grep -q '^lave = ' $P || sed -i 's/^cube = "127.0.0.1:25572"$/cube = "127.0.0.1:25572"\nlave = "127.0.0.1:25573"/' $P
sed -i 's/^limites=tour=100,cube=30$/limites=tour=100,cube=30,lave=50/' $R
grep -q '^controle.lave=' $R || printf 'controle.lave=25583\nnom.lave=Mer de lave\ncouleur.lave=#FF7A1A\n' >> $R

echo "== 6. pont du site : Mer de lave pilotable (démarrer / arrêter / relancer)"
grep -q "'mc-lave'" /root/rcon-pont.py || sed -i "s/'mc-cube': 'minecraft-cube', 'oasis': 'minecraft-atelier'}/'mc-cube': 'minecraft-cube', 'mc-lave': 'minecraft-lave', 'oasis': 'minecraft-atelier'}/" /root/rcon-pont.py
python3 -m py_compile /root/rcon-pont.py && systemctl restart krp-pont

echo "== 7. démarrage"
systemctl daemon-reload
systemctl enable minecraft-lave >/dev/null 2>&1
for u in minecraft-hub minecraft minecraft-cube minecraft-lave; do systemctl start $u; done
for i in $(seq 1 120); do
  ok=0; for u in minecraft-hub minecraft minecraft-cube minecraft-lave; do journalctl -u $u --since "-6min" --no-pager -o cat | grep -q "Lobbik prêt" && ok=$((ok+1)); done
  [ $ok = 4 ] && break; sleep 3
done
systemctl restart minecraft-proxy; sleep 6
for u in minecraft-proxy minecraft-hub minecraft minecraft-cube minecraft-lave; do
  echo "$u : $(systemctl is-active $u) · $(journalctl -u $u --since '-6min' --no-pager -o cat | grep -c -E 'Exception|ERROR') erreur(s) · $(journalctl -u $u --since '-6min' --no-pager -o cat | grep -E 'Lobbik prêt|Réseau Lobbik prêt' | tail -1 | cut -c1-80)"
done
free -m | sed -n 2,3p
echo "BASCULE FAITE — sauvegarde : $B — retour : bash $S/retour-paper.sh $B"
