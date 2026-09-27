#!/bin/bash
# Retour à l'avant-réseau : La Tour sur 25565 et Le Cube sur 25566 en accès direct, comme avant le 27/09/2026.
# Usage : retour-arriere.sh /mnt/HC_Volume_106906374/sauvegardes/avant-bascule-reseau-AAAAMMJJ-HHMM
set -e
SAUVE=${1:?dossier de sauvegarde}
M=/srv/minecraft
systemctl stop minecraft-proxy minecraft-hub minecraft minecraft-cube minecraft-redirection || true
systemctl disable minecraft-proxy minecraft-hub minecraft-redirection || true
R=/mnt/HC_Volume_106906374/minecraft   # /srv/minecraft/serveur et /srv/minecraft/cube sont des liens vers ces dossiers
for d in serveur cube; do
  rm -rf $R/$d.apres-reseau; mv $R/$d $R/$d.apres-reseau
  tar -xzf $SAUVE/$d.tgz -C $R
  for x in libraries versions cache; do [ -d $R/$d/$x ] || mv $R/$d.apres-reseau/$x $R/$d/; done
  chown -R minecraft:minecraft $R/$d
done
cp $SAUVE/minecraft.service $SAUVE/minecraft-cube.service /etc/systemd/system/
systemctl daemon-reload
systemctl start minecraft minecraft-cube
systemctl is-active minecraft minecraft-cube
echo "Retour arrière fait (les dossiers du réseau sont dans $R/*.apres-reseau)."
