#!/bin/bash
# Réseau Lobbik MODDÉ — serveurs de TEST Fabric (27/09/2026) : mêmes ports que l'ancien test Paper (proxy :25600, hub :25601,
# tour :25602, cube :25603, contrôle 25680-25682), dossiers /srv/mctest/f-{hub,tour,cube}. Ne touche pas à la production.
set -e
S=/root/mctest-fabric-stage; T=/srv/mctest; J=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
id mctest >/dev/null 2>&1 || useradd --system --home-dir $T --create-home --shell /usr/sbin/nologin mctest
. $T/secrets.env
SITE=$(sed -n 's/^cle: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' /srv/minecraft/serveur/plugins/KrpTour/config.yml | head -1)
serveur() {   # nom port contrôle places rôle nom-affiché couleur
  local n=$1 port=$2 ctl=$3 places=$4 role=$5 nom=$6 coul=$7 D=$T/f-$1
  mkdir -p $D/mods $D/config $D/world/datapacks
  cp $S/fabric-server.jar $D/
  python3 - "$S" "$D/mods" <<'PY'
import json,sys,os,hashlib,urllib.request
S,dos=sys.argv[1],sys.argv[2]; d=json.load(open(S+'/atelier-mods.json')); l=[m for m in d['mods'] if m.get('cote')!='client']+[json.load(open(S+'/fabricproxy-lite.json'))]
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
motd=$nom (test Fabric)
enable-rcon=true
rcon.port=$((ctl+10))
rcon.password=$CLE
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
cle_site=$SITE
envoi_site=false
pont_chat_site=false
lire_chat_site=false
membres_seulement=true
heure=18000
ouverture=45
C
  [ "$role" = tour ] && printf 'tour.graine=20260919\ntour.reseau=true\n' >> $D/config/lobbik.properties
  [ "$role" = lave ] && printf 'lave.graine=20260927\nlave.longueur=800\nlave.reseau=true\n' >> $D/config/lobbik.properties
  rm -rf $D/world/datapacks/krp-tour; cp -r $S/datapack/krp-tour $D/world/datapacks/
  cat > /etc/systemd/system/mctest-f-$n.service <<U
[Unit]
Description=Minecraft Lobbik TEST Fabric — $n
After=network.target
[Service]
User=mctest
WorkingDirectory=$D
ExecStart=$J -Xms192M -Xmx800M -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -jar fabric-server.jar nogui
Restart=on-failure
RestartSec=10
Nice=10
CPUWeight=40
MemoryHigh=1100M
MemoryMax=1250M
U
}
serveur hub  25601 25680 1000 hub  "Lobbik"  "#7CFF4F"
serveur tour 25602 25681 100  tour "La Tour" "#FFB347"
serveur cube 25603 25682 30   cube "Le Cube" "#5AB4FF"
[ "${SANS_LAVE:-}" = 1 ] || serveur lave 25604 25683 50   lave "Mer de lave" "#FF7A1A"   # 27/09/2026 : 4e jeu
# proxy de test : celui de l'ancien test (mêmes ports), avec les transferts acceptés
sed -i 's/^accepts-transfers = false/accepts-transfers = true/' $T/proxy/velocity.toml
chown -R mctest:mctest $T
systemctl daemon-reload
ufw allow 25600/tcp comment 'Minecraft TEST reseau Lobbik (proxy)' >/dev/null
echo "Installé. systemctl start mctest-f-hub mctest-f-tour mctest-f-cube mctest-proxy"
