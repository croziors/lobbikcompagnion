#!/bin/bash
# Bascule du Minecraft Lobbik en réseau (27/09/2026, demande de Kripy) :
#   proxy (0.0.0.0:25565, et 25566 redirigé) → hub 127.0.0.1:25570 · La Tour 127.0.0.1:25571 · Le Cube 127.0.0.1:25572
# La Tour garde son monde et ses données ; le Cube garde ses records, son monde est reconstruit à côté du hub
# (l'ancien est mis de côté). Tout est sauvegardé avant. Retour arrière : retour-arriere.sh (même dossier).
set -e
S=/root/reseau-prod-stage; M=/srv/minecraft; V=/mnt/HC_Volume_106906374
J=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
QUAND=$(date +%Y%m%d-%H%M)
SAUVE=$V/sauvegardes/avant-bascule-reseau-$QUAND
SEED=565165537861651668

echo "== 1. arrêt du test et des serveurs actuels"
systemctl stop mctest-proxy mctest-hub mctest-tour mctest-cube 2>/dev/null || true
systemctl disable mctest-proxy mctest-hub mctest-tour mctest-cube 2>/dev/null || true
systemctl stop minecraft minecraft-cube

echo "== 2. sauvegarde (serveurs arrêtés) → $SAUVE"
mkdir -p $SAUVE
cp /etc/systemd/system/minecraft.service /etc/systemd/system/minecraft-cube.service $SAUVE/
for d in serveur cube; do
  tar --exclude="$d/libraries" --exclude="$d/versions" --exclude="$d/cache" --exclude="$d/logs" -czf $SAUVE/$d.tgz -C $V/minecraft $d   # /srv/minecraft/<d> est un lien vers le volume
done
ls -la $SAUVE

if [ ! -f $M/secrets-reseau.env ]; then printf 'FWD=%s\nCLE=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" > $M/secrets-reseau.env; chmod 600 $M/secrets-reseau.env; chown minecraft:minecraft $M/secrets-reseau.env; fi
. $M/secrets-reseau.env
SITE=$(sed -n 's/^cle: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' $M/serveur/plugins/KrpTour/config.yml | head -1)
[ -n "$SITE" ] || { echo "clé du site introuvable"; exit 1; }

prop() { # fichier clé valeur
  if grep -q "^$2=" "$1"; then sed -i "s|^$2=.*|$2=$3|" "$1"; else echo "$2=$3" >> "$1"; fi
}
velocite() { # dossier
  python3 - "$1/config/paper-global.yml" "$FWD" <<'PY'
import sys,re
p,sec=sys.argv[1],sys.argv[2]; s=open(p).read()
s2=re.sub(r'(?ms)(  velocity:\n    enabled: )\w+(\n    online-mode: )\w+(\n    secret: )[^\n]*', lambda m: m.group(1)+'true'+m.group(2)+'true'+m.group(3)+"'"+sec+"'", s)
assert s2!=s or "enabled: true" in s
open(p,'w').write(s2)
PY
  if [ -f $1/spigot.yml ]; then sed -i 's/^\(\s*moved-too-quickly-multiplier:\).*/\1 100.0/' $1/spigot.yml; else printf 'settings:\n  moved-too-quickly-multiplier: 100.0\n' > $1/spigot.yml; fi
}
reseau() { # dossier rôle port nom couleur pont_chat lire_chat
  mkdir -p $1/plugins/KrpReseau
  cp $S/KrpReseau.jar $1/plugins/
  cat > $1/plugins/KrpReseau/config.yml <<C
role: $2
nom: "$4"
couleur: "$5"
hub_nom: hub
port_controle: $3
cle_reseau: "$CLE"
site: https://lobbik.com
cle_site: "$SITE"
envoi_site: true
pont_chat_site: $6
lire_chat_site: $7
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
ecrans_tour: $M/serveur/plugins/KrpTour/direct.json
ecrans_cube: $M/cube/plugins/KrpCube/direct.json
C
}

