package hk.krp.reseau;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Les autres serveurs, vus de loin : La Tour et Le Cube reconstruits à l'identique avec leur propre code de génération
 * (copié de KrpTour et KrpCube à la compilation, voir build.sh). Décor seulement : rien n'y bouge.
 */
final class Repliques {
    private Repliques() { }

    static void tour(World w, long graine) {
        new hk.krp.reseau.tour.Carte(graine).construire(w);
    }

    static void cube(World w, FileConfiguration c) {
        hk.krp.reseau.cube.Labyrinthe.X0 = Geo.CUBE_X0; hk.krp.reseau.cube.Labyrinthe.Z0 = Geo.CUBE_Z0; hk.krp.reseau.cube.Labyrinthe.HABILLAGE = true;
        new hk.krp.reseau.cube.Labyrinthe(c.getLong("graine_cube", 20260923L), c.getInt("cube_largeur", 10), c.getInt("cube_niveaux", 10),
            c.getInt("cube_profondeur", 10), c.getInt("cube_pieges", 20)).construire(w);
    }
}
