package hk.krp.tour;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;

/** La Tour : un mur central massif, et une spirale de cubes à gravir autour. Chaque cube porte un id (= niveau).
 *  Génération déterministe à partir d'une graine : la carte est la même pour tous et se reconstruit à l'identique. */
public final class Carte {
    public static final class Cube {
        public final int id, x, y, z; public final boolean ephemere, checkpoint; public final int largeur; public final int genre;   // genre : 0 normal, 1 rebond (slime), 2 glace
        public final int niveau;          // niveau accordé : = id sur le chemin, = id de la bifurcation dans un cul-de-sac (on ne progresse pas en s'y perdant)
        Cube(int id, int x, int y, int z, boolean ephemere, boolean checkpoint, int largeur) { this(id, x, y, z, ephemere, checkpoint, largeur, 0, id); }
        Cube(int id, int x, int y, int z, boolean ephemere, boolean checkpoint, int largeur, int genre) { this(id, x, y, z, ephemere, checkpoint, largeur, genre, id); }
        Cube(int id, int x, int y, int z, boolean ephemere, boolean checkpoint, int largeur, int genre, int niveau) { this.id = id; this.x = x; this.y = y; this.z = z; this.ephemere = ephemere; this.checkpoint = checkpoint; this.largeur = largeur; this.genre = genre; this.niveau = niveau; }
        public String cle() { return x + "," + y + "," + z; }
    }

    public static final int RAYON_MUR = 14;      // rayon du mur central (pierre)
    public static final int Y_DEPART = 64;       // sol de départ
    public static final int HAUTEUR = 1000;      // hauteur visée de l'ascension (monde surélevé par le paquet de données : y max 1152)
    public final long graine;
    public final List<Cube> cubes = new ArrayList<>();
    public final Map<String, Cube> parPosition = new HashMap<>();  // "x,y,z" (chaque bloc d'un cube large) → cube
    public final List<Integer> checkpoints = new ArrayList<>();     // ids des 2 points de réapparition
    public int niveauMax = 0;                                      // niveau du sommet (nombre de cubes du chemin principal − 1)
    public Cube sommet() { for (Cube c : cubes) if (c.niveau == niveauMax && c.niveau == c.id || c.niveau == niveauMax) return c; return cubes.get(cubes.size() - 1); }

