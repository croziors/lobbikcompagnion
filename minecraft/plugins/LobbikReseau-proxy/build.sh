#!/bin/bash
# Compile le plugin du proxy Lobbik contre le proxy Velocity-CTD+ modifié (27/09/2026)
set -e
cd "$(dirname "$0")"
J=/usr/lib/jvm/java-25-openjdk-arm64/bin
CP=/root/lobbik-mc/ctdplus/proxy/build/libs/velocity-proxy-4.2.1-SNAPSHOT-all.jar
rm -rf out && mkdir out
$J/javac --release 21 -encoding UTF-8 -cp "$CP" -d out $(find src -name '*.java')
cp res/* out/
$J/jar cf LobbikReseau.jar -C out .
ls -la LobbikReseau.jar
