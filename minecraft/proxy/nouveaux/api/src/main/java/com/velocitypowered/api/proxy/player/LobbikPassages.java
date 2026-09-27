/*
 * Lobbik (27/09/2026) — passages sans coupure entre serveurs qui partagent la même carte.
 */

package com.velocitypowered.api.proxy.player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marque un changement de serveur « par le pont » : le joueur marche d'un serveur à l'autre sur une
 * carte identique des deux côtés. Pour ce passage, le proxy garde le monde du client (voir
 * {@link ClientWorldSwitches}) et rend la première position envoyée par le serveur d'arrivée
 * relative et nulle, pour que le joueur ne soit ni déplacé ni tourné : il continue de marcher.
 *
 * <p>Un passage sans marque (commande, connexion directe) garde le comportement habituel.</p>
 */
public final class LobbikPassages {

  private static final long VALIDITE_MS = 15_000;

  private static final ConcurrentHashMap<UUID, Long> PONTS = new ConcurrentHashMap<>();

  private LobbikPassages() {
  }

  /**
   * Annonce que le prochain changement de serveur de ce joueur se fait par le pont.
   *
   * @param joueur le joueur
   */
  public static void marquerPont(UUID joueur) {
    PONTS.put(joueur, System.currentTimeMillis() + VALIDITE_MS);
  }

  /**
   * Consomme la marque posée par {@link #marquerPont(UUID)}.
   *
   * @param joueur le joueur
   * @return vrai si le passage en cours est un passage par le pont encore valable
   */
  public static boolean prendrePont(UUID joueur) {
    Long jusqua = PONTS.remove(joueur);
    return jusqua != null && jusqua >= System.currentTimeMillis();
  }

  /**
   * Retire la marque sans la consommer (passage annulé).
   *
   * @param joueur le joueur
   */
  public static void oublier(UUID joueur) {
    PONTS.remove(joueur);
  }
}