    public Carte(long graine) {
        this.graine = graine;
        Random r = new Random(graine);
        double angle = 0; int y = Y_DEPART; double rayon = RAYON_MUR + 3.5;
        int id = 0;
        // Départ : plateforme 5×5 devant le mur
        int cx = (int) Math.round(Math.cos(angle) * rayon), cz = (int) Math.round(Math.sin(angle) * rayon);
        ajouter(new Cube(id++, cx, y, cz, false, false, 5));   // le cube 0 : plateforme de départ sur la base
        int cibleCp1 = -1, cibleCp2 = -1;
        int prevX = cx, prevZ = cz, prevY = y;
        while (y < Y_DEPART + HAUTEUR) {
            // difficulté progressive : f = 0 en bas, 1 au sommet
            double f = Math.min(1.0, (y - Y_DEPART) / (double) HAUTEUR);
            double maxPlat = 3.0 + 0.9 * f;        // à plat : 3,0 → 3,9 entre centres (saut sprint max ≈ 4)
            double maxMonte = 2.1 + 0.9 * f;       // +1 : 2,1 → 3,0 (il faut savoir sauter)
            int probaMonte = 50 + (int) (10 * f), probaLarge = 12 - (int) (9 * f), probaEph = 3 + (int) (16 * f), probaDescente = 6;
            int sens = r.nextInt(100) < probaMonte ? 1 : (r.nextInt(100) < probaDescente ? -1 : 0);
            double distance = sens == 1 ? 1.9 + r.nextDouble() * (maxMonte - 1.9) : sens == 0 ? 2.2 + r.nextDouble() * (maxPlat - 2.2) : 3.0 + r.nextDouble() * 1.3;
            angle += distance / rayon;
            rayon += (r.nextDouble() - 0.5) * 1.4; rayon = Math.max(RAYON_MUR + 2.5, Math.min(RAYON_MUR + 6.5, rayon));
            int nx = (int) Math.round(Math.cos(angle) * rayon), nz = (int) Math.round(Math.sin(angle) * rayon);
            int ny = y + sens;
            // vérification géométrique du saut (centres des blocs) ; sinon on rapproche
            double dx = nx - prevX, dz = nz - prevZ, d = Math.sqrt(dx * dx + dz * dz);
            double limite = sens == 1 ? maxMonte : sens == 0 ? maxPlat : 4.3;
            if (d < 1.5 || d > limite || (nx == prevX && nz == prevZ)) { angle += 0.05; continue; }
            boolean large = r.nextInt(100) < probaLarge;               // paliers 3×3 : fréquents en bas, rares en haut
            boolean ephemere = !large && id > 20 && r.nextInt(100) < probaEph;
            // cubes spéciaux (vanilla, rien à installer) : rebond (slime) et glace glissante, de plus en plus fréquents en montant
            int genre = (!ephemere && !large && f > 0.15) ? (r.nextInt(100) < 5 + (int) (6 * f) ? 1 : r.nextInt(100) < 4 + (int) (5 * f) ? 2 : 0) : 0;
            Cube c = new Cube(id, nx, ny, nz, ephemere, false, large ? 3 : 1, genre);
            ajouter(c); id++;
            prevX = nx; prevZ = nz; prevY = ny; y = ny;
            // parfois deux chemins : une branche de 3 à 7 cubes part dans l'autre sens et finit en cul-de-sac (même aspect que le chemin)
            if (!large && f > 0.08 && r.nextInt(100) < 5) { id = culDeSac(r, id, c, angle, rayon, f); }
        }
        // 2 points de réapparition seulement : au tiers et aux deux tiers de la hauteur, sur un palier large
        int total = cubes.size();
        cibleCp1 = plusProche(total / 3); cibleCp2 = plusProche(2 * total / 3);
        // les cubes du chemin principal reçoivent un niveau continu (les culs-de-sac gardent celui de leur bifurcation)
        int niv = 0; Map<Integer, Integer> nivDe = new HashMap<>();
        for (int k = 0; k < cubes.size(); k++) { Cube c = cubes.get(k); if (c.niveau == c.id) { nivDe.put(c.id, niv); cubes.set(k, new Cube(c.id, c.x, c.y, c.z, c.ephemere, c.checkpoint, c.largeur, c.genre, niv)); niv++; } }
        for (int k = 0; k < cubes.size(); k++) { Cube c = cubes.get(k); if (c.niveau != c.id && nivDe.containsKey(c.niveau) == false && c.niveau < c.id) { /* cul-de-sac : niveau = celui (recalculé) de la bifurcation */ } }
        for (int k = 0; k < cubes.size(); k++) { Cube c = cubes.get(k); Integer nn = nivDe.get(c.niveau); if (nn != null && c.niveau != c.id) cubes.set(k, new Cube(c.id, c.x, c.y, c.z, c.ephemere, c.checkpoint, c.largeur, c.genre, nn)); }
        niveauMax = niv - 1;
        for (int k : new int[]{cibleCp1, cibleCp2}) {
            Cube old = cubes.get(k);
            Cube cp = new Cube(old.id, old.x, old.y, old.z, false, true, 3, 0, old.niveau);
            cubes.set(k, cp); checkpoints.add(cp.id);
        }
        parPosition.clear();
        for (Cube c : cubes) indexer(c);
    }

