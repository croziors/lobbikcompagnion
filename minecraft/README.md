# Réseau Minecraft Lobbik

Le code du réseau Minecraft de [Lobbik](https://lobbik.com) : un **hub** (« Lobbik ») relié par des ponts à **La Tour** (parkour de 1 000 blocs) et au **Cube** (1 000 salles, certaines tuent). On passe d'un serveur à l'autre **à pied, sans écran de chargement**.

Minecraft Java **26.2**, serveurs **Paper** (build 124), proxy **Velocity-CTD+** modifié. Aucun mod côté joueur.

## Comment marche le passage sans coupure

1. Le joueur franchit la porte du pont : le plugin du serveur de départ demande au proxy de le faire passer.
2. Le proxy dépose un **billet** chez le serveur d'arrivée (HTTP local, clé partagée) : le **numéro d'entité** que le client connaît déjà et la position exacte.
3. Le serveur d'arrivée donne ce numéro au joueur (`Entity#setId` avant son entrée dans le monde) : le proxy peut garder le monde du client (pas de *join game*, pas de reconfiguration).
4. Le proxy efface chez le client ce que l'ancien serveur y avait laissé (entités, objectifs, équipes, effets), retient l'écran « chargement du terrain », rend la première position relative et nulle : le joueur continue de marcher.

Conditions : mêmes registres partout (même Paper, même pack de données `krp-tour` — monde haut de 1 216 blocs —, même graine de monde, même heure).

## Contenu

| Dossier | Quoi |
|---|---|
| `proxy/velocity-ctd-plus-lobbik.patch` + `proxy/nouveaux/` | Modifications de [Velocity-CTD+](https://github.com/Faboit1/Velocity-CTD-plus) (base `5f8208c`), GPL-3.0 : `LobbikPassage` (nettoyage au passage, suivi), `LobbikPassages` (marque « pont »), réponse aux *keepalive* de configuration |
| `plugins/LobbikReseau-proxy` | Plugin du proxy : passages, files d'attente (places = `max-players` de chaque serveur), chat commun, liste des joueurs |
| `plugins/KrpReseau` | Plugin des serveurs (rôle hub / tour / cube) : billets, portes colorées par joueur, allées de file d'attente, décor commun, écrans en direct (cartes), panneaux des serveurs |
| `plugins/KrpTour`, `plugins/KrpCube` | Les deux jeux |
| `deploiement/` | Installation du réseau de test, bascule en production et retour arrière |
| `datapack/krp-tour` | Monde surélevé (hauteur 1 216), ciel de nuit bleu profond, étoiles vives, mer de nuages |
| `atelier/` | **L'Atelier** : serveur moddé (Fabric 26.2 + CC: Tweaked + Macaw's), démarré à la demande, liste des mods figée (Modrinth, SHA-512), écran Lua des scores en direct |

Les clés (secret de transfert du proxy, clé du réseau, clé du site) sont générées sur le serveur et ne sont jamais dans ce dépôt.

Le pack de textures proposé aux joueurs est **Faithful 32x** (https://faithfulpack.net, licence Faithful), non modifié, en téléchargement dans les Releases (`textures-faithful32x-26.2`).

## Réseau moddé (Fabric), en production depuis le 27/09/2026

Tout le réseau Lobbik tourne désormais en **Fabric** (Minecraft 26.2) avec un seul mod serveur maison, `fabric/` (paquet `hk.krp.lobbik`) :

- **Rôle** d'un serveur dans `config/lobbik.properties` : `hub`, `tour`, `cube` ou `lave`.
- **Passage sans écran de chargement** : billets déposés par le proxy + mixin `PlayerList.placeNewPlayer` qui reprend le numéro d'entité du client.
- **Hub** : portes par joueur, file d'attente, décor Volt, écrans CC: Tweaked en direct (`Ordinateurs.java`, `resources/lobbik/ecran-hub.lua`), copies des jeux vues de loin (`Repliques.java`).
- **La Tour** (`tour/`), **Le Cube** (`cube/`) et **la Mer de lave** (`lave/` : 800 blocs de sauts au-dessus d'une mer de lave, 4 réapparitions).
- **Tags de clan et badges de réussite** dans le chat.

Mods côté serveur et client : `atelier/atelier-mods.json`. Le Compagnon Lobbik installe le client moddé et resynchronise les mods tout seul.

Compilation : `cd fabric && ./gradlew build` (JDK 25).

Déploiement : `deploiement-fabric/bascule-fabric.sh` (production, sauvegarde + retour arrière `retour-paper.sh`) et `test-installer.sh` (serveurs de test). Aucune clé n'est dans ce dépôt : elles sont lues dans des fichiers du serveur.
