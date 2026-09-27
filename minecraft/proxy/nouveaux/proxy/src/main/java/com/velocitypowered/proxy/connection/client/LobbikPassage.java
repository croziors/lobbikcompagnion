/*
 * Lobbik (27/09/2026) — passages sans coupure entre serveurs qui partagent la même carte.
 */

package com.velocitypowered.proxy.connection.client;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Ce que le client garde d'un serveur quand on ne lui renvoie pas de « join game ».
 *
 * <p>Quand le proxy garde le monde du client pendant un changement de serveur, rien ne dit au
 * client d'oublier ce que l'ancien serveur lui avait montré : ses entités (joueurs, affichages),
 * ses objectifs et équipes de tableau, les effets posés sur le joueur. On suit donc ces paquets au
 * passage (sans les décoder entièrement : leurs premiers champs suffisent) et on les efface au
 * moment du changement. On retient aussi le prochain « start waiting for level chunks » (il
 * afficherait l'écran de chargement) et on rend relative la première position envoyée par le
 * serveur d'arrivée lors d'un passage par le pont.</p>
 *
 * <p>Les identifiants de paquets sont ceux du protocole 26.1 / 26.2 (775 / 776), relevés dans le
 * rapport {@code packets.json} du serveur vanilla 26.2. Pour toute autre version, rien n'est suivi
 * et le monde n'est jamais gardé.</p>
 */
public final class LobbikPassage {

  private static final Logger LOGGER = LogManager.getLogger(LobbikPassage.class);

  static final int ADD_ENTITY = 0x01;
  static final int GAME_EVENT = 0x26;
  static final int PLAYER_POSITION = 0x48;
  static final int REMOVE_ENTITIES = 0x4d;
  static final int REMOVE_MOB_EFFECT = 0x4e;
  static final int SET_OBJECTIVE = 0x6a;
  static final int SET_PLAYER_TEAM = 0x6d;
  static final int UPDATE_MOB_EFFECT = 0x84;

  private static final int EVENEMENT_MODE_DE_JEU = 3;
  private static final int EVENEMENT_ATTENTE_DES_TRONCONS = 13;

  /** X, Y, Z, Y_ROT, X_ROT, DELTA_X, DELTA_Y, DELTA_Z, ROTATE_DELTA : tout relatif. */
  private static final int TOUT_RELATIF = 0x1FF;

  private static final long ARMEMENT_MS = 10_000;

  private final Set<Integer> entites = new HashSet<>();
  private final Set<String> objectifs = new LinkedHashSet<>();
  private final Set<String> equipes = new LinkedHashSet<>();
  private final Set<Integer> effets = new HashSet<>();
  private int moi = -1;
  private final String nom;
  private long traceJusqua;
  private final StringBuilder trace = new StringBuilder();

  LobbikPassage(String nom) {
    this.nom = nom;
  }

  /** Journal de débogage (20 s) : ce que le serveur envoie après un passage ou une réapparition. */
  public void tracer(String quoi) {
    vider();
    traceJusqua = System.currentTimeMillis() + 20_000;
    LOGGER.info("[lobbik] {} : {}", nom, quoi);
  }

  private void noter(String s) {
    if (System.currentTimeMillis() > traceJusqua) {
      vider();
      return;
    }
    trace.append(s).append(' ');
    if (trace.length() > 900) {
      vider();
    }
  }

  private void vider() {
    if (trace.length() > 0) {
      LOGGER.info("[lobbik] {} ← {}", nom, trace.toString().trim());
      trace.setLength(0);
    }
  }

  /** Côté client → serveur : les accusés de terrain, la réapparition, « chargé ». */
  public void versServeur(ByteBuf buf, boolean transmis) {
    if (System.currentTimeMillis() > traceJusqua) {
      return;
    }
    int debut = buf.readerIndex();
    try {
      int id = ProtocolUtils.readVarInt(buf);
      if (id == 0x0b || id == 0x0c || id == 0x2c) {
        noter("→" + (id == 0x0b ? "ackTerrain" : id == 0x0c ? "commande" : "charge") + (transmis ? "" : "(PERDU)"));
      }
    } catch (RuntimeException ignored) {
      // rien
    } finally {
      buf.readerIndex(debut);
    }
  }
  private boolean attenteARetenir;
  private boolean positionARelativiser;
  private long armeLe;

  static boolean prisEnCharge(ProtocolVersion version) {
    return version == ProtocolVersion.MINECRAFT_26_1 || version == ProtocolVersion.MINECRAFT_26_2;
  }

  void moi(int entityId) {
    this.moi = entityId;
  }

  /**
   * Regarde un paquet que le serveur envoie au client.
   *
   * @param buf le paquet brut, identifiant compris ; son index de lecture est rendu intact
   * @return le paquet à transmettre (le même ou un nouveau), ou null pour le retenir
   */
  public ByteBuf versClient(ByteBuf buf) {
    final int debut = buf.readerIndex();
    try {
      final int id = ProtocolUtils.readVarInt(buf);
      if (System.currentTimeMillis() <= traceJusqua) {
        switch (id) {
          case 0x2d -> noter("T");
          case 0x0c -> noter("[lot");
          case 0x0b -> noter("lot]");
          case 0x25 -> noter("oubli");
          case 0x5e -> noter("centre");
          case GAME_EVENT -> noter("ev" + buf.getUnsignedByte(buf.readerIndex()));
          case PLAYER_POSITION -> noter("pos");
          default -> { }
        }
      }
      switch (id) {
        case ADD_ENTITY -> entites.add(ProtocolUtils.readVarInt(buf));
        case REMOVE_ENTITIES -> {
          int n = ProtocolUtils.readVarInt(buf);
          for (int i = 0; i < n; i++) {
            entites.remove(ProtocolUtils.readVarInt(buf));
          }
        }
        case SET_OBJECTIVE -> {
          String nom = ProtocolUtils.readString(buf);
          byte methode = buf.readByte();
          if (methode == 0) {
            objectifs.add(nom);
          } else if (methode == 1) {
            objectifs.remove(nom);
          }
        }
        case SET_PLAYER_TEAM -> {
          String nom = ProtocolUtils.readString(buf);
          byte methode = buf.readByte();
          if (methode == 0) {
            equipes.add(nom);
          } else if (methode == 1) {
            equipes.remove(nom);
          }
        }
        case UPDATE_MOB_EFFECT -> {
          int entite = ProtocolUtils.readVarInt(buf);
          int effet = ProtocolUtils.readVarInt(buf);
          if (entite == moi) {
            effets.add(effet);
          }
        }
        case REMOVE_MOB_EFFECT -> {
          int entite = ProtocolUtils.readVarInt(buf);
          int effet = ProtocolUtils.readVarInt(buf);
          if (entite == moi) {
            effets.remove(effet);
          }
        }
        case GAME_EVENT -> {
          int type = buf.readUnsignedByte();
          // tous les « attends le terrain » des 5 premières secondes : il en vient parfois deux (arrivée, puis replacement)
          if (type == EVENEMENT_ATTENTE_DES_TRONCONS && attenteARetenir && System.currentTimeMillis() - armeLe < 5_000) {
            return null;
          }
        }
        case PLAYER_POSITION -> {
          if (positionARelativiser && arme()) {
            positionARelativiser = false;
            int teleportation = ProtocolUtils.readVarInt(buf);
            ByteBuf nul = Unpooled.buffer(64);
            ProtocolUtils.writeVarInt(nul, PLAYER_POSITION);
            ProtocolUtils.writeVarInt(nul, teleportation);
            for (int i = 0; i < 6; i++) {
              nul.writeDouble(0);   // position puis élan : rien ne change
            }
            nul.writeFloat(0);      // lacet
            nul.writeFloat(0);      // tangage
            nul.writeInt(TOUT_RELATIF);
            return nul;
          }
        }
        default -> {
        }
      }
    } catch (RuntimeException e) {
      LOGGER.debug("Paquet illisible pour le suivi Lobbik", e);
    } finally {
      buf.readerIndex(debut);
    }
    return buf;
  }

  private boolean arme() {
    return System.currentTimeMillis() - armeLe < ARMEMENT_MS;
  }

  /**
   * Efface chez le client ce que l'ancien serveur y avait mis.
   *
   * @param client        la connexion du client
   * @param entitesAussi  faux quand un « join game » part quand même au client (il vide alors son
   *                      monde, entités comprises)
   * @param modeDeJeu     le mode de jeu annoncé par le serveur d'arrivée, ou -1
   * @param pont          vrai pour un passage par le pont (position rendue relative)
   */
  void changement(MinecraftConnection client, boolean entitesAussi, int modeDeJeu, boolean pont) {
    if (entitesAussi) {
      entites.remove(moi);
      if (!entites.isEmpty()) {
        ByteBuf b = Unpooled.buffer();
        ProtocolUtils.writeVarInt(b, REMOVE_ENTITIES);
        ProtocolUtils.writeVarInt(b, entites.size());
        for (int e : entites) {
          ProtocolUtils.writeVarInt(b, e);
        }
        client.delayedWrite(b);
      }
    }
    for (String nom : objectifs) {
      ByteBuf b = Unpooled.buffer();
      ProtocolUtils.writeVarInt(b, SET_OBJECTIVE);
      ProtocolUtils.writeString(b, nom);
      b.writeByte(1);
      client.delayedWrite(b);
    }
    for (String nom : equipes) {
      ByteBuf b = Unpooled.buffer();
      ProtocolUtils.writeVarInt(b, SET_PLAYER_TEAM);
      ProtocolUtils.writeString(b, nom);
      b.writeByte(1);
      client.delayedWrite(b);
    }
    if (moi >= 0) {
      for (int effet : effets) {
        ByteBuf b = Unpooled.buffer();
        ProtocolUtils.writeVarInt(b, REMOVE_MOB_EFFECT);
        ProtocolUtils.writeVarInt(b, moi);
        ProtocolUtils.writeVarInt(b, effet);
        client.delayedWrite(b);
      }
    }
    if (entitesAussi && modeDeJeu >= 0) {
      ByteBuf b = Unpooled.buffer();
      ProtocolUtils.writeVarInt(b, GAME_EVENT);
      b.writeByte(EVENEMENT_MODE_DE_JEU);
      b.writeFloat(modeDeJeu);
      client.delayedWrite(b);
    }
    entites.clear();
    objectifs.clear();
    equipes.clear();
    effets.clear();
    attenteARetenir = entitesAussi;
    positionARelativiser = entitesAussi && pont;
    armeLe = System.currentTimeMillis();
    tracer("changement de serveur (monde gardé=" + entitesAussi + ", pont=" + pont + ")");
  }

  /** Après un vrai « join game » ou une reconfiguration : le client est reparti de zéro. */
  void oublier() {
    entites.clear();
    objectifs.clear();
    equipes.clear();
    effets.clear();
    attenteARetenir = false;
    positionARelativiser = false;
  }
}
