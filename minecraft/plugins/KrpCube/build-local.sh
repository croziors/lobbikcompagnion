#!/bin/bash
# Compile KrpCube ici (VM Lobbik) contre Paper 26.2 build 124 copié dans /root/lobbik-mc/lib (27/09/2026)
set -e
cd "$(dirname "$0")"
J=/usr/lib/jvm/java-25-openjdk-arm64/bin
LIB=/root/lobbik-mc/lib
CP="$(find $LIB/libraries -name '*.jar' | tr '\n' ':')$LIB/paper-26.2.jar"
rm -rf out && mkdir out
$J/javac --release 25 -encoding UTF-8 -nowarn -cp "$CP" -d out $(find src -name '*.java') 2>&1 | grep -v "^Note:\|warning:\|^\s*\^\|^        " || true
test -f out/hk/krp/*/KrpCube.class
cp res/*.yml out/
$J/jar cf KrpCube.jar -C out .
ls -la KrpCube.jar
