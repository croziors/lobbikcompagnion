"""RCON minimal (localhost) pour les scripts de l'Atelier de Lobbik."""
import socket, struct
def commande(cmd, mdp, port=25577, hote='127.0.0.1', delai=5):
    s = socket.create_connection((hote, port), delai)
    def env(i, t, p): d = struct.pack('<ii', i, t) + p.encode() + b'\0\0'; s.sendall(struct.pack('<i', len(d)) + d)
    def rec():
        n = struct.unpack('<i', s.recv(4))[0]; d = b''
        while len(d) < n: d += s.recv(n - len(d))
        return struct.unpack('<ii', d[:8])[0], d[8:-2].decode('utf-8', 'replace')
    env(1, 3, mdp)
    if rec()[0] == -1: raise PermissionError('mot de passe RCON refusé')
    env(2, 2, cmd); r = rec()[1]; s.close(); return r
