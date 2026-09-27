#!/bin/bash
# Compile KrpReseau (réseau Minecraft Lobbik, 27/09/2026). Les générateurs de La Tour et du Cube sont copiés depuis
# leurs sources (même code = mêmes blocs) dans les paquets hk.krp.reseau.tour et hk.krp.reseau.cube.
set -e
cd "$(dirname "$0")"
J=/usr/lib/jvm/java-25-openjdk-arm64/bin
LIB=/root/lobbik-mc/lib
CP="$(find $LIB/libraries -name '*.jar' | tr '\n' ':')$LIB/paper-26.2.jar"
rm -rf gen out && mkdir -p gen/hk/krp/reseau/tour gen/hk/krp/reseau/cube out
sed 's/^package hk.krp.tour;/package hk.krp.reseau.tour;/' /srv/aoe/mc-plugin/KrpTour/src/hk/krp/tour/Carte.java > gen/hk/krp/reseau/tour/Carte.java
for f in Labyrinthe Plan; do sed 's/^package hk.krp.cube;/package hk.krp.reseau.cube;/' /srv/aoe/mc-plugin/KrpCube/src/hk/krp/cube/$f.java > gen/hk/krp/reseau/cube/$f.java; done
$J/javac --release 25 -encoding UTF-8 -nowarn -cp "$CP" -d out $(find src gen -name '*.java') 2>&1 | grep -v "^Note:" || true
test -f out/hk/krp/reseau/KrpReseau.class
cp res/*.yml out/
$J/jar cf KrpReseau.jar -C out .
ls -la KrpReseau.jar
