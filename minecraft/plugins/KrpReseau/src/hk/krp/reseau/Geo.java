package hk.krp.reseau;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * La carte commune du réseau Lobbik (27/09/2026), identique sur les trois serveurs : chacun construit les zones des
 * autres en décor, pour qu'en passant d'un serveur à l'autre le client ne voie rien changer.
 *
 * <pre>
 *            nord (−z)
 *   Le Cube ◂── pont ──▸ hub « Lobbik » ◂── pont ──▸ La Tour
 *  x −300…−201          centre (−110, 0)            centre (0, 0)
 * </pre>
 * Au point d'apparition du hub, on regarde le nord : la Tour est à droite (est), le Cube à gauche (ouest).
 */
final class Geo {
    private Geo() { }

    /** Niveau des pieds partout (dessus des blocs en y = 63), comme l'esplanade de la Tour. */
    static final int Y = 64;

    static final int TOUR_X = 0, TOUR_Z = 0, TOUR_BASE = 28;

    static final int HUB_X = -110, HUB_Z = 0, HUB_R = 36;

    /** Le Cube : 10 × 10 × 10 salles de 10 blocs → x de −300 à −201, z de −50 à 49, y de 64 à 163. */
    static final int CUBE_X0 = -300, CUBE_Y0 = 64, CUBE_Z0 = -50, CUBE_COTE = 100;

    /** Ponts : 5 blocs de large sur l'axe z = 0 ; les portes sont à la sortie du hub. */
    static final int PONT_DEMI = 2;
    static final int PORTE_TOUR_X = -71, PORTE_CUBE_X = -149;
    static final int PONT_TOUR_FIN = -26;
    /** Vestibule du Cube : une pièce blanche posée devant sa face est, séparée par 3 blocs de vide. */
    static final int VESTIBULE_X1 = -197, VESTIBULE_X2 = -189, VESTIBULE_DEMI = 3;   // face est du Cube en x = −201, 3 blocs de vide entre les deux

    /** Porte de l'Atelier (serveur moddé, hors réseau) : au sud de l'arrivée, on la passe en allant vers le sud. */
    static final int ATELIER_Z = HUB_Z + 29;

    /** Hauteur des portes (murs colorés) : de y = 64 à 67. */
    static final int PORTE_BAS = 64, PORTE_HAUT = 67;

    static Location spawn(World w) { return new Location(w, HUB_X + 0.5, Y, HUB_Z + 18.5, 180f, 0f); }

    /** Où l'on revient en tombant du pont, côté de chaque serveur. */
    static Location bordTour(World w) { return new Location(w, PONT_TOUR_FIN - 3 + 0.5, Y, 0.5, -90f, 0f); }
    static Location bordCube(World w) { return new Location(w, PORTE_CUBE_X - 4 + 0.5, Y, 0.5, 90f, 0f); }

    static boolean surPont(double x, double z) { return Math.abs(z) <= PONT_DEMI + 0.7; }
    static double distHub(double x, double z) { return Math.hypot(x - HUB_X, z - HUB_Z); }
}
