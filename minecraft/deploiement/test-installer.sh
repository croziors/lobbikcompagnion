#!/bin/bash
# Réseau Minecraft Lobbik — serveurs de TEST (27/09/2026), sur le serveur de jeu, sans toucher à La Tour (25565) ni au Cube (25566).
#   proxy  : 0.0.0.0:25600 (public)          → tour.lobbik.com:25600
#   hub    : 127.0.0.1:25601  contrôle 25680
#   tour   : 127.0.0.1:25602  contrôle 25681
#   cube   : 127.0.0.1:25603  contrôle 25682
# Utilisateur mctest, dossier /srv/mctest, services mctest-*.service (mémoire plafonnée, priorité basse).
# Relancer ce script met à jour jars et réglages ; les mondes de test restent. Tout retirer : desinstaller.sh
set -e
S=/root/mctest-stage; T=/srv/mctest
J=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
PROD=/srv/minecraft/serveur
id mctest >/dev/null 2>&1 || useradd --system --home-dir $T --create-home --shell /usr/sbin/nologin mctest
mkdir -p $T/proxy/plugins/lobbikreseau
if [ ! -f $T/secrets.env ]; then printf 'FWD=%s\nCLE=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" > $T/secrets.env; chmod 600 $T/secrets.env; fi
. $T/secrets.env
SITE=$(sed -n 's/^cle: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' $PROD/plugins/KrpTour/config.yml | head -1)
[ -n "$SITE" ] || { echo "clé du site introuvable"; exit 1; }

# ---------- proxy
cp $S/velocity.jar $S/velocity.toml $T/proxy/
printf %s "$FWD" > $T/proxy/forwarding.secret
cp $S/LobbikReseau.jar $T/proxy/plugins/
sed -e "s/^cle=.*/cle=$CLE/" -e 's/^controle.hub=.*/controle.hub=25680/' -e 's/^controle.tour=.*/controle.tour=25681/' -e 's/^controle.cube=.*/controle.cube=25682/' $S/reseau.properties > $T/proxy/plugins/lobbikreseau/reseau.properties

# ---------- serveurs Paper : nom port contrôle places générateur difficulté
backend() {
  local n=$1 port=$2 ctl=$3 places=$4 gen=$5 diff=$6 motd=$7
  local D=$T/$n
  mkdir -p $D/config $D/plugins $D/world/datapacks
  cp $PROD/paper.jar $D/paper.jar
  for x in libraries versions cache; do [ -d $D/$x ] || cp -r $PROD/$x $D/; done
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
allow-nether=false
spawn-protection=0
gamemode=adventure
force-gamemode=true
difficulty=$diff
network-compression-threshold=-1
sync-chunk-writes=false
enable-rcon=false
motd=$motd
P
  [ -f $D/bukkit.yml ] || printf 'settings:\n  allow-end: false\n  connection-throttle: -1\nworlds:\n  world:\n    generator: %s\n' "$gen" > $D/bukkit.yml
  [ -f $D/config/paper-global.yml ] || cp $PROD/config/paper-global.yml $D/config/paper-global.yml
  if true; then
    python3 - "$D/config/paper-global.yml" "$FWD" <<'PY'
import sys,re
p,sec=sys.argv[1],sys.argv[2]; s=open(p).read()
s=re.sub(r'(?ms)(  velocity:\n    enabled: )\w+(\n    online-mode: )\w+(\n    secret: )[^\n]*', lambda m: m.group(1)+'true'+m.group(2)+'true'+m.group(3)+"'"+sec+"'", s)
open(p,'w').write(s)
PY
  fi
  grep -q "enabled: true" <(sed -n '/^  velocity:/,+3p' $D/config/paper-global.yml) || { echo "proxy Velocity non activé dans $D"; exit 1; }
  rm -rf $D/world/datapacks/krp-tour; cp -r $S/datapack/krp-tour $D/world/datapacks/
  # passage par le pont : le joueur continue de marcher pendant ~1 s ; on tolère l'écart au lieu de le ramener en arrière
  [ -f $D/spigot.yml ] && sed -i 's/^\(\s*moved-too-quickly-multiplier:\).*/\1 100.0/' $D/spigot.yml
  cp $S/KrpReseau.jar $D/plugins/
  mkdir -p $D/plugins/KrpReseau
}
backend hub  25601 25680 1000 KrpReseau peaceful "Lobbik (test)"
backend tour 25602 25681 100  KrpTour   hard     "La Tour (test)"
backend cube 25603 25682 30   KrpCube   normal   "Le Cube (test)"

