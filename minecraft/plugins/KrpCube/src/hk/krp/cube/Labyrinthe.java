package hk.krp.cube;

import java.util.*;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;

/** Le Cube : une grille de salles cubiques (intérieur 7×7×7, murs de 1 bloc propres à chaque salle, pas de 10 blocs),
 *  reliées par une porte de fer au milieu de chaque face et par l'échelle centrale (trappes haut/bas).
 *  Depuis le 23/09/2026 au soir (Maxime : « une salle tue parfois et parfois non, c'est incohérent »), comme dans le film,
 *  c'est la SALLE qui tue : le plan (voir Plan) désigne les salles sûres, qui forment un labyrinthe 3D ; toute autre salle est
 *  mortelle, par quelque porte qu'on y entre. Aucun numéro, aucune indication : couleur, éclairage, décor ne disent rien.
 *  Génération déterministe (graine). */
public final class Labyrinthe {
    public static final int P = 10, Y0 = 64;   // 7 d'intérieur + 2 murs (un par cube) + 1 bloc de sas partagé
    /** Origine du Cube : (0, 0) jusqu'au 27/09/2026 ; (−300, −50) dans le réseau Lobbik (config « origine_x / origine_z »), à côté du hub. */
    public static int X0 = 0, Z0 = 0;
    public enum Piege { AUCUN, LAMES, GAZ, FLAMMES, ACIDE, CHUTE }
    /** issues : 0 +x, 1 −x, 2 +z, 3 −z, 4 haut, 5 bas */
    public static final int[] DX = Plan.DX, DY = Plan.DY, DZ = Plan.DZ;

    public static final class Salle {
        public final int i, j, k, id;
        public int couleur;
        public Piege piege = Piege.AUCUN;               // salle mortelle : la manière dont elle tue (AUCUN = salle sûre)
        public boolean sure, depart, sortie, chemin;
        public String code = "";                        // numéro façon film « 517 · 244 · 831 » : haché de l'id et de la graine, sans lien avec le danger ni les coordonnées    // sure : dans le labyrinthe des salles sûres ; sinon elle tue, toujours          // chemin : sur la route (unique) départ → sortie
        public final boolean[] portes = new boolean[6];  // une porte existe (toujours, même au bord : elle donne sur le vide)
        public final boolean[] passage = new boolean[6]; // la salle voisine par cette issue est sûre elle aussi (et celle-ci aussi)
        Salle(int i, int j, int k, int id) { this.i = i; this.j = j; this.k = k; this.id = id; }
        public int ox() { return X0 + i * P; } public int oy() { return Y0 + j * P; } public int oz() { return Z0 + k * P; }
        public Location centre(World w) { return new Location(w, ox() + 4.5, oy() + 1, oz() + 4.5); }
        public String coord() { return "[" + i + "," + j + "," + k + "]"; }
    }

    // 0 blanc (départ/sortie), 1 bleu, 2 orange, 3 brun, 4 vert, 5 rouge — tirées au hasard, deux voisines peuvent se ressembler
    // Maxime : « des cubes plus lisses, la laine », « le cube de toute la même couleur » → sol, murs et plafond en laine identique
    static final Material[] LAINE = {Material.WHITE_WOOL, Material.BLUE_WOOL, Material.ORANGE_WOOL, Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL};
    public static final String[] NOMS = {"blanche", "bleue", "orange", "brune", "verte", "rouge"};
    public static final NamedTextColor[] TEINTES = {NamedTextColor.WHITE, NamedTextColor.BLUE, NamedTextColor.GOLD, NamedTextColor.DARK_RED, NamedTextColor.GREEN, NamedTextColor.RED};

    public final long graine; public final int nx, ny, nz, partPieges;
    public final Plan plan;
    public final Salle[][][] grille; public final List<Salle> salles = new ArrayList<>();
    public Salle depart, sortie;

