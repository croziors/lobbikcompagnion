package hk.krp.reseau;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.*;

/**
 * Le décor commun du réseau, version « propre et moderne » (Kripy, 27/09/2026 : « pas un mode féerique, un truc clean
 * moderne ») aux couleurs Volt de Lobbik : sol noir carbone, lignes de lumière vert électrique et cyan, verre clair,
 * cuivre oxydé. Un disque flottant (le hub) avec son faisceau cyan au centre, le salon pour discuter, les écrans, les
 * portes-cadres lumineuses, les allées vitrées de la file d'attente, deux ponts, et le vestibule blanc du Cube.
 * Tout est déterministe : les trois serveurs posent exactement les mêmes blocs aux mêmes endroits.
 */
final class Decor {
    private final World w;
    Decor(World w) { this.w = w; }

    private void b(int x, int y, int z, Material m) { w.getBlockAt(x, y, z).setType(m, false); }
    private void b(int x, int y, int z, BlockData d) { w.getBlockAt(x, y, z).setBlockData(d, false); }

    static final int R = 36;                 // rayon du disque
    static final int CERCLE_Z = Geo.HUB_Z - 21, CERCLE_R = 8;

    /* ------------------------------------------------------------------ tout */
    void toutCommun() {
        nettoyer();
        disque(); centre(); salon(); arrivee(); ecrans(); murEcrans(); porteAtelier();
        ponts();
        allees(true); allees(false);
        porte(Geo.PORTE_TOUR_X, true); porte(Geo.PORTE_CUBE_X, false);
        vestibule();
    }

    /** Efface l'ancien décor (de l'île du hub aux abords du Cube), sans toucher à la Tour ni au Cube. */
    private void nettoyer() {
        for (int x = Geo.VESTIBULE_X1 - 1; x <= -30; x++) for (int z = -95; z <= 95; z++) {
            if (Math.hypot(x - Geo.TOUR_X, z - Geo.TOUR_Z) <= Geo.TOUR_BASE + 1.5) continue;
            for (int y = 20; y <= 125; y++) { Block k = w.getBlockAt(x, y, z); if (!k.getType().isAir()) k.setType(Material.AIR, false); }
        }
    }

