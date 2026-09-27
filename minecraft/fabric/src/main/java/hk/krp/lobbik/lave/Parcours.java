package hk.krp.lobbik.lave;

import java.util.*;

/**
 * La Mer de lave (27/09/2026, Kripy : « une mer de lave, il faut sauter, arriver au bout ; 500 voire 1000 blocs de long ; mode
 * labyrinthe mais sans murs ; rebondir ; 4 plateformes de réapparition de progression »).
 * Génération déterministe (graine) : même graine → même parcours, reconstruit à l'identique et redessiné en 3D sur le site.
 * Pure logique (aucune classe Minecraft) : Construction.java pose les blocs.
 *
 * Le parcours file vers le sud (+z) depuis le pont du hub. Chemin principal = suite de dalles de difficulté croissante ; des
 * FOURCHES (deux routes qui se rejoignent plus loin, l'une plus dure et plus courte) et des FAUSSES PISTES (culs-de-sac) en font
 * un labyrinthe à ciel ouvert. Progression = distance parcourue sur le chemin principal (une fausse piste ne fait pas avancer).
 */
public final class Parcours {
    /** genre : 0 dalle, 1 rebond (slime), 2 glace, 3 éphémère (disparaît et revient), 4 réapparition, 5 départ, 6 arrivée */
    public static final class Dalle {
        public final int id, x, y, z, largeur, genre; public final int progres;   // progrès (en blocs depuis le départ) accordé en s'y posant
        public final int route;   // 0 chemin principal, 1 fourche, 2 fausse piste
        Dalle(int id, int x, int y, int z, int largeur, int genre, int progres, int route) { this.id = id; this.x = x; this.y = y; this.z = z; this.largeur = largeur; this.genre = genre; this.progres = progres; this.route = route; }
    }

    public static final int X0 = -92;            // axe du parcours (même x que le pont qui descend du hub)
    public static final int Z_DEPART = 112;      // centre de la dalle de départ
    public static final int Y_LAVE = 58;         // surface de la lave
    public static final int Y_BASE = 62;         // hauteur des dalles au départ
    public static final int DEMI_LARGEUR = 56;   // la mer va de X0 − 56 à X0 + 56

    public final long graine; public final int longueur;
    public final List<Dalle> dalles = new ArrayList<>();
    public final Map<String, Dalle> parPosition = new HashMap<>();
    public final List<Integer> reapparitions = new ArrayList<>();   // ids des 4 plateformes de réapparition, dans l'ordre
    public Dalle depart, arrivee;
    private final Random r;
    private int prochain = 0;
    private int cibleX = X0;   // le chemin principal serpente : il vise un x, puis un autre, d'un bord à l'autre de la mer

    public Parcours(long graine, int longueur) {
        this.graine = graine; this.longueur = Math.max(300, Math.min(1500, longueur));
        this.r = new Random(graine);
        depart = ajouter(X0, Y_BASE, Z_DEPART, 5, 5, 0, 0);   // genre 5 : départ
        // 4 réapparitions à 1/5, 2/5, 3/5 et 4/5 du chemin ; l'arrivée au bout
        int zFin = Z_DEPART + this.longueur;
        int[] paliers = new int[4]; for (int k = 0; k < 4; k++) paliers[k] = Z_DEPART + this.longueur * (k + 1) / 5;
        int px = X0, py = Y_BASE, pz = Z_DEPART + 2, prochainPalier = 0;
        List<Dalle> principal = new ArrayList<>(); principal.add(depart);
        while (pz < zFin - 6) {
            double f = (pz - Z_DEPART) / (double) this.longueur;       // 0 au départ, 1 à l'arrivée
            if (prochainPalier < 4 && pz >= paliers[prochainPalier]) {
                // plateforme de réapparition : grande dalle 5×5, un saut facile pour l'atteindre
                int nz = pz + 4, nx = px + r.nextInt(3) - 1;
                Dalle cp = ajouter(nx, py, nz, 5, 4, nz - Z_DEPART, 0);
                reapparitions.add(cp.id); principal.add(cp);
                px = nx; pz = nz + 2; prochainPalier++; continue;
            }
            if (f > 0.93) cibleX = X0;   // on revient vers l'axe pour finir face au hub
            else if (Math.abs(px - cibleX) < 5 || r.nextInt(100) < 3) cibleX = X0 + (r.nextInt(2 * (DEMI_LARGEUR - 12)) - (DEMI_LARGEUR - 12));
            Dalle d = sautSuivant(px, py, pz, f, 0, true);
            if (d == null) { pz++; continue; }
            principal.add(d); px = d.x; py = d.y; pz = d.z + d.largeur / 2;
            // labyrinthe : fausses pistes et fourches, plus nombreuses en avançant
            if (d.largeur == 1 && f > 0.05 && r.nextInt(100) < 7 + (int) (6 * f)) faussePiste(d, f);
        }
        arrivee = ajouter(px, py, pz + 6, 9, 6, this.longueur, 0);   // juste après la dernière dalle : toujours atteignable
        // fourches : entre deux dalles du chemin principal assez éloignées, une seconde route qui les relie
        for (int i = 6; i + 8 < principal.size(); i += 9 + r.nextInt(8)) if (r.nextInt(100) < 55) fourche(principal.get(i), principal.get(i + 5 + r.nextInt(3)));
    }