    public Labyrinthe(long graine, int nx, int ny, int nz, int partPieges) {
        this.nx = nx; this.ny = ny; this.nz = nz; this.partPieges = partPieges;
        grille = new Salle[nx][ny][nz];
        int id = 0;   // même ordre d'identifiants que Plan.id(i, j, k) = (j·nz + k)·nx + i
        for (int j = 0; j < ny; j++) for (int k = 0; k < nz; k++) for (int i = 0; i < nx; i++) { Salle s = new Salle(i, j, k, id++); grille[i][j][k] = s; salles.add(s); }
        plan = Plan.generer(graine, nx, ny, nz); this.graine = plan.graine;
        depart = salles.get(plan.depart); depart.depart = true; sortie = salles.get(plan.sortie); sortie.sortie = true;
        for (Salle s : salles) {
            s.chemin = plan.surChemin[s.id]; s.sure = plan.sure[s.id];
            for (int d = 0; d < 6; d++) { s.portes[d] = true; s.passage[d] = plan.passage[s.id][d]; }
        }
        // couleurs et manières de tuer : tirées de la graine retenue (les couleurs sont indépendantes des salles sûres : elles ne trahissent rien)
        Random r = new Random(plan.graine * 31 + 7);
        for (Salle s : salles) s.couleur = 1 + r.nextInt(LAINE.length - 1);
        depart.couleur = 0; sortie.couleur = 0;
        Set<String> pris = new HashSet<>();
        for (Salle s : salles) { int e = 0; String c; do c = codePour(plan.graine, s.id, e++); while (!pris.add(c)); s.code = c; }
        Piege[] choix = {Piege.LAMES, Piege.GAZ, Piege.FLAMMES, Piege.ACIDE, Piege.CHUTE};
        for (Salle s : salles) { Piege pg = choix[r.nextInt(choix.length)]; if (!s.sure) s.piege = pg; }   // toute salle mortelle a sa manière ; une salle sûre n'en a aucune
    }

    public Salle voisin(Salle s, int d) {
        int i = s.i + DX[d], j = s.j + DY[d], k = s.k + DZ[d];
        return i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz ? null : grille[i][j][k];
    }
    /** Directions en clair pour les commandes op : 0 +x est, 1 −x ouest, 2 +z sud, 3 −z nord, 4 haut, 5 bas. */
    public static final String[] DIRECTIONS = {"est", "ouest", "sud", "nord", "haut", "bas"};
    /** Code unique à trois nombres de 3 chiffres (100-999), déterministe (graine + id + tentative en cas de doublon). */
    private static String codePour(long graine, int id, int essai) {
        long h = graine * 0x9E3779B97F4A7C15L + (id + 1) * 0xBF58476D1CE4E5B9L + essai * 0x94D049BB133111EBL;
        h ^= h >>> 31; h *= 0x7FB5D329728EA185L; h ^= h >>> 27; h *= 0x81DADEF4BC2DD44DL; h ^= h >>> 33;
        return (100 + Math.floorMod(h, 900)) + " · " + (100 + Math.floorMod(h >>> 21, 900)) + " · " + (100 + Math.floorMod(h >>> 42, 900));
    }
    public int nbMortelles() { int n = 0; for (Salle s : salles) if (!s.sure) n++; return n; }

    /** La salle dont l'intérieur contient ce point (null dans un mur, une porte ou hors du Cube). */
    public Salle salleDe(Location l) {
        int x = l.getBlockX() - X0, y = l.getBlockY() - Y0, z = l.getBlockZ() - Z0;
        if (x < 0 || y < 0 || z < 0) return null;
        int i = x / P, j = y / P, k = z / P;
        if (i >= nx || j >= ny || k >= nz) return null;
        int rx = x % P, ry = y % P, rz = z % P;
        if (rx < 1 || rx > 7 || rz < 1 || rz > 7 || ry < 1 || ry > 7) return null;
        return grille[i][j][k];
    }

    /* ------------------------------------------------------------------ construction */
    public void construire(World w) {
        for (Salle s : salles) coque(w, s);
        for (Salle s : salles) ouvrir(w, s, false);
        decorDepartSortie(w);
        if (HABILLAGE) habiller(w);
    }

