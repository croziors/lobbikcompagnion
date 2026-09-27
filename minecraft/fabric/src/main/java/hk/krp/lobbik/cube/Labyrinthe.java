package hk.krp.lobbik.cube;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/** Le Cube : une grille de salles cubiques (intérieur 7×7×7, murs de 1 bloc propres à chaque salle, pas de 10 blocs),
 *  reliées par une porte de fer au milieu de chaque face et par l'échelle centrale (trappes haut/bas).
 *  Depuis le 23/09/2026 au soir (Maxime : « une salle tue parfois et parfois non, c'est incohérent »), comme dans le film,
 *  c'est la SALLE qui tue : le plan (voir Plan) désigne les salles sûres, qui forment un labyrinthe 3D ; toute autre salle est
 *  mortelle, par quelque porte qu'on y entre. Aucun numéro, aucune indication : couleur, éclairage, décor ne disent rien.
 *  Génération déterministe (graine). Port Fabric du plugin KrpCube (27/09/2026) : blocs vanilla posés sans mise à jour des voisins
 *  (échelles sans mur, portes coupées en deux moitiés : tout tient parce que rien ne recalcule leur forme). */
public final class Labyrinthe {
    public static final int P = 10, Y0 = 64;   // 7 d'intérieur + 2 murs (un par cube) + 1 bloc de sas partagé
    /** Origine du Cube : (−300, −50) dans le réseau Lobbik (réglages « cube.origine_x / cube.origine_z »), à côté du hub. */
    public static int X0 = 0, Z0 = 0;
    public enum Piege { AUCUN, LAMES, GAZ, FLAMMES, ACIDE, CHUTE }
    /** issues : 0 +x, 1 −x, 2 +z, 3 −z, 4 haut, 5 bas */
    public static final int[] DX = Plan.DX, DY = Plan.DY, DZ = Plan.DZ;

    public static final class Salle {
        public final int i, j, k, id;
        public int couleur;
        public Piege piege = Piege.AUCUN;               // salle mortelle : la manière dont elle tue (AUCUN = salle sûre)
        public boolean sure, depart, sortie, chemin;    // sure : dans le labyrinthe des salles sûres ; sinon elle tue, toujours ; chemin : sur la route départ → sortie
        public String code = "";                        // numéro façon film « 517 · 244 · 831 » : haché de l'id et de la graine, sans lien avec le danger ni les coordonnées
        public final boolean[] portes = new boolean[6];  // une porte existe (toujours, même au bord : elle donne sur le vide)
        public final boolean[] passage = new boolean[6]; // la salle voisine par cette issue est sûre elle aussi (et celle-ci aussi)
        Salle(int i, int j, int k, int id) { this.i = i; this.j = j; this.k = k; this.id = id; }
        public int ox() { return X0 + i * P; } public int oy() { return Y0 + j * P; } public int oz() { return Z0 + k * P; }
        /** au pied de l'échelle centrale, regard vers le sud (comme la Location du plugin, lacet 0) */
        public Vec3 centre() { return new Vec3(ox() + 4.5, oy() + 1, oz() + 4.5); }
        public String coord() { return "[" + i + "," + j + "," + k + "]"; }
    }

    // 0 blanc (départ/sortie), 1 bleu, 2 orange, 3 brun, 4 vert, 5 rouge — tirées au hasard, deux voisines peuvent se ressembler
    // Maxime : « des cubes plus lisses, la laine », « le cube de toute la même couleur » → sol, murs et plafond en laine identique
    static final String[] LAINE = {"white_wool", "blue_wool", "orange_wool", "brown_wool", "green_wool", "red_wool"};
    public static final String[] NOMS = {"blanche", "bleue", "orange", "brune", "verte", "rouge"};
    public static final int[] TEINTES = {0xFFFFFF, 0x5555FF, 0xFFAA00, 0xAA0000, 0x55FF55, 0xFF5555};

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
    /** Directions en clair pour les commandes admin : 0 +x est, 1 −x ouest, 2 +z sud, 3 −z nord, 4 haut, 5 bas. */
    public static final String[] DIRECTIONS = {"est", "ouest", "sud", "nord", "haut", "bas"};
    /** Code unique à trois nombres de 3 chiffres (100-999), déterministe (graine + id + tentative en cas de doublon). */
    private static String codePour(long graine, int id, int essai) {
        long h = graine * 0x9E3779B97F4A7C15L + (id + 1) * 0xBF58476D1CE4E5B9L + essai * 0x94D049BB133111EBL;
        h ^= h >>> 31; h *= 0x7FB5D329728EA185L; h ^= h >>> 27; h *= 0x81DADEF4BC2DD44DL; h ^= h >>> 33;
        return (100 + Math.floorMod(h, 900)) + " · " + (100 + Math.floorMod(h >>> 21, 900)) + " · " + (100 + Math.floorMod(h >>> 42, 900));
    }
    public int nbMortelles() { int n = 0; for (Salle s : salles) if (!s.sure) n++; return n; }

