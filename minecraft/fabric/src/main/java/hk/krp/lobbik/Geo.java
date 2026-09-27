package hk.krp.lobbik;

/**
 * La carte commune du réseau Lobbik (identique sur les trois serveurs) — voir la version Paper (KrpReseau) du 27/09/2026.
 * Au point d'arrivée du hub, on regarde le nord : la Tour est à droite (est), le Cube à gauche (ouest).
 */
public final class Geo {
    private Geo() { }
    public record Lieu(double x, double y, double z, float yaw, float pitch) { }

    public static final int Y = 64;
    public static final int TOUR_X = 0, TOUR_Z = 0, TOUR_BASE = 28;
    public static final int HUB_X = -110, HUB_Z = 0, HUB_R = 36;
    public static final int CUBE_X0 = -300, CUBE_Y0 = 64, CUBE_Z0 = -50, CUBE_COTE = 100;
    public static final int PONT_DEMI = 2;
    public static final int PORTE_TOUR_X = -71, PORTE_CUBE_X = -149;
    public static final int PONT_TOUR_FIN = -26;
    public static final int VESTIBULE_X1 = -197, VESTIBULE_X2 = -189, VESTIBULE_DEMI = 3;
    public static final int ATELIER_Z = HUB_Z + 29;
    public static final int PORTE_BAS = 64, PORTE_HAUT = 67;
    /* Mer de lave (27/09/2026) : pont vers le SUD depuis le bord du hub, porte sur l'axe z ; la mer commence à z ≈ 104. */
    public static final int LAVE_X = -92, PORTE_LAVE_Z = 37, PONT_LAVE_FIN = 108;
    public static int bordHubLave() { return (int) Math.floor(HUB_Z + Math.sqrt(HUB_R * HUB_R - (double) (LAVE_X - HUB_X) * (LAVE_X - HUB_X))) - 1; }
    public static boolean surPontLave(double x, double z) { return Math.abs(x - (LAVE_X + 0.5)) <= PONT_DEMI + 0.7 && z > bordHubLave() - 2 && z < PONT_LAVE_FIN + 3; }

    public static Lieu spawn() { return new Lieu(HUB_X + 0.5, Y, HUB_Z + 18.5, 180f, 0f); }
    public static Lieu bordTour() { return new Lieu(PONT_TOUR_FIN - 3 + 0.5, Y, 0.5, -90f, 0f); }
    public static Lieu bordCube() { return new Lieu(PORTE_CUBE_X - 4 + 0.5, Y, 0.5, 90f, 0f); }
    public static Lieu bordLave() { return new Lieu(LAVE_X + 0.5, Y, PORTE_LAVE_Z + 4.5, 0f, 0f); }
    public static boolean surPont(double x, double z) { return Math.abs(z) <= PONT_DEMI + 0.7; }
    public static double distHub(double x, double z) { return Math.hypot(x - HUB_X, z - HUB_Z); }
}