    /** Une dalle atteignable depuis (px, py, pz), vers le sud, difficulté selon f (0 → 1). */
    private Dalle sautSuivant(int px, int py, int pz, double f, int route, boolean principal) {
        for (int essai = 0; essai < 40; essai++) {
            int sens = r.nextInt(100) < 38 ? 1 : r.nextInt(100) < 22 ? -1 : 0;   // monte, descend, à plat
            double maxPlat = 3.0 + 1.2 * f, maxMonte = 2.2 + 1.0 * f, maxDesc = 3.8 + 0.9 * f;
            double dist = sens == 1 ? 1.9 + r.nextDouble() * (maxMonte - 1.9) : sens == 0 ? 2.1 + r.nextDouble() * (maxPlat - 2.1) : 2.8 + r.nextDouble() * (maxDesc - 2.8);
            double vers = principal ? Math.max(-0.75, Math.min(0.75, (cibleX - px) / 12.0)) : 0;   // attiré vers le x visé
            double ang = vers + (r.nextDouble() - 0.5) * (principal ? 1.0 : 2.2);    // écart par rapport au sud (radians)
            int nx = px + (int) Math.round(Math.sin(ang) * dist), nz = pz + Math.max(1, (int) Math.round(Math.cos(ang) * dist));
            int ny = Math.max(Y_LAVE + 3, Math.min(Y_LAVE + 16, py + sens));
            if (Math.abs(nx - X0) > DEMI_LARGEUR - 4) continue;
            double dx = nx - px, dz = nz - pz, d = Math.sqrt(dx * dx + dz * dz);
            double lim = ny > py ? maxMonte : ny < py ? maxDesc : maxPlat;
            if (d < 1.9 || d > lim + 0.35) continue;
            boolean large = r.nextInt(100) < 12 - (int) (9 * f);
            int genre = 0;
            if (!large && f > 0.08) { int t = r.nextInt(100);
                genre = t < 6 + (int) (8 * f) ? 1 : t < 12 + (int) (12 * f) ? 2 : t < 18 + (int) (20 * f) ? 3 : 0; }
            if (occupe(nx, ny, nz, large ? 3 : 1)) continue;
            return ajouter(nx, ny, nz, large ? 3 : 1, genre, principal ? nz - Z_DEPART : -1, route);
        }
        return null;
    }

    /** Fausse piste : 3 à 8 sauts qui partent de côté et s'arrêtent au-dessus de la lave. Même aspect que le vrai chemin. */
    private void faussePiste(Dalle depuis, double f) {
        int n = 3 + r.nextInt(6), x = depuis.x, y = depuis.y, z = depuis.z; int cote = r.nextBoolean() ? 1 : -1;
        for (int k = 0; k < n; k++) {
            int nx = x + cote * (2 + r.nextInt(2)), nz = z + r.nextInt(3), ny = Math.max(Y_LAVE + 3, Math.min(Y_LAVE + 16, y + (r.nextInt(3) - 1)));
            double d = Math.hypot(nx - x, nz - z); if (d > 3.6 || Math.abs(nx - X0) > DEMI_LARGEUR - 4 || occupe(nx, ny, nz, 1)) break;
            int genre = f > 0.1 && r.nextInt(100) < 20 ? (r.nextBoolean() ? 1 : 3) : 0;
            Dalle d2 = ajouter(nx, ny, nz, 1, genre, depuis.progres, 2);
            x = d2.x; y = d2.y; z = d2.z;
        }
    }