echo "== 3. La Tour → serveur interne"
T=$M/serveur
prop $T/server.properties server-ip 127.0.0.1
prop $T/server.properties server-port 25571
prop $T/server.properties online-mode false
prop $T/server.properties enforce-secure-profile false
prop $T/server.properties prevent-proxy-connections false
prop $T/server.properties view-distance 12
prop $T/server.properties network-compression-threshold -1
velocite $T
cp $S/KrpTour.jar $T/plugins/KrpTour.jar
grep -q "^envoi_site:" $T/plugins/KrpTour/config.yml || printf 'envoi_site: true\nreseau:\n  actif: true\n' >> $T/plugins/KrpTour/config.yml
reseau $T tour 25581 "La Tour" "#FFB347" false false
# mémoire : la Tour n'a pas besoin de 2,5 Go pré-réservés
sed -i 's/-Xms1536M -Xmx2560M/-Xms512M -Xmx1536M/; s/ -XX:+AlwaysPreTouch//' /etc/systemd/system/minecraft.service

echo "== 4. Le Cube → serveur interne, monde reconstruit à côté du hub"
C=$M/cube
prop $C/server.properties server-ip 127.0.0.1
prop $C/server.properties server-port 25572
prop $C/server.properties online-mode false
prop $C/server.properties enforce-secure-profile false
prop $C/server.properties prevent-proxy-connections false
prop $C/server.properties view-distance 12
prop $C/server.properties network-compression-threshold -1
prop $C/server.properties level-seed $SEED
velocite $C
NIV=$(sed -n 's/^level-name=//p' $C/server.properties); NIV=${NIV:-world}
if [ -d $C/$NIV ] && [ ! -f $C/$NIV/.reseau-lobbik ]; then mv $C/$NIV $C/$NIV.avant-reseau-$QUAND; fi
mkdir -p $C/$NIV/datapacks; rm -rf $C/$NIV/datapacks/krp-tour; cp -r $S/datapack/krp-tour $C/$NIV/datapacks/; touch $C/$NIV/.reseau-lobbik
rm -f $C/plugins/KrpCube/cube-*.ok $C/plugins/KrpCube/habillage-*.ok
cp $S/KrpCube.jar $C/plugins/KrpCube.jar
grep -q "^envoi_site:" $C/plugins/KrpCube/config.yml || printf '\nenvoi_site: true\nreseau:\n  actif: true\n  origine_x: -300\n  origine_z: -50\n  vestibule: "-196,-190,-2,2,64,68"\n  sortie_pont: "-186.5,64,0.5,-90"\n' >> $C/plugins/KrpCube/config.yml
reseau $C cube 25582 "Le Cube" "#5AB4FF" true true

echo "== 5. le hub « Lobbik »"
if [ ! -d $V/minecraft/hub ]; then mkdir -p $V/minecraft/hub; fi
[ -e $M/hub ] || ln -s $V/minecraft/hub $M/hub
H=$M/hub
cp $T/paper.jar $H/paper.jar
for x in libraries versions cache; do [ -d $H/$x ] || cp -r $T/$x $H/; done
echo "eula=true" > $H/eula.txt
[ -f $H/server.properties ] || cat > $H/server.properties <<P
server-ip=127.0.0.1
server-port=25570
online-mode=false
enforce-secure-profile=false
prevent-proxy-connections=false
view-distance=12
simulation-distance=4
max-players=1000
level-name=world
level-seed=$SEED
allow-nether=false
spawn-protection=0
gamemode=adventure
force-gamemode=true
difficulty=peaceful
network-compression-threshold=-1
sync-chunk-writes=false
enable-rcon=false
motd=Lobbik (hub)
P
[ -f $H/bukkit.yml ] || printf 'settings:\n  allow-end: false\n  connection-throttle: -1\nworlds:\n  world:\n    generator: KrpReseau\n' > $H/bukkit.yml
mkdir -p $H/config $H/world/datapacks
[ -f $H/config/paper-global.yml ] || cp $T/config/paper-global.yml $H/config/paper-global.yml
velocite $H
rm -rf $H/world/datapacks/krp-tour; cp -r $S/datapack/krp-tour $H/world/datapacks/
reseau $H hub 25580 "Lobbik" "#7CFF4F" true true
cp $T/server-icon.png $H/ 2>/dev/null || true