    public String json() {
        StringBuilder b = new StringBuilder("{\"graine\":" + graine + ",\"rayon_mur\":" + RAYON_MUR + ",\"y_depart\":" + Y_DEPART + ",\"checkpoints\":" + checkpoints + ",\"cubes\":[");
        for (int i = 0; i < cubes.size(); i++) { Cube c = cubes.get(i); if (i > 0) b.append(','); b.append('[').append(c.id).append(',').append(c.x).append(',').append(c.y).append(',').append(c.z).append(',').append(c.largeur).append(',').append(c.ephemere ? 1 : 0).append(',').append(c.checkpoint ? 1 : 0).append(',').append(c.genre).append(',').append(c.niveau).append(']'); }
        return b.append("]}").toString();
    }
    /** Une fausse piste : des cubes qui repartent dans l'autre sens (ou montent tout droit) puis s'arrêtent. Ils portent le niveau de la bifurcation. */
    private int culDeSac(Random r, int id, Cube depuis, double angle, double rayon, double f) {
        int n = 3 + r.nextInt(5); double a = angle; double ray = rayon; int px = depuis.x, pz = depuis.z, py = depuis.y;
        boolean arriere = r.nextBoolean();
        for (int k = 0; k < n; k++) {
            boolean monte = r.nextInt(100) < 50;
            double d = monte ? 2.0 + r.nextDouble() * (0.9 + 0.8 * f) : 2.3 + r.nextDouble() * (1.2 + 0.4 * f);
            a += (arriere ? -1 : 1) * d / ray; ray += arriere ? 0.9 : -0.6; ray = Math.max(RAYON_MUR + 2.5, Math.min(RAYON_MUR + 9, ray));
            int nx = (int) Math.round(Math.cos(a) * ray), nz = (int) Math.round(Math.sin(a) * ray), ny = py + (monte ? 1 : 0);
            double dx = nx - px, dz = nz - pz, dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 1.5 || dist > (monte ? 2.9 : 3.7)) { a += (arriere ? -0.05 : 0.05); k--; if (r.nextInt(6) == 0) break; continue; }
            if (occupe(nx, ny, nz)) break;                       // on ne traverse jamais le vrai chemin
            Cube c = new Cube(id++, nx, ny, nz, false, false, 1, 0, depuis.niveau);
            ajouter(c); px = nx; pz = nz; py = ny;
        }
        return id;
    }
    private boolean occupe(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) if (parPosition.containsKey((x + dx) + "," + (y + dy) + "," + (z + dz))) return true;
        return false;
    }
    private int plusProche(int idx) { for (int k = idx; k < cubes.size(); k++) if (cubes.get(k).largeur == 3) return k; return idx; }
    private void ajouter(Cube c) { cubes.add(c); indexer(c); }
    private void indexer(Cube c) {
        int r = c.largeur / 2;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) parPosition.put((c.x + dx) + "," + c.y + "," + (c.z + dz), c);
    }

    public Cube cubeSous(Location l) {
        // le bloc sous les pieds (et un cran plus bas pour les bords de bloc)
        Cube c = parPosition.get(l.getBlockX() + "," + (l.getBlockY() - 1) + "," + l.getBlockZ());
        if (c == null) c = parPosition.get(l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
        return c;
    }

    public Location lieu(World w, Cube c) { return new Location(w, c.x + 0.5, c.y + 1.02, c.z + 0.5); }

    /** Pose la Tour dans le monde (mur + cubes). Idempotent. */
    public void construire(World w) {
        int ymax = Y_DEPART + HAUTEUR + 20;
        // le mur : cylindre de pierre taillée, creux, du fond du monde au sommet
        for (int x = -RAYON_MUR - 1; x <= RAYON_MUR + 1; x++) for (int z = -RAYON_MUR - 1; z <= RAYON_MUR + 1; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > RAYON_MUR + 0.6 || d < RAYON_MUR - 1.6) continue;
            for (int y = Y_DEPART - 30; y <= ymax; y++) {
                Material m = (y % 17 == 0) ? Material.POLISHED_DEEPSLATE : ((x + y + z) % 5 == 0 ? Material.STONE_BRICKS : Material.DEEPSLATE_BRICKS);
                w.getBlockAt(x, y, z).setType(m, false);
            }
        }
        // couronne lumineuse au sommet
        for (int x = -RAYON_MUR - 1; x <= RAYON_MUR + 1; x++) for (int z = -RAYON_MUR - 1; z <= RAYON_MUR + 1; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d <= RAYON_MUR + 0.6 && d >= RAYON_MUR - 1.6) w.getBlockAt(x, ymax + 1, z).setType(Material.SEA_LANTERN, false);
        }
        // la base : une grande esplanade ronde au pied du mur (rayon mur + 14), bordée d'une margelle lumineuse
        int rb = RAYON_MUR + 14;
        for (int x = -rb - 1; x <= rb + 1; x++) for (int z = -rb - 1; z <= rb + 1; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > rb + 0.5 || d < RAYON_MUR - 1) continue;
            // une île qui flotte dans le vide : dessus dallé, bord en herbe et lanternes, dessous en terre puis roche qui s'amincit
            w.getBlockAt(x, Y_DEPART - 1, z).setType(d > rb - 0.8 ? Material.SEA_LANTERN : d > rb - 3 ? Material.GRASS_BLOCK : ((x + z) % 6 == 0 ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE), false);
            for (int k = 2; k <= 9; k++) {
                if (d > rb + 0.5 - (k - 1) * 1.6) break;
                w.getBlockAt(x, Y_DEPART - k, z).setType(k <= 3 ? Material.DIRT : k <= 7 ? Material.STONE : Material.DEEPSLATE, false);
            }
        }
        for (Cube c : cubes) poser(w, c, true);
        // lampes le long du mur, une tous les 8 cubes, pour lire la hauteur
        for (int k = 0; k < cubes.size(); k += 8) {
            Cube c = cubes.get(k);
            double a = Math.atan2(c.z, c.x); int lx = (int) Math.round(Math.cos(a) * (RAYON_MUR + 0.4)), lz = (int) Math.round(Math.sin(a) * (RAYON_MUR + 0.4));
            Block b = w.getBlockAt(lx, c.y + 2, lz);
            if (b.getType() != Material.AIR) b.setType(Material.SEA_LANTERN, false);
        }
    }

    public void poser(World w, Cube c, boolean visible) {
        int r = c.largeur / 2;
        Material m = !visible ? Material.AIR : c.checkpoint ? Material.GOLD_BLOCK : c.ephemere ? Material.PURPLE_CONCRETE : c.genre == 1 ? Material.SLIME_BLOCK : c.genre == 2 ? Material.PACKED_ICE : c.largeur == 3 ? Material.SMOOTH_QUARTZ : matiere(c.id);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) w.getBlockAt(c.x + dx, c.y, c.z + dz).setType(m, false);
        if (visible && c.checkpoint) {
            // point de réapparition : dalle d'or, lanterne au centre, colonne de lumière visible de loin
            w.getBlockAt(c.x, c.y, c.z).setType(Material.SEA_LANTERN, false);
            for (int k = 1; k <= 3; k++) w.getBlockAt(c.x, c.y + k, c.z).setType(Material.END_ROD, false);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (Math.abs(dx) + Math.abs(dz) == 2) w.getBlockAt(c.x + dx, c.y + 1, c.z + dz).setType(Material.GLOWSTONE, false);
        }
    }
    /** Avertissement avant disparition : le cube passe au rouge. */
    public void avertir(World w, Cube c) {
        int r = c.largeur / 2;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) w.getBlockAt(c.x + dx, c.y, c.z + dz).setType(Material.RED_CONCRETE, false);
    }
    private static Material matiere(int id) {
        Material[] pal = { Material.WHITE_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.CYAN_CONCRETE, Material.LIME_CONCRETE, Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE, Material.PINK_CONCRETE, Material.MAGENTA_CONCRETE };
        return pal[(id / 40) % pal.length];   // la couleur change tous les 40 cubes : on voit sa progression
    }
}
