#!/bin/bash
# Oasis (ex-« L'Atelier », renommé le 27/09/2026 à la demande de Kripy) : 4e serveur Minecraft de Lobbik, MODDÉ (Fabric 26.2 + CC: Tweaked + Macaw's), créatif.
# Joignable en direct sur minecraft.lobbik.com:25567 (hors proxy : ses registres diffèrent des serveurs vanilla), depuis le hub
# par une porte (transfert). Démarré À LA DEMANDE (sudo systemctl start, permis au seul compte minecraft), arrêté après 10 min
# sans joueur. Liste blanche = comptes Minecraft liés à Lobbik (mc_permis), relue toutes les 2 min.
set -e
S=/root/atelier-stage; V=/mnt/HC_Volume_106906374/minecraft/atelier; M=/srv/minecraft
J=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
mkdir -p $V/mods; [ -e $M/atelier ] || ln -s $V $M/atelier
cd $V
cp $S/fabric-server.jar $V/fabric-server.jar
# mods : téléchargés depuis Modrinth, empreinte SHA-512 vérifiée ; les anciens retirés
python3 - "$S/atelier-mods.json" "$V/mods" <<'PY'
import json,sys,os,hashlib,urllib.request
d=json.load(open(sys.argv[1])); dos=sys.argv[2]; garder=set()
for m in [m for m in d['mods'] if m.get('cote')!='client']:   # mods « client » (animations) : jamais sur le serveur
    f=os.path.join(dos,m['fichier']); garder.add(m['fichier'])
    if os.path.exists(f) and hashlib.sha512(open(f,'rb').read()).hexdigest()==m['sha512']: continue
    data=urllib.request.urlopen(urllib.request.Request(m['url'],headers={'User-Agent':'lobbik-atelier/1.0 (lobbik.com)'})).read()
    if hashlib.sha512(data).hexdigest()!=m['sha512']: raise SystemExit('empreinte différente : '+m['fichier'])
    open(f,'wb').write(data); print('mod', m['fichier'])
for f in os.listdir(dos):
    if f.endswith('.jar') and f not in garder: os.remove(os.path.join(dos,f)); print('retiré', f)
PY
echo "eula=true" > eula.txt
[ -f .rcon ] || openssl rand -hex 16 > .rcon; RCON=$(cat .rcon)
cat > server.properties <<P
server-port=25567
online-mode=true
enforce-secure-profile=true
accepts-transfers=true
white-list=true
enforce-whitelist=true
enable-command-block=true
gamemode=creative
force-gamemode=true
difficulty=peaceful
spawn-protection=12
max-players=20
view-distance=8
simulation-distance=6
level-name=world
level-type=minecraft\:flat
allow-nether=false
enable-rcon=true
rcon.port=25577
rcon.password=$RCON
motd=\u00a7aOasis \u00a77— le serveur moddé de Lobbik : CC\: Tweaked, Macaw's \u00a78· client moddé via le Compagnon
P
[ -f ops.json ] || echo '[{"uuid":"caa13678-f5cd-4801-bbaf-f4fe4bc5924f","name":"croziors","level":4,"bypassesPlayerLimit":true}]' > ops.json
mkdir -p /root/bin
cp $S/rconlib.py $S/atelier-liste-blanche.py $S/atelier-veille.py $S/atelier-spawn.py /root/bin/
cp $S/ecran.lua $V/ecran.lua
chown -R minecraft:minecraft $V; chown -h minecraft:minecraft $M/atelier; chmod 600 $V/.rcon
cat > /etc/systemd/system/minecraft-atelier.service <<U
[Unit]
Description=Minecraft — Oasis, le serveur moddé de Lobbik (Fabric 26.2 + CC: Tweaked, à la demande)
After=network-online.target

[Service]
User=minecraft
Group=minecraft
WorkingDirectory=$V
ExecStartPre=/usr/bin/python3 /root/bin/atelier-liste-blanche.py --sans-rcon
ExecStart=$J -Xms256M -Xmx1024M -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -jar fabric-server.jar nogui
ExecStartPost=/bin/sh -c 'sleep 1; /usr/bin/python3 /root/bin/atelier-spawn.py &'
ExecStop=/bin/kill -SIGINT \$MAINPID
TimeoutStopSec=90
SuccessExitStatus=0 1 130 143
Nice=3
U
# ExecStartPre/Post tournent en root (« + » serait plus propre, mais le script lit la clé du site réservée à root)
sed -i 's|^ExecStartPre=|ExecStartPre=+|; s|^ExecStartPost=|ExecStartPost=+|' /etc/systemd/system/minecraft-atelier.service
cat > /etc/systemd/system/minecraft-atelier-veille.service <<U
[Unit]
Description=L'Atelier de Lobbik : liste blanche à jour, arrêt après 10 min sans joueur
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /root/bin/atelier-veille.py
U
cat > /etc/systemd/system/minecraft-atelier-veille.timer <<U
[Unit]
Description=L'Atelier de Lobbik : veille toutes les 2 min
[Timer]
OnBootSec=2min
OnUnitActiveSec=2min
[Install]
WantedBy=timers.target
U
# le hub (compte minecraft) peut démarrer l'Atelier, et rien d'autre
cat > /etc/sudoers.d/minecraft-atelier <<U
minecraft ALL=(root) NOPASSWD: /usr/bin/systemctl start minecraft-atelier.service, /usr/bin/systemctl is-active minecraft-atelier.service
U
chmod 440 /etc/sudoers.d/minecraft-atelier; visudo -c -f /etc/sudoers.d/minecraft-atelier
systemctl daemon-reload
systemctl enable --now minecraft-atelier-veille.timer
ufw allow 25567/tcp comment "Minecraft Atelier modde (a la demande)" >/dev/null
echo "Atelier installé (arrêté ; il démarre à la demande)."