    /** La salle dont l'intérieur contient ce point (null dans un mur, une porte ou hors du Cube). */
    public Salle salleDe(double px, double py, double pz) {
        int x = (int) Math.floor(px) - X0, y = (int) Math.floor(py) - Y0, z = (int) Math.floor(pz) - Z0;
        if (x < 0 || y < 0 || z < 0) return null;
        int i = x / P, j = y / P, k = z / P;
        if (i >= nx || j >= ny || k >= nz) return null;
        int rx = x % P, ry = y % P, rz = z % P;
        if (rx < 1 || rx > 7 || rz < 1 || rz > 7 || ry < 1 || ry > 7) return null;
        return grille[i][j][k];
    }
    public Salle salleDe(Vec3 v) { return salleDe(v.x, v.y, v.z); }

    /* ------------------------------------------------------------------ blocs vanilla */
    private static final int SANS_VOISINS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;   // comme setType(m, false) de Bukkit : rien ne recalcule la forme des voisins
    private static final Map<String, BlockState> ETATS = new HashMap<>();
    /** état par défaut d'un bloc vanilla par son nom (« white_wool ») — par le registre : certains blocs colorés n'ont plus de constante simple en 26.x */
    public static BlockState etat(String id) { return ETATS.computeIfAbsent(id, k -> BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(k)).defaultBlockState()); }
    static void bloc(ServerLevel w, int x, int y, int z, String id) { w.setBlock(new BlockPos(x, y, z), etat(id), SANS_VOISINS); }
    static void bloc(ServerLevel w, int x, int y, int z, BlockState st) { w.setBlock(new BlockPos(x, y, z), st, SANS_VOISINS); }

    /* ------------------------------------------------------------------ construction */
    /** Tout d'un coup (hors du jeu : la construction en jeu passe par etapes(), quelques salles par tick). */
    public void construire(ServerLevel w) { for (Runnable r : etapes(w, true, HABILLAGE)) r.run(); }
    /**
     * La construction découpée en étapes courtes (une salle chacune) : 1 000 salles × 1 000 blocs d'un seul tenant dépasseraient
     * la minute du chien de garde du serveur. La salle blanche d'abord (on peut y poser les joueurs aussitôt), puis les autres,
     * puis les portes toutes fermées, le décor du départ et de la sortie, et l'habillage extérieur.
     */
    public List<Runnable> etapes(ServerLevel w, boolean coques, boolean habillage) {
        List<Runnable> l = new ArrayList<>();
        if (coques) {
            l.add(() -> coque(w, depart));
            for (Salle s : salles) if (s != depart) l.add(() -> coque(w, s));
            l.add(() -> { for (Salle s : salles) ouvrir(w, s, false); });
            l.add(() -> decorDepartSortie(w));
        }
        if (habillage) l.addAll(etapesHabillage(w));
        return l;
    }

    /** Réseau Lobbik (27/09/2026, Kripy : « le contour de chaque salle en couleur, même si ce n'est pas la vraie couleur dedans »). */
    public static boolean HABILLAGE = false;
    private static final String[] TUILES = {"lime_concrete", "cyan_concrete", "magenta_concrete", "orange_concrete", "yellow_concrete",
        "light_blue_concrete", "pink_concrete", "purple_concrete", "red_concrete", "white_concrete"};
    /**
     * Vu de dehors, chaque salle du bord se voit : une tuile de couleur (au hasard, sans lien avec l'intérieur) cernée d'un
     * contour lumineux. Posé sur la couche extérieure seulement — le sas (faces +x, +z, dessus) ou une peau d'un bloc
     * (faces −x, −z, dessous) —, jamais sur un mur que l'on voit de l'intérieur ; portes et trappes du bord intactes.
     * Les couleurs sont tirées dans le même ordre que le plugin (même Cube vu de dehors), puis posées une salle par étape.
     */
    public List<Runnable> etapesHabillage(ServerLevel w) {
        Random r = new Random(graine * 17 + 3);
        List<Runnable> l = new ArrayList<>();
        for (Salle s : salles) {
            String[] tuiles = new String[6]; boolean bord = false;
            for (int d = 0; d < 6; d++) if (voisin(s, d) == null) { tuiles[d] = TUILES[r.nextInt(TUILES.length)]; bord = true; }
            if (bord) l.add(() -> habiller(w, s, tuiles));
        }
        return l;
    }
    private void habiller(ServerLevel w, Salle s, String[] tuiles) {
        int ox = s.ox(), oy = s.oy(), oz = s.oz();
        for (int d = 0; d < 6; d++) {
            if (tuiles[d] == null) continue;
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
                bloc(w, x, y, z, bord ? "sea_lantern" : tuiles[d]);
            }
        }
    }

    void coque(ServerLevel w, Salle s) {
        int ox = s.ox(), oy = s.oy(), oz = s.oz(); BlockState mur = etat(LAINE[s.couleur]), sas = etat("gray_concrete"), air = etat("air");
        // le sas : toute la cellule 10×10×10 en gris sombre, puis le cube 9×9×9 de la couleur de la salle, puis l'intérieur vide
        for (int x = 0; x <= 9; x++) for (int y = 0; y <= 9; y++) for (int z = 0; z <= 9; z++) {
            boolean estSas = x == 9 || y == 9 || z == 9;
            boolean bord = x == 0 || x == 8 || y == 0 || y == 8 || z == 0 || z == 8;
            bloc(w, ox + x, oy + y, oz + z, estSas ? sas : bord ? mur : air);
        }
        for (int[] c : new int[][]{{2, 2}, {6, 2}, {2, 6}, {6, 6}}) bloc(w, ox + c[0], oy + 6, oz + c[1], "light");   // éclairage invisible, normal (niveau 15)
        for (int y = 1; y <= 7; y++) echelle(w, ox + 4, oy + y, oz + 4, Direction.EAST);   // l'échelle au centre, du sol au plafond, sans mur (posée sans mise à jour : elle tient)
        // quatre faces : ouverture au centre du mur (rangées 3-4), porte de fer dans le bloc de sas, deux barreaux d'échelle sous l'ouverture
        for (int d = 0; d < 4; d++) {
            int mx = d == 0 ? ox + 8 : d == 1 ? ox : ox + 4, mz = d == 2 ? oz + 8 : d == 3 ? oz : oz + 4;        // le mur de la salle
            bloc(w, mx, oy + 3, mz, air); bloc(w, mx, oy + 4, mz, air);                                            // ouverture dans le mur
            Direction face = d == 0 ? Direction.WEST : d == 1 ? Direction.EAST : d == 2 ? Direction.NORTH : Direction.SOUTH;
            echelle(w, mx - DX[d], oy + 1, mz - DZ[d], face); echelle(w, mx - DX[d], oy + 2, mz - DZ[d], face);
            int sx = mx + DX[d], sz = mz + DZ[d];                                                                    // UNE seule porte, au milieu, dans le bloc de passage (Maxime)
            porte(w, sx, oy + 3, sz, DX[d] != 0 ? Direction.EAST : Direction.SOUTH, false);
        }
        // plafond et sol : trappe au-dessus de l'échelle (plafond + sas), trappe au pied de l'échelle (plancher)
        trappe(w, ox + 4, oy + 8, oz + 4, Half.BOTTOM); trappe(w, ox + 4, oy + 9, oz + 4, Half.BOTTOM); trappe(w, ox + 4, oy, oz + 4, Half.TOP);
        if (voisin(s, 5) == null) trappe(w, ox + 4, oy - 1, oz + 4, Half.BOTTOM);   // tout en bas : la trappe de sas donne sur le vide
    }
    private static void echelle(ServerLevel w, int x, int y, int z, Direction face) { bloc(w, x, y, z, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, face)); }
    private static void trappe(ServerLevel w, int x, int y, int z, Half moitie) {
        bloc(w, x, y, z, Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.NORTH).setValue(TrapDoorBlock.HALF, moitie).setValue(TrapDoorBlock.OPEN, false));
    }
    private static BlockState battant(Direction face, boolean haut, boolean ouverte) {
        return Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, face).setValue(DoorBlock.HALF, haut ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, ouverte).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
    }
    private static void porte(ServerLevel w, int x, int y, int z, Direction face, boolean ouverte) {
        bloc(w, x, y, z, battant(face, false, ouverte)); bloc(w, x, y + 1, z, battant(face, true, ouverte));
    }
    void decorDepartSortie(ServerLevel w) {
        Salle s = sortie; int ox = s.ox(), oy = s.oy(), oz = s.oz();
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) if (x != 4 || z != 4) bloc(w, ox + x, oy + 8, oz + z, "glowstone");   // plafond de lumière
        for (int y = 1; y <= 7; y++) for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) bloc(w, ox + c[0], oy + y, oz + c[1], "gold_block");
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) if (x != 4 || z != 4) bloc(w, ox + x, oy, oz + z, "gold_block");
        s = depart; ox = s.ox(); oy = s.oy(); oz = s.oz();
        for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) for (int y = 1; y <= 7; y++) bloc(w, ox + c[0], oy + y, oz + c[1], "quartz_pillar");
        bloc(w, ox + 4, oy, oz + 4, "white_glazed_terracotta");
    }

    /** Ouvre ou ferme toutes les issues d'une salle. */
    public void ouvrir(ServerLevel w, Salle s, boolean ouvert) { for (int d = 0; d < 6; d++) if (s.portes[d]) basculer(w, s, d, ouvert); }
    /** La porte d'une issue latérale : unique, dans le bloc de passage entre les deux cubes (x, z). */
    private int[] murDe(Salle s, int d) {
        int ox = s.ox(), oz = s.oz();
        return switch (d) { case 0 -> new int[]{ox + 9, oz + 4}; case 1 -> new int[]{ox - 1, oz + 4}; case 2 -> new int[]{ox + 4, oz + 9}; default -> new int[]{ox + 4, oz - 1}; };
    }
    /** L'issue d de la salle s est-elle ouverte ? (une porte du bord s'ouvre aussi : elle donne sur le vide) */
    public boolean ouverte(ServerLevel w, Salle s, int d) {
        if (d < 4) { int[] c = murDe(s, d); BlockState st = w.getBlockState(new BlockPos(c[0], s.oy() + 3, c[1])); return st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.OPEN); }
        return w.getBlockState(new BlockPos(s.ox() + 4, d == 4 ? s.oy() + 8 : s.oy(), s.oz() + 4)).is(Blocks.LADDER);
    }
    /** Ouvre ou ferme UNE issue : la porte du sas (côtés) ou le puits vertical (plafond, sas, plancher du dessus). */
    public void basculer(ServerLevel w, Salle s, int d, boolean ouvert) {
        Salle v = voisin(s, d);
        if (d < 4) {   // la porte unique du passage
            Direction f = DX[d] != 0 ? Direction.EAST : Direction.SOUTH;
            int[] c = murDe(s, d);
            for (int h = 0; h < 2; h++) {
                BlockPos b = new BlockPos(c[0], s.oy() + 3 + h, c[1]); BlockState st = w.getBlockState(b);
                w.setBlock(b, st.getBlock() instanceof DoorBlock ? st.setValue(DoorBlock.OPEN, ouvert) : battant(f, h == 1, ouvert), SANS_VOISINS);
            }
        } else {
            int x = s.ox() + 4, z = s.oz() + 4;   // le puits est au centre, dans l'axe de l'échelle : ouvert = l'échelle continue, fermé = trappes de fer
            int[] ys = d == 4 ? (v != null ? new int[]{s.oy() + 8, s.oy() + 9, s.oy() + 10} : new int[]{s.oy() + 8, s.oy() + 9})
                              : (v != null ? new int[]{s.oy(), s.oy() - 1, s.oy() - 2} : new int[]{s.oy(), s.oy() - 1});
            for (int n = 0; n < ys.length; n++) {
                if (ouvert) echelle(w, x, ys[n], z, Direction.EAST);
                else trappe(w, x, ys[n], z, (d == 4 ? n == 2 : n == 0) ? Half.TOP : Half.BOTTOM);
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
}
