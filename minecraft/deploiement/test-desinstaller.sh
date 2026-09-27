#!/bin/bash
# Retire entièrement le réseau de TEST Lobbik (services, dossier, utilisateur, port 25600). La Tour et Le Cube ne sont pas concernés.
systemctl disable --now mctest-proxy mctest-hub mctest-tour mctest-cube 2>/dev/null
rm -f /etc/systemd/system/mctest-*.service; systemctl daemon-reload
ufw delete allow 25600/tcp >/dev/null 2>&1
rm -rf /srv/mctest; userdel mctest 2>/dev/null
echo "Réseau de test retiré."