echo "== 6. le proxy"
if [ ! -d $V/minecraft/proxy ]; then mkdir -p $V/minecraft/proxy/plugins/lobbikreseau; fi
[ -e $M/proxy ] || ln -s $V/minecraft/proxy $M/proxy
P=$M/proxy
mkdir -p $P/plugins/lobbikreseau
cp $S/velocity.jar $S/velocity.toml $P/
printf %s "$FWD" > $P/forwarding.secret
cp $S/LobbikReseau.jar $P/plugins/
sed -e "s/^cle=.*/cle=$CLE/" $S/reseau.properties > $P/plugins/lobbikreseau/reseau.properties
cp $T/server-icon.png $P/server-icon.png 2>/dev/null || true
chown -R minecraft:minecraft $V/minecraft/hub $V/minecraft/proxy $T/ $C/ 2>/dev/null || true
chown -h minecraft:minecraft $M/hub $M/proxy

G="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"
cat > /etc/systemd/system/minecraft-hub.service <<U
[Unit]
Description=Minecraft — hub « Lobbik » du réseau (Paper, 27/09/2026)
After=network-online.target
Wants=network-online.target

[Service]
User=minecraft
Group=minecraft
WorkingDirectory=$H
ExecStart=$J -Xms512M -Xmx1280M $G -jar paper.jar --nogui
ExecStop=/bin/kill -SIGINT \$MAINPID
Restart=always
RestartSec=10
TimeoutStopSec=90
Nice=2

[Install]
WantedBy=multi-user.target
U
cat > /etc/systemd/system/minecraft-proxy.service <<U
[Unit]
Description=Minecraft — proxy du réseau Lobbik (Velocity-CTD+ modifié, 27/09/2026)
After=network-online.target minecraft-hub.service
Wants=network-online.target

[Service]
User=minecraft
Group=minecraft
WorkingDirectory=$P
ExecStart=$J -Xms128M -Xmx384M -XX:+UseG1GC -XX:G1HeapRegionSize=4M -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -jar velocity.jar
Restart=always
RestartSec=5
Nice=1

[Install]
WantedBy=multi-user.target
U
# cube.lobbik.com (enregistrement SRV → port 25566) arrive aussi au proxy
cat > /etc/systemd/system/minecraft-redirection.service <<U
[Unit]
Description=Minecraft — le port 25566 (ancien Cube) redirigé vers le proxy 25565
After=network-online.target ufw.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c 'iptables -t nat -C PREROUTING -p tcp --dport 25566 -j REDIRECT --to-ports 25565 2>/dev/null || iptables -t nat -A PREROUTING -p tcp --dport 25566 -j REDIRECT --to-ports 25565'
ExecStop=/bin/sh -c 'iptables -t nat -D PREROUTING -p tcp --dport 25566 -j REDIRECT --to-ports 25565 || true'

[Install]
WantedBy=multi-user.target
U
systemctl daemon-reload
systemctl enable minecraft-hub minecraft-proxy minecraft-redirection >/dev/null 2>&1

echo "== 7. démarrage : Cube, Tour, hub, puis proxy"
systemctl start minecraft-cube minecraft minecraft-hub
for s in cube serveur hub; do for i in $(seq 1 100); do sleep 3; grep -q "Done (" $M/$s/logs/latest.log 2>/dev/null && break; done; echo "-- $s :"; grep -i "Décor construit\|prêt\|Cube construit\|Habillage\|ERROR\|Exception" $M/$s/logs/latest.log | head -6 | cut -c1-200; done
systemctl start minecraft-proxy minecraft-redirection
ufw delete allow 25600/tcp >/dev/null 2>&1 || true
sleep 5
systemctl is-active minecraft-proxy minecraft-hub minecraft minecraft-cube minecraft-redirection
echo "Bascule terminée."