    /** Réseau Lobbik (27/09/2026, Kripy : « le contour de chaque salle en couleur, même si ce n'est pas la vraie couleur dedans »). */
    public static boolean HABILLAGE = false;
    private static final Material[] TUILES = {Material.LIME_CONCRETE, Material.CYAN_CONCRETE, Material.MAGENTA_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE,
        Material.LIGHT_BLUE_CONCRETE, Material.PINK_CONCRETE, Material.PURPLE_CONCRETE, Material.RED_CONCRETE, Material.WHITE_CONCRETE};
    /**
     * Vu de dehors, chaque salle du bord se voit : une tuile de couleur (au hasard, sans lien avec l'intérieur) cernée d'un
     * contour lumineux. Posé sur la couche extérieure seulement — le sas (faces +x, +z, dessus) ou une peau d'un bloc
     * (faces −x, −z, dessous) —, jamais sur un mur que l'on voit de l'intérieur ; portes et trappes du bord intactes.
     */
    public void habiller(World w) {
        Random r = new Random(graine * 17 + 3);
        for (Salle s : salles) for (int d = 0; d < 6; d++) {
            if (voisin(s, d) != null) continue;
            Material tuile = TUILES[r.nextInt(TUILES.length)];
            int ox = s.ox(), oy = s.oy(), oz = s.oz();
            for (int a = 0; a <= 9; a++) for (int b = 0; b <= 9; b++) {
                int x, y, z; boolean porte;
                switch (d) {
                    case 0 -> { x = ox + 9; y = oy + a; z = oz + b; porte = (a == 3 || a == 4) && b == 4; }
                    case 1 -> { x = ox - 1; y = oy + a; z = oz + b; porte = (a == 3 || a == 4) && b == 4; }
                    case 2 -> { x = ox + a; y = oy + b; z = oz + 9; porte = a == 4 && (b == 3 || b == 4); }
                    case 3 -> { x = ox + a; y = oy + b; z = oz - 1; porte = a == 4 && (b == 3 || b == 4); }
                    case 4 -> { x = ox + a; y = oy + 9; z = oz + b; porte = a == 4 && b == 4; }
                    default -> { x = ox + a; y = oy - 1; z = oz + b; porte = a == 4 && b == 4; }
                }
                if (porte) continue;
                boolean bord = a == 0 || b == 0 || a == 9 || b == 9;
                bloc(w, x, y, z, bord ? Material.SEA_LANTERN : tuile);
            }
        }
    }
    private static void bloc(World w, int x, int y, int z, Material m) { w.getBlockAt(x, y, z).setType(m, false); }
    private static void bloc(World w, int x, int y, int z, BlockData d) { w.getBlockAt(x, y, z).setBlockData(d, false); }