    /** Fourche : une route parallèle qui relie a → b par l'autre côté (souvent plus dure : dalles éphémères, rebonds). */
    private void fourche(Dalle a, Dalle b) {
        int cote = r.nextBoolean() ? 1 : -1, x = a.x, y = a.y, z = a.z; int ecart = 4 + r.nextInt(3);
        List<Dalle> faites = new ArrayList<>();
        for (int k = 0; k < 14; k++) {
            int restantZ = b.z - z; if (restantZ <= 3 && Math.abs(b.x - x) <= 3) break;
            int cibleX = (k < 3 ? a.x + cote * ecart : restantZ < 8 ? b.x : a.x + cote * ecart);
            int nx = x + Integer.signum(cibleX - x) * Math.min(2, Math.abs(cibleX - x)), nz = z + (restantZ > 3 ? 2 + r.nextInt(2) : 0);
            int ny = Math.max(Y_LAVE + 3, Math.min(Y_LAVE + 16, y + Integer.signum(b.y - y)));
            if (Math.hypot(nx - x, nz - z) > 3.7 || Math.abs(nx - X0) > DEMI_LARGEUR - 4 || occupe(nx, ny, nz, 1)) return;   // abandon propre : une fourche ratée devient une fausse piste
            double f = (nz - Z_DEPART) / (double) longueur;
            int genre = r.nextInt(100) < 35 ? 3 : r.nextInt(100) < 25 ? 1 : 0;
            Dalle d = ajouter(nx, ny, nz, 1, genre, Math.min(nz - Z_DEPART, b.progres), 1);
            faites.add(d); x = nx; y = ny; z = nz;
        }
    }

    private boolean occupe(int x, int y, int z, int largeur) {
        int m = largeur / 2 + 1;
        for (int dx = -m; dx <= m; dx++) for (int dz = -m; dz <= m; dz++) for (int dy = -2; dy <= 2; dy++)
            if (parPosition.containsKey((x + dx) + "," + (y + dy) + "," + (z + dz))) return true;
        return false;
    }
    private Dalle ajouter(int x, int y, int z, int largeur, int genre, int progres, int route) {
        Dalle d = new Dalle(prochain++, x, y, z, largeur, genre, Math.max(0, progres), route);
        dalles.add(d);
        int m = largeur / 2;
        for (int dx = -m; dx <= m; dx++) for (int dz = -m; dz <= m; dz++) parPosition.put((x + dx) + "," + y + "," + (z + dz), d);
        return d;
    }

    /** La dalle sous les pieds (ou un cran plus bas pour les bords). */
    public Dalle sous(double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        Dalle d = parPosition.get(bx + "," + (by - 1) + "," + bz);
        return d != null ? d : parPosition.get(bx + "," + by + "," + bz);
    }
    public Dalle reapparition(int n) { if (n <= 0) return depart; int id = reapparitions.get(Math.min(n, reapparitions.size()) - 1); return dalles.get(id); }
    public int zMin() { return Z_DEPART - 8; }
    public int zMax() { return Z_DEPART + longueur + 12; }

    /** Pour le site (vue 3D) et les écrans du hub : toutes les dalles, compactes. */
    public String json() {
        StringBuilder b = new StringBuilder("{\"graine\":").append(graine).append(",\"longueur\":").append(longueur).append(",\"x0\":").append(X0)
            .append(",\"z_depart\":").append(Z_DEPART).append(",\"y_lave\":").append(Y_LAVE).append(",\"demi_largeur\":").append(DEMI_LARGEUR)
            .append(",\"reapparitions\":").append(reapparitions).append(",\"dalles\":[");
        for (int i = 0; i < dalles.size(); i++) { Dalle d = dalles.get(i); if (i > 0) b.append(',');
            b.append('[').append(d.x).append(',').append(d.y).append(',').append(d.z).append(',').append(d.largeur).append(',').append(d.genre).append(',').append(d.route).append(',').append(d.progres).append(']'); }
        return b.append("]}").toString();
    }

    /** Essai hors jeu : java Parcours.java [graine] [longueur] → statistiques. */
    public static void main(String[] a) {
        Parcours p = new Parcours(a.length > 0 ? Long.parseLong(a[0]) : 20260927L, a.length > 1 ? Integer.parseInt(a[1]) : 800);
        int[] genres = new int[7], routes = new int[3];
        for (Dalle d : p.dalles) { genres[d.genre]++; routes[d.route]++; }
        System.out.println("dalles " + p.dalles.size() + " · principal " + routes[0] + " · fourches " + routes[1] + " · fausses pistes " + routes[2]);
        System.out.println("genres : dalle " + genres[0] + ", rebond " + genres[1] + ", glace " + genres[2] + ", éphémère " + genres[3] + ", réapparition " + genres[4]);
        System.out.println("réapparitions " + p.reapparitions + " · arrivée z " + p.arrivee.z + " · x de " + p.dalles.stream().mapToInt(d -> d.x).min().getAsInt() + " à " + p.dalles.stream().mapToInt(d -> d.x).max().getAsInt()
            + " · y de " + p.dalles.stream().mapToInt(d -> d.y).min().getAsInt() + " à " + p.dalles.stream().mapToInt(d -> d.y).max().getAsInt());
    }
}