    /* ------------------------------------------------------------------ le disque */
    private void disque() {
        int cx = Geo.HUB_X, cz = Geo.HUB_Z;
        for (int x = cx - R; x <= cx + R; x++) for (int z = cz - R; z <= cz + R; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > R + 0.5) continue;
            int dx = x - cx, dz = z - cz;
            Material sol;
            if (d > R - 0.5) sol = Material.SEA_LANTERN;                                        // anneau lumineux du bord
            else if ((dz == 0 && Math.abs(dx) >= 7) || (dx == 0 && Math.abs(dz) >= 7)) sol = Material.VERDANT_FROGLIGHT;   // lignes vertes vers les quatre directions
            else if (Math.abs(d - 13) < 0.5 || Math.abs(d - 26) < 0.5) sol = Material.WAXED_OXIDIZED_CUT_COPPER;           // cercles cyan-vert, discrets
            else sol = (Math.floorDiv(dx, 4) + Math.floorDiv(dz, 4)) % 2 == 0 ? Material.BLACK_CONCRETE : Material.POLISHED_DEEPSLATE;   // dalles « carbone »
            b(x, 63, z, sol);
            // dessous : un cône net qui s'affine, cerclé de lumière tous les 6 blocs
            int prof = (int) Math.round((R + 0.5 - d) * 0.8) + 2;
            for (int k = 1; k <= prof; k++) {
                int y = 63 - k;
                boolean surface = d > R - 1.5 - k / 0.8;
                b(x, y, z, surface && k % 6 == 0 ? Material.VERDANT_FROGLIGHT : Material.BLACK_CONCRETE);
            }
            if (d < 0.8) for (int k = 1; k <= 6; k++) { org.bukkit.block.data.Directional e = (org.bukkit.block.data.Directional) Bukkit.createBlockData(Material.END_ROD); e.setFacing(BlockFace.DOWN); b(x, 63 - prof - k, z, e); }
        }
        // rambarde de verre sur tout le tour, ouverte vers les deux ponts
        for (int x = cx - R - 1; x <= cx + R + 1; x++) for (int z = cz - R - 1; z <= cz + R + 1; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > R + 0.5 || d <= R - 0.5) continue;
            if (Math.abs(z - cz) <= Geo.PONT_DEMI + 1 && Math.abs(x - cx) > R - 4) continue;   // sorties est et ouest
            if (z < cz - 1 && ((x >= Geo.PORTE_TOUR_X - 16) || (x <= Geo.PORTE_CUBE_X + 16))) continue;   // allées de la file (au nord de chaque porte)
            vitre(x, 64, z);
        }
        relierVitres(cx - R - 2, cx + R + 2, cz - R - 2, cz + R + 2, 64);
    }
    private void vitre(int x, int y, int z) { b(x, y, z, Material.GLASS_PANE); }
    private void relierVitres(int x1, int x2, int z1, int z2, int y) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            Block k = w.getBlockAt(x, y, z);
            if (!(k.getBlockData() instanceof MultipleFacing f) || !(k.getType() == Material.GLASS_PANE || k.getType().name().endsWith("_FENCE"))) continue;
            for (BlockFace fc : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Material v = w.getBlockAt(x + fc.getModX(), y, z + fc.getModZ()).getType();
                f.setFace(fc, v == k.getType() || v.isOccluding());
            }
            k.setBlockData(f, false);
        }
    }

    /** Au centre : une estrade, un anneau de lampes de cuivre, et le faisceau cyan d'une balise, visible de la Tour et du Cube. */
    private void centre() {
        int cx = Geo.HUB_X, cz = Geo.HUB_Z;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) b(x, 62, z, Material.IRON_BLOCK);   // socle de la balise (caché)
        b(cx, 63, cz, Material.BEACON);
        for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 5; z <= cz + 5; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > 5.5) continue;
            if (x == cx && z == cz) { b(x, 64, z, Material.CYAN_STAINED_GLASS); continue; }
            b(x, 64, z, d > 4.5 ? Material.POLISHED_BLACKSTONE_SLAB : Material.POLISHED_BLACKSTONE);
            if (d > 3.5 && d <= 4.5 && (x + z) % 2 == 0) { CopperBulb c = (CopperBulb) Bukkit.createBlockData(Material.WAXED_OXIDIZED_COPPER_BULB); c.setLit(true); b(x, 64, z, c); }
        }
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) if (x != cx || z != cz) b(x, 64, z, Material.TINTED_GLASS);
    }

    /** Le salon (au nord) : l'endroit pour se retrouver et parler — bancs blancs en cercle, jardinières, table lumineuse. */
    private void salon() {
        int cx = Geo.HUB_X, cz = CERCLE_Z;
        for (int x = cx - CERCLE_R; x <= cx + CERCLE_R; x++) for (int z = cz - CERCLE_R; z <= cz + CERCLE_R; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > CERCLE_R + 0.5) continue;
            b(x, 63, z, d > CERCLE_R - 0.5 ? Material.WAXED_OXIDIZED_CUT_COPPER : Material.SMOOTH_QUARTZ);
            if (d > 5.2 && d < 6.3) {
                boolean passage = Math.abs(x - cx) <= 1 && z > cz;   // ouverture côté centre
                if (passage) continue;
                Stairs s = (Stairs) Bukkit.createBlockData(Material.SMOOTH_QUARTZ_STAIRS);
                double a = Math.atan2(z - cz, x - cx);
                s.setFacing(Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? BlockFace.EAST : BlockFace.WEST) : (Math.sin(a) > 0 ? BlockFace.SOUTH : BlockFace.NORTH));
                b(x, 64, z, s);
            }
        }
        // table basse lumineuse au centre
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) b(x, 64, z, x == cx && z == cz ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE_SLAB);
        // jardinières carrées aux quatre coins
        for (int[] c : new int[][]{{7, 7}, {-7, 7}, {7, -7}, {-7, -7}}) jardiniere(cx + c[0], cz + c[1]);
    }
    private void jardiniere(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) b(x + dx, 64, z + dz, dx == 0 && dz == 0 ? Material.MOSS_BLOCK : Material.BLACK_CONCRETE);
        b(x, 65, z, Material.FLOWERING_AZALEA);
    }
    /** L'arrivée (au sud) : un rond cyan, face au centre. */
    private void arrivee() {
        int cx = Geo.HUB_X, cz = Geo.HUB_Z + 18;
        for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > 4.5) continue;
            b(x, 63, z, d > 3.5 ? Material.SEA_LANTERN : d < 1 ? Material.PEARLESCENT_FROGLIGHT : Material.SMOOTH_QUARTZ);
        }
    }
    /** Deux écrans noirs à l'arrivée, de part et d'autre de l'allée (le texte y est affiché par Panneaux). */
    private void ecrans() {
        for (int s : new int[]{-1, 1}) {
            int x1 = Geo.HUB_X + s * 4, x2 = Geo.HUB_X + s * 13;
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int y = 64; y <= 70; y++) {
                boolean bord = x == x1 || x == x2 || y == 70;
                b(x, y, Geo.HUB_Z + 11, bord ? Material.VERDANT_FROGLIGHT : Material.BLACK_CONCRETE);
            }
        }
    }

    /** Le mur d'écrans en direct, au nord du salon : 16 × 7 cadres (posés par Ecrans), bordé de lumière verte. */
    private void murEcrans() {
        int z = Ecrans.Z_CADRES - 1;
        for (int x = Ecrans.X0 - 1; x <= Ecrans.X0 + Ecrans.COLS; x++) for (int y = 63; y <= Ecrans.Y_HAUT + 1; y++) {
            boolean bord = x == Ecrans.X0 - 1 || x == Ecrans.X0 + Ecrans.COLS || y == Ecrans.Y_HAUT + 1 || y == Ecrans.Y_HAUT - Ecrans.LIGNES;
            b(x, y, z, y == 63 ? Material.BLACK_CONCRETE : bord ? Material.VERDANT_FROGLIGHT : Material.BLACK_CONCRETE);
            b(x, y, z - 1, Material.BLACK_CONCRETE);
        }
        for (int x = Ecrans.X0; x < Ecrans.X0 + Ecrans.COLS; x++) for (int y = Ecrans.Y_HAUT - Ecrans.LIGNES + 1; y <= Ecrans.Y_HAUT; y++) b(x, y, Ecrans.Z_CADRES, Material.AIR);
    }

    /** La porte de l'Atelier (serveur moddé CC: Tweaked) : cadre de cuivre et lumière ocre, au sud de l'arrivée. */
    private void porteAtelier() {
        int z = Geo.ATELIER_Z, cx = Geo.HUB_X;
        for (int x = cx - 3; x <= cx + 3; x++) for (int y = 63; y <= Geo.PORTE_HAUT + 2; y++) {
            boolean cadre = Math.abs(x - cx) == 3 || y == Geo.PORTE_HAUT + 1 || y == Geo.PORTE_HAUT + 2 || y == 63;
            b(x, y, z, !cadre ? Material.AIR : (y == Geo.PORTE_HAUT + 2 && Math.abs(x - cx) <= 2) || (Math.abs(x - cx) == 3 && y > 63 && y <= Geo.PORTE_HAUT) ? Material.OCHRE_FROGLIGHT : Material.WAXED_CUT_COPPER);
        }
        for (int zz = z - 3; zz < z; zz++) for (int x = cx - 2; x <= cx + 2; x++) b(x, 63, zz, x == cx ? Material.OCHRE_FROGLIGHT : Material.WAXED_CUT_COPPER);
    }

    /* ------------------------------------------------------------------ ponts */
    private void ponts() {
        pont(Geo.HUB_X + R - 2, Geo.PONT_TOUR_FIN, Material.VERDANT_FROGLIGHT);
        pont(Geo.VESTIBULE_X2, Geo.HUB_X - R + 2, Material.SEA_LANTERN);
    }
    private void pont(int xa, int xb, Material ligne) {
        for (int x = Math.min(xa, xb); x <= Math.max(xa, xb); x++) {
            if (Math.hypot(x - Geo.TOUR_X, Geo.TOUR_Z) <= Geo.TOUR_BASE + 0.4) continue;   // esplanade de la Tour
            for (int z = -Geo.PONT_DEMI - 1; z <= Geo.PONT_DEMI + 1; z++) {
                if (Geo.distHub(x, z) <= R - 0.5) continue;
                if (Math.hypot(x - Geo.TOUR_X, z - Geo.TOUR_Z) <= Geo.TOUR_BASE + 0.4) continue;
                b(x, 63, z, z == 0 ? ligne : Material.BLACK_CONCRETE);
                b(x, 62, z, Material.POLISHED_BLACKSTONE_SLAB);
                if (Math.abs(z) == Geo.PONT_DEMI + 1) { vitre(x, 64, z); if (Math.floorMod(x, 8) == 0) { b(x, 64, z, Material.BLACK_CONCRETE); b(x, 65, z, Material.END_ROD); } }
            }
        }
        relierVitres(Math.min(xa, xb) - 1, Math.max(xa, xb) + 1, -Geo.PONT_DEMI - 2, Geo.PONT_DEMI + 2, 64);
    }

    /* ------------------------------------------------------------------ portes-cadres (le mur coloré est affiché par le plugin) */
    private void porte(int px, boolean tour) {
        Material lumiere = tour ? Material.VERDANT_FROGLIGHT : Material.SEA_LANTERN;
        for (int z : new int[]{-3, 3}) { for (int y = 63; y <= Geo.PORTE_HAUT + 1; y++) b(px, y, z, y == 63 || y == Geo.PORTE_HAUT + 1 ? Material.BLACK_CONCRETE : lumiere); }
        for (int z = -3; z <= 3; z++) { b(px, Geo.PORTE_HAUT + 1, z, Material.BLACK_CONCRETE); b(px, Geo.PORTE_HAUT + 2, z, Math.abs(z) <= 2 ? lumiere : Material.BLACK_CONCRETE); }
        for (int y = 64; y <= Geo.PORTE_HAUT; y++) for (int z = -2; z <= 2; z++) b(px, y, z, Material.AIR);   // passage libre côté serveur
        for (int z = -2; z <= 2; z++) b(px, 63, z, lumiere);
    }

    /* ------------------------------------------------------------------ allées de la file d'attente */
    /** Allées : 5 couloirs en zigzag au nord de chaque porte ; le couloir du pont (z de −1 à 2) reste libre pour passer et revenir. */
    static final int ALLEE_LONG = 13, ALLEE_Z0 = -2, ALLEE_NB = 5;
    /** Le chemin de la file d'attente case par case, de la porte vers l'entrée (la case 0 touche la porte). */
    static List<int[]> chemin(boolean tour) {
        List<int[]> c = new ArrayList<>();
        int s = tour ? 1 : -1, porte = tour ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X;
        int proche = porte - s, loin = porte - s * ALLEE_LONG;
        for (int n = 0; n < ALLEE_NB; n++) {
            int z = ALLEE_Z0 - 2 * n;
            boolean versLoin = n % 2 == 0;
            for (int k = 0; k < ALLEE_LONG; k++) c.add(new int[]{versLoin ? proche - s * k : loin + s * k, z});
            if (n < ALLEE_NB - 1) c.add(new int[]{versLoin ? loin : proche, z - 1});
        }
        return c;
    }
    static int[] entree(boolean tour) { List<int[]> c = chemin(tour); int[] f = c.get(c.size() - 1); int s = tour ? 1 : -1; return new int[]{f[0] - s, f[1]}; }
    private void allees(boolean tour) {
        List<int[]> ch = chemin(tour);
        Set<Long> dans = new HashSet<>();
        for (int[] p : ch) dans.add(cle(p[0], p[1]));
        int[] ent = entree(tour); dans.add(cle(ent[0], ent[1]));
        int s = tour ? 1 : -1, porte = tour ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X;
        int xa = porte - s, xb = porte - s * (ALLEE_LONG + 1);
        int xmin = Math.min(xa, xb), xmax = Math.max(xa, xb), zmin = ALLEE_Z0 - 2 * (ALLEE_NB - 1) - 1, zmax = ALLEE_Z0 + 1;
        Material ligne = tour ? Material.VERDANT_FROGLIGHT : Material.SEA_LANTERN;
        for (int x = xmin; x <= xmax; x++) for (int z = zmin; z <= zmax; z++) {
            boolean chemin = dans.contains(cle(x, z));
            b(x, 63, z, chemin ? (Math.floorMod(x + z, 6) == 0 ? ligne : Material.POLISHED_DEEPSLATE) : Material.BLACK_CONCRETE);
            b(x, 62, z, Material.BLACK_CONCRETE);
            for (int y = 64; y <= 66; y++) b(x, y, z, Material.AIR);
            if (!chemin) vitre(x, 64, z);   // cloisons de verre
        }
        relierVitres(xmin - 1, xmax + 1, zmin - 1, zmax + 1, 64);
    }
    private static long cle(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    /* ------------------------------------------------------------------ vestibule du Cube */
    private void vestibule() {
        int d = Geo.VESTIBULE_DEMI;
        for (int x = Geo.VESTIBULE_X1; x <= Geo.VESTIBULE_X2; x++) for (int z = -d; z <= d; z++) for (int y = 63; y <= 69; y++) {
            boolean paroi = x == Geo.VESTIBULE_X1 || x == Geo.VESTIBULE_X2 || Math.abs(z) == d || y == 63 || y == 69;
            boolean entree = x == Geo.VESTIBULE_X2 && Math.abs(z) <= 1 && y >= 64 && y <= 66;
            b(x, y, z, !paroi || entree ? Material.AIR : Material.WHITE_CONCRETE);
        }
        for (int z = -d + 1; z <= d - 1; z++) for (int x = Geo.VESTIBULE_X1 + 1; x < Geo.VESTIBULE_X2; x++) b(x, 69, z, (x + z) % 3 == 0 ? Material.SEA_LANTERN : Material.WHITE_CONCRETE);
    }
}
