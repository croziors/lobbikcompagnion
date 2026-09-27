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
serveur lave 25604 25683 50 lave "Mer de lave" "#FF7A1A"
chown -R mctest:mctest $T/f-lave
# proxy de test : 4e serveur, sa file (50 places), son port de contrôle, son nom et sa couleur
P=$T/proxy/velocity.toml; R=$T/proxy/plugins/lobbikreseau/reseau.properties
grep -q '^lave = ' $P || sed -i 's/^cube = "127.0.0.1:25603"$/cube = "127.0.0.1:25603"\nlave = "127.0.0.1:25604"/' $P
grep -q 'lave=' $R || sed -i 's/^limites=tour=100,cube=30$/limites=tour=100,cube=30,lave=50/' $R
grep -q '^controle.lave=' $R || printf 'controle.lave=25683\nnom.lave=Mer de lave\ncouleur.lave=#FF7A1A\n' >> $R
systemctl daemon-reload
echo "Mer de lave installée (test). systemctl start mctest-f-lave ; systemctl restart mctest-proxy"
