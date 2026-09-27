#!/bin/bash
# Retour arrière de la bascule Fabric (27/09/2026) : les anciens serveurs Paper repartent tels quels (leurs dossiers n'ont pas bougé).
# bash retour-paper.sh /mnt/HC_Volume_106906374/sauvegardes/avant-fabric-AAAAMMJJ-HHMM
set -euo pipefail
B=${1:?dossier de sauvegarde}
[ -f $B/units/minecraft.service ] || { echo "sauvegarde incomplète : $B"; exit 1; }
systemctl stop minecraft-hub minecraft minecraft-cube minecraft-lave 2>/dev/null || true
systemctl disable minecraft-lave 2>/dev/null || true
cp -p $B/units/minecraft-hub.service $B/units/minecraft.service $B/units/minecraft-cube.service /etc/systemd/system/
cp -p $B/velocity.toml /srv/minecraft/proxy/velocity.toml; cp -p $B/reseau.properties /srv/minecraft/proxy/plugins/lobbikreseau/reseau.properties
cp -p $B/rcon-pont.py /root/rcon-pont.py; systemctl restart krp-pont
systemctl daemon-reload
systemctl start minecraft-hub minecraft minecraft-cube
sleep 40; systemctl restart minecraft-proxy
for u in minecraft-proxy minecraft-hub minecraft minecraft-cube; do echo "$u : $(systemctl is-active $u)"; done
echo "RETOUR À PAPER FAIT (les dossiers f-* restent en place)"
