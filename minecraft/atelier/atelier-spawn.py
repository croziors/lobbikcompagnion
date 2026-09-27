#!/usr/bin/env python3
"""Au premier démarrage d'un monde de l'Atelier : règles, point d'arrivée, bouton « Retour à Lobbik », mur d'écrans CC: Tweaked
qui affiche en direct les scores de La Tour et du Cube (programme ecran.lua dans l'ordinateur 0)."""
import os, shutil, subprocess, sys, time
sys.path.insert(0, '/root/bin'); import rconlib
A = '/srv/minecraft/atelier'; MARQUE = A + '/world/.lobbik-spawn-1'
mdp = open(A + '/.rcon').read().strip()
for _ in range(120):
    try: rconlib.commande('list', mdp); break
    except Exception: time.sleep(3)
else: sys.exit('RCON injoignable')
def c(x):
    r = rconlib.commande(x, mdp)
    if r and ('Unknown' in r or 'Incorrect' in r or 'Expected' in r or 'Invalid' in r): print('!!', x, '→', r)
for g in ('advance_time false', 'advance_weather false', 'spawn_mobs false', 'spawn_monsters false', 'show_advancement_messages false'): c('gamerule ' + g)
c('time set 6000'); c('weather clear')
if os.path.exists(MARQUE): sys.exit(0)
Y = -61   # dessus du monde plat
c(f'setworldspawn 0 {Y + 1} 0')
c(f'fill -12 {Y} -16 12 {Y} 12 minecraft:polished_andesite')
c(f'fill -12 {Y} 0 12 {Y} 0 minecraft:lime_concrete'); c(f'fill 0 {Y} -16 0 {Y} 12 minecraft:lime_concrete')
# retour à Lobbik : bouton sur un bloc de commande
c(f'setblock 0 {Y + 1} 6 minecraft:command_block{{Command:"transfer minecraft.lobbik.com 25565 @p"}}')
c(f'setblock 0 {Y + 1} 5 minecraft:polished_blackstone_button[face=wall,facing=north]')
c(f'setblock 0 {Y + 2} 6 minecraft:sea_lantern')
c(f'setblock 0 {Y + 2} 5 minecraft:oak_wall_sign[facing=north]{{front_text:{{messages:["","Retour à","Lobbik",""]}}}}')
# mur d'écrans CC: Tweaked (8 × 5) face au sud, l'ordinateur derrière
c(f'fill -4 {Y + 1} -12 3 {Y + 5} -12 computercraft:monitor_advanced[facing=south]')
c(f'setblock 0 {Y + 1} -13 computercraft:computer_advanced[facing=south]{{ComputerId:0,On:1b}}')
d = A + '/world/computercraft/computer/0'; os.makedirs(d, exist_ok=True)
shutil.copy(A + '/ecran.lua', d + '/startup.lua')
subprocess.run(['chown', '-R', 'minecraft:minecraft', A + '/world/computercraft'])
c('computercraft turn-on #0')
open(MARQUE, 'w').write('1'); subprocess.run(['chown', 'minecraft:minecraft', MARQUE])
print('Atelier : arrivée et écrans posés')
