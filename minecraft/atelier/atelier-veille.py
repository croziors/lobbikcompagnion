#!/usr/bin/env python3
"""Toutes les 2 min : liste blanche à jour ; si l'Atelier tourne depuis 10 min et qu'il est vide depuis 10 min, on l'arrête."""
import os, re, subprocess, sys, time
sys.path.insert(0, '/root/bin'); import rconlib
A = '/srv/minecraft/atelier'; ETAT = '/var/lib/lobbik-atelier'; os.makedirs(ETAT, exist_ok=True)
subprocess.run(['/usr/bin/python3', '/root/bin/atelier-liste-blanche.py'])
if subprocess.run(['systemctl', 'is-active', '--quiet', 'minecraft-atelier']).returncode != 0:
    try: os.remove(ETAT + '/vide_depuis')
    except FileNotFoundError: pass
    sys.exit(0)
depuis = subprocess.run(['systemctl', 'show', '-p', 'ActiveEnterTimestampMonotonic', '--value', 'minecraft-atelier'], capture_output=True, text=True).stdout.strip()
actif_s = (time.monotonic() * 1e6 - int(depuis or 0)) / 1e6
try: n = int(re.search(r'(\d+)', rconlib.commande('list', open(A + '/.rcon').read().strip())).group(1))
except Exception: sys.exit(0)   # pas encore prêt
f = ETAT + '/vide_depuis'
if n > 0:
    if os.path.exists(f): os.remove(f)
    sys.exit(0)
if not os.path.exists(f): open(f, 'w').write(str(time.time())); sys.exit(0)
if time.time() - float(open(f).read()) >= 600 and actif_s >= 600:
    os.remove(f); subprocess.run(['systemctl', 'stop', 'minecraft-atelier'])