    private void coque(World w, Salle s) {
        int ox = s.ox(), oy = s.oy(), oz = s.oz(); Material mur = LAINE[s.couleur];
        // le sas : toute la cellule 10×10×10 en gris sombre, puis le cube 9×9×9 de la couleur de la salle, puis l'intérieur vide
        for (int x = 0; x <= 9; x++) for (int y = 0; y <= 9; y++) for (int z = 0; z <= 9; z++) {
            boolean sas = x == 9 || y == 9 || z == 9;
            boolean bord = x == 0 || x == 8 || y == 0 || y == 8 || z == 0 || z == 8;
            bloc(w, ox + x, oy + y, oz + z, sas ? Material.GRAY_CONCRETE : bord ? mur : Material.AIR);
        }
        for (int[] c : new int[][]{{2, 2}, {6, 2}, {2, 6}, {6, 6}}) bloc(w, ox + c[0], oy + 6, oz + c[1], Material.LIGHT);   // éclairage invisible, normal
        for (int y = 1; y <= 7; y++) echelle(w, ox + 4, oy + y, oz + 4, BlockFace.EAST);   // l'échelle au centre, du sol au plafond, sans mur (posée sans mise à jour physique : elle tient)
        // quatre faces : ouverture au centre du mur (rangées 3-4), porte de fer dans le bloc de sas, deux barreaux d'échelle sous l'ouverture
        for (int d = 0; d < 4; d++) {
            int mx = d == 0 ? ox + 8 : d == 1 ? ox : ox + 4, mz = d == 2 ? oz + 8 : d == 3 ? oz : oz + 4;        // le mur de la salle
            bloc(w, mx, oy + 3, mz, Material.AIR); bloc(w, mx, oy + 4, mz, Material.AIR);                          // ouverture dans le mur
            BlockFace face = d == 0 ? BlockFace.WEST : d == 1 ? BlockFace.EAST : d == 2 ? BlockFace.NORTH : BlockFace.SOUTH;
            echelle(w, mx - DX[d], oy + 1, mz - DZ[d], face); echelle(w, mx - DX[d], oy + 2, mz - DZ[d], face);
            int sx = mx + DX[d], sz = mz + DZ[d];                                                                    // UNE seule porte, au milieu, dans le bloc de passage (Maxime)
            porte(w, sx, oy + 3, sz, DX[d] != 0 ? BlockFace.EAST : BlockFace.SOUTH, false);
        }
        // plafond et sol : trappe au-dessus de l'échelle (plafond + sas), trappe au pied de l'échelle (plancher)
        trappe(w, ox + 4, oy + 8, oz + 4, Bisected.Half.BOTTOM); trappe(w, ox + 4, oy + 9, oz + 4, Bisected.Half.BOTTOM); trappe(w, ox + 4, oy, oz + 4, Bisected.Half.TOP);
        if (voisin(s, 5) == null) trappe(w, ox + 4, oy - 1, oz + 4, Bisected.Half.BOTTOM);   // tout en bas : la trappe de sas donne sur le vide
    }
    private static void echelle(World w, int x, int y, int z, BlockFace face) { org.bukkit.block.data.type.Ladder l = (org.bukkit.block.data.type.Ladder) Material.LADDER.createBlockData(); l.setFacing(face); bloc(w, x, y, z, l); }
    private static void trappe(World w, int x, int y, int z, Bisected.Half moitie) {
        TrapDoor t = (TrapDoor) Material.IRON_TRAPDOOR.createBlockData(); t.setFacing(BlockFace.NORTH); t.setHalf(moitie); t.setOpen(false); bloc(w, x, y, z, t);
    }
    private void porte(World w, int x, int y, int z, BlockFace face, boolean ouverte) {
        Door bas = (Door) Material.IRON_DOOR.createBlockData(); bas.setFacing(face); bas.setHalf(Bisected.Half.BOTTOM); bas.setOpen(ouverte); bas.setHinge(Door.Hinge.LEFT);
        Door haut = (Door) Material.IRON_DOOR.createBlockData(); haut.setFacing(face); haut.setHalf(Bisected.Half.TOP); haut.setOpen(ouverte); haut.setHinge(Door.Hinge.LEFT);
        bloc(w, x, y, z, bas); bloc(w, x, y + 1, z, haut);
    }
    private void decorDepartSortie(World w) {
        Salle s = sortie; int ox = s.ox(), oy = s.oy(), oz = s.oz();
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) if (x != 4 || z != 4) bloc(w, ox + x, oy + 8, oz + z, Material.GLOWSTONE);   // plafond de lumière
        for (int y = 1; y <= 7; y++) for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) bloc(w, ox + c[0], oy + y, oz + c[1], Material.GOLD_BLOCK);
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) if (x != 4 || z != 4) bloc(w, ox + x, oy, oz + z, Material.GOLD_BLOCK);
        s = depart; ox = s.ox(); oy = s.oy(); oz = s.oz();
        for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) for (int y = 1; y <= 7; y++) bloc(w, ox + c[0], oy + y, oz + c[1], Material.QUARTZ_PILLAR);
        bloc(w, ox + 4, oy, oz + 4, Material.WHITE_GLAZED_TERRACOTTA);
    }

    /** Ouvre ou ferme toutes les issues d'une salle. */
    public void ouvrir(World w, Salle s, boolean ouvert) { for (int d = 0; d < 6; d++) if (s.portes[d]) basculer(w, s, d, ouvert); }
    /** La porte d'une issue latérale : unique, dans le bloc de passage entre les deux cubes (x, z). */
    private int[] murDe(Salle s, int d) {
        int ox = s.ox(), oz = s.oz();
        return switch (d) { case 0 -> new int[]{ox + 9, oz + 4}; case 1 -> new int[]{ox - 1, oz + 4}; case 2 -> new int[]{ox + 4, oz + 9}; default -> new int[]{ox + 4, oz - 1}; };
    }
    /** L'issue d de la salle s est-elle ouverte ? (une porte du bord s'ouvre aussi : elle donne sur le vide) */
    public boolean ouverte(World w, Salle s, int d) {
        if (d < 4) { int[] c = murDe(s, d); return w.getBlockAt(c[0], s.oy() + 3, c[1]).getBlockData() instanceof Door door && door.isOpen(); }
        return w.getBlockAt(s.ox() + 4, d == 4 ? s.oy() + 8 : s.oy(), s.oz() + 4).getType() == Material.LADDER;
    }
    /** Ouvre ou ferme UNE issue : la porte du sas (côtés) ou le puits vertical (plafond, sas, plancher du dessus). */
    public void basculer(World w, Salle s, int d, boolean ouvert) {
        Salle v = voisin(s, d);
        if (d < 4) {   // la porte unique du passage
            BlockFace f = DX[d] != 0 ? BlockFace.EAST : BlockFace.SOUTH;
            int[] c = murDe(s, d);
            for (int h = 0; h < 2; h++) {
                Block b = w.getBlockAt(c[0], s.oy() + 3 + h, c[1]);
                if (b.getBlockData() instanceof Door door) { door.setOpen(ouvert); b.setBlockData(door, false); }
                else { Door door = (Door) Material.IRON_DOOR.createBlockData(); door.setFacing(f); door.setHalf(h == 0 ? Bisected.Half.BOTTOM : Bisected.Half.TOP); door.setOpen(ouvert); b.setBlockData(door, false); }
            }
        } else {
            int x = s.ox() + 4, z = s.oz() + 4;   // le puits est au centre, dans l'axe de l'échelle : ouvert = l'échelle continue, fermé = trappes de fer
            int[] ys = d == 4 ? (v != null ? new int[]{s.oy() + 8, s.oy() + 9, s.oy() + 10} : new int[]{s.oy() + 8, s.oy() + 9})
                              : (v != null ? new int[]{s.oy(), s.oy() - 1, s.oy() - 2} : new int[]{s.oy(), s.oy() - 1});
            for (int n = 0; n < ys.length; n++) {
                if (ouvert) echelle(w, x, ys[n], z, BlockFace.EAST);
                else trappe(w, x, ys[n], z, (d == 4 ? n == 2 : n == 0) ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
            }
        }
    }
    /** À partir d'un bloc de porte, de trappe ou de puits (ou des pieds d'un joueur qui s'y trouve) : la salle et l'issue concernées, ou null.
     *  Sert au clic ET à la détection du passage : un joueur dont les pieds sont dans le bloc de sas d'une issue est en train de la franchir. */
    public int[] issueDe(int x, int y, int z) {
        int gx = x - X0, gy = y - Y0, gz = z - Z0;
        int i = Math.floorDiv(gx, P), j = Math.floorDiv(gy, P), k = Math.floorDiv(gz, P), rx = Math.floorMod(gx, P), ry = Math.floorMod(gy, P), rz = Math.floorMod(gz, P);
        if (i == -1 && rx == 9) { i = 0; rx = -1; }   // porte du bord ouest (bloc de passage hors grille) → issue « −x » de la salle 0
        if (k == -1 && rz == 9) { k = 0; rz = -1; }
        if (j == -1 && ry == 9) { j = 0; ry = -1; }   // trappe de sas sous le plancher du bas
        if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) return null;
        if (ry == -1 && rx == 4 && rz == 4) return new int[]{grille[i][j][k].id, 5};
        if (rx == -1 && rz == 4 && (ry == 3 || ry == 4)) return new int[]{grille[i][j][k].id, 1};
        if (rz == -1 && rx == 4 && (ry == 3 || ry == 4)) return new int[]{grille[i][j][k].id, 3};
        if (rx == 9 && rz == 4 && (ry == 3 || ry == 4)) return new int[]{grille[i][j][k].id, 0};
        if (rz == 9 && rx == 4 && (ry == 3 || ry == 4)) return new int[]{grille[i][j][k].id, 2};
        if (rx == 4 && rz == 4 && (ry == 8 || ry == 9)) return new int[]{grille[i][j][k].id, 4};
        if (rx == 4 && rz == 4 && ry == 0) return new int[]{grille[i][j][k].id, 5};
        return null;
    }
    /** Remplace le sol intérieur (7×7) ; renvoie ce qu'il y avait pour le remettre. */
    public Map<Block, BlockData> sol(World w, Salle s, Material m, boolean aussiPlafondDessous) {
        Map<Block, BlockData> avant = new LinkedHashMap<>();
        int ox = s.ox(), oy = s.oy(), oz = s.oz();
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) {
            if (x == 4 && z == 4) continue;                                   // le pied de l'échelle reste : un refuge d'un bloc
            Block b = w.getBlockAt(ox + x, oy, oz + z); avant.put(b, b.getBlockData()); b.setType(m, false);
            if (aussiPlafondDessous) for (int dy = 1; dy <= 2; dy++) { Block c = w.getBlockAt(ox + x, oy - dy, oz + z); avant.put(c, c.getBlockData()); c.setType(m, false); }
        }
        return avant;
    }
    public static void remettre(Map<Block, BlockData> avant) { for (Map.Entry<Block, BlockData> e : avant.entrySet()) e.getKey().setBlockData(e.getValue(), false); }
}
