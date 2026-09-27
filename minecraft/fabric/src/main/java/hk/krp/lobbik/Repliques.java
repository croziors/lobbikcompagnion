package hk.krp.lobbik;

import hk.krp.lobbik.cube.Labyrinthe;
import hk.krp.lobbik.lave.Lave;
import hk.krp.lobbik.lave.Parcours;
import hk.krp.lobbik.tour.Carte;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;

/**
 * Les autres jeux, vus de loin (27/09/2026, port Fabric des répliques du plugin Paper) : chaque serveur du réseau reconstruit à
 * l'identique, avec leur propre code de génération, La Tour, Le Cube et la Mer de lave qu'il n'héberge pas — on les voit depuis
 * le hub et d'un jeu à l'autre. Décor seulement : rien n'y bouge. Construit par étapes (25 ms par tick), une fois par version.
 */
public final class Repliques {
    private static final String VERSION = "1";
    private final Deque<Runnable> chantier = new ArrayDeque<>();

    public void planifier(ServerLevel w, Config cfg, String role) {
        Path dos = FabricLoader.getInstance().getConfigDir();
        if (!"tour".equals(role)) etape(dos.resolve("lobbik-replique-tour-" + VERSION + ".ok"), "La Tour", () -> new Carte(cfg.l("tour.graine", 20260919L)).construire(w));
        if (!"cube".equals(role)) {
            Path m = dos.resolve("lobbik-replique-cube-" + VERSION + ".ok");
            if (!Files.exists(m)) {
                Labyrinthe.X0 = cfg.i("cube.origine_x", Geo.CUBE_X0); Labyrinthe.Z0 = cfg.i("cube.origine_z", Geo.CUBE_Z0); Labyrinthe.HABILLAGE = true;
                Labyrinthe lab = new Labyrinthe(cfg.l("cube.graine", 20260923L), Math.max(2, cfg.i("cube.largeur", 10)), Math.max(2, cfg.i("cube.niveaux", 10)),
                                                Math.max(1, cfg.i("cube.profondeur", 10)), cfg.i("cube.pieges", 20));
                Lobbik.LOG.info("Copie du Cube (vue de loin) : construction par étapes…");
                chantier.addAll(lab.etapes(w, true, true));
                chantier.add(() -> marquer(m, "Le Cube"));
            }
        }
        if (!"lave".equals(role)) {
            Path m = dos.resolve("lobbik-replique-lave-" + VERSION + ".ok");
            if (!Files.exists(m)) {
                Lobbik.LOG.info("Copie de la Mer de lave (vue de loin) : construction par étapes…");
                chantier.addAll(Lave.etapes(w, new Parcours(cfg.l("lave.graine", 20260927L), cfg.i("lave.longueur", 800))));
                chantier.add(() -> marquer(m, "la Mer de lave"));
            }
        }
    }
    private void etape(Path marque, String nom, Runnable r) {
        if (Files.exists(marque)) return;
        chantier.add(() -> { Lobbik.LOG.info("Copie de {} (vue de loin)…", nom); r.run(); marquer(marque, nom); });
    }
    private static void marquer(Path m, String nom) { try { Files.writeString(m, "1"); } catch (Exception ignored) { } Lobbik.LOG.info("Copie de {} terminée.", nom); }

    public void tic() {
        if (chantier.isEmpty()) return;
        long t0 = System.nanoTime();
        while (!chantier.isEmpty() && System.nanoTime() - t0 < 25_000_000L) chantier.poll().run();
    }
}