reseau() {   # nom rôle port-contrôle nom-affiché couleur
  cat > $T/$1/plugins/KrpReseau/config.yml <<C
role: $2
nom: "$4"
couleur: "$5"
hub_nom: hub
port_controle: $3
cle_reseau: "$CLE"
site: https://lobbik.com
cle_site: "$SITE"
envoi_site: false
pont_chat_site: false
lire_chat_site: false
membres_seulement: true
heure: 18000
ouverture: 45
replique_tour: true
replique_cube: true
graine_tour: 20260919
graine_cube: 20260923
cube_largeur: 10
cube_niveaux: 10
cube_profondeur: 10
cube_pieges: 20
ecrans_tour: $T/tour/plugins/KrpTour/direct.json
ecrans_cube: $T/cube/plugins/KrpCube/direct.json
C
}
reseau hub  hub  25680 "Lobbik"  "#7CFF4F"
reseau tour tour 25681 "La Tour" "#FFB347"
reseau cube cube 25682 "Le Cube" "#5AB4FF"

cp $S/KrpTour.jar $T/tour/plugins/; mkdir -p $T/tour/plugins/KrpTour
printf 'hub: https://lobbik.com\ncle: "%s"\ngraine: 20260919\nenvoi_site: false\nreseau:\n  actif: true\n' "$SITE" > $T/tour/plugins/KrpTour/config.yml
cp $S/KrpCube.jar $T/cube/plugins/; mkdir -p $T/cube/plugins/KrpCube
python3 - "$T/cube/plugins/KrpCube/config.yml" "$SITE" <<'PY'
import sys,re
s=open('/srv/minecraft/cube/plugins/KrpCube/config.yml').read()
s=re.sub(r'(?m)^cle: .*$', 'cle: "'+sys.argv[2]+'"', s)
s+='\nenvoi_site: false\nreseau:\n  actif: true\n  origine_x: -300\n  origine_z: -50\n  vestibule: "-196,-190,-2,2,64,68"\n  sortie_pont: "-186.5,64,0.5,-90"\n'
open(sys.argv[1],'w').write(s)
PY
chown -R mctest:mctest $T

# ---------- services (mémoire plafonnée : en cas d'excès, seul le test s'arrête ; priorité CPU basse)
unit() {   # nom dossier commande mémoire
  cat > /etc/systemd/system/mctest-$1.service <<U
[Unit]
Description=Minecraft Lobbik TEST — $1 (réseau sans coupure, 27/09/2026)
After=network.target

[Service]
User=mctest
WorkingDirectory=$T/$2
ExecStart=$3
Restart=on-failure
RestartSec=10
Nice=10
CPUWeight=40
MemoryHigh=$4
MemoryMax=$5
SuccessExitStatus=0 1 143

[Install]
WantedBy=multi-user.target
U
}
G="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC"
unit proxy proxy "$J -Xms64M -Xmx192M $G -jar velocity.jar" 300M 380M
unit hub hub "$J -Xms256M -Xmx768M $G -jar paper.jar --nogui" 1000M 1150M
unit tour tour "$J -Xms192M -Xmx640M $G -jar paper.jar --nogui" 850M 980M
unit cube cube "$J -Xms192M -Xmx640M $G -jar paper.jar --nogui" 850M 980M
systemctl daemon-reload
ufw allow 25600/tcp comment 'Minecraft TEST reseau Lobbik (proxy)' >/dev/null
echo "Installé. Démarrer : systemctl start mctest-hub mctest-tour mctest-cube mctest-proxy"
