package hk.krp.lobbik;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Le décor commun, version « propre et moderne » (Volt) — port Fabric du Decor Paper (27/09/2026) : disque flottant du hub,
 * balise cyan, salon, écrans, ponts, portes-cadres, allées vitrées de la file d'attente, vestibule du Cube, porte d'Oasis.
 * Déterministe : les trois serveurs posent les mêmes blocs aux mêmes endroits.
 */
public final class Decor {
    private final ServerLevel w;
    public Decor(ServerLevel w) { this.w = w; }

    private static final int SANS_VOISINS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private final Map<String, BlockState> cache = new HashMap<>();
    /** état par défaut d'un bloc vanilla par son nom (« black_concrete ») */
    BlockState s(String id) { return cache.computeIfAbsent(id, k -> BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(k)).defaultBlockState()); }
    void b(int x, int y, int z, String id) { w.setBlock(new BlockPos(x, y, z), s(id), SANS_VOISINS); }
    void b(int x, int y, int z, BlockState st) { w.setBlock(new BlockPos(x, y, z), st, SANS_VOISINS); }
    BlockState get(int x, int y, int z) { return w.getBlockState(new BlockPos(x, y, z)); }

    static final int R = 36;
    static final int CERCLE_Z = Geo.HUB_Z - 21, CERCLE_R = 8;
    public static final int ECRANS_X0 = Geo.HUB_X - 8, ECRANS_COLS = 16, ECRANS_LIGNES = 7, ECRANS_Y_HAUT = 71, ECRANS_Z = Geo.HUB_Z - 30;

    public void toutCommun() {
        nettoyer();
        disque(); centre(); salon(); arrivee(); ecrans(); murEcrans(); porteOasis();
        ponts();
        allees(true); allees(false);
        porte(Geo.PORTE_TOUR_X, true); porte(Geo.PORTE_CUBE_X, false);
        pontLave();
        vestibule();
    }

    private void nettoyer() {
        BlockState air = s("air");
        for (int x = Geo.VESTIBULE_X1 - 1; x <= -30; x++) for (int z = -95; z <= 95; z++) {
            if (Math.hypot(x - Geo.TOUR_X, z - Geo.TOUR_Z) <= Geo.TOUR_BASE + 1.5) continue;
            for (int y = 20; y <= 125; y++) { BlockPos p = new BlockPos(x, y, z); if (!w.getBlockState(p).isAir()) w.setBlock(p, air, SANS_VOISINS); }
        }
    }

    /* ------------------------------------------------------------------ le disque */
    private void disque() {
        int cx = Geo.HUB_X, cz = Geo.HUB_Z;
        for (int x = cx - R; x <= cx + R; x++) for (int z = cz - R; z <= cz + R; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > R + 0.5) continue;
            int dx = x - cx, dz = z - cz;
            String sol;
            if (d > R - 0.5) sol = "sea_lantern";
            else if ((dz == 0 && Math.abs(dx) >= 7) || (dx == 0 && Math.abs(dz) >= 7)) sol = "verdant_froglight";
            else if (Math.abs(d - 13) < 0.5 || Math.abs(d - 26) < 0.5) sol = "waxed_oxidized_cut_copper";
            else sol = (Math.floorDiv(dx, 4) + Math.floorDiv(dz, 4)) % 2 == 0 ? "black_concrete" : "polished_deepslate";
            b(x, 63, z, sol);
            int prof = (int) Math.round((R + 0.5 - d) * 0.8) + 2;
            for (int k = 1; k <= prof; k++) {
                boolean surface = d > R - 1.5 - k / 0.8;
                b(x, 63 - k, z, surface && k % 6 == 0 ? "verdant_froglight" : "black_concrete");
            }
            if (d < 0.8) for (int k = 1; k <= 6; k++) b(x, 63 - prof - k, z, s("end_rod").setValue(EndRodBlock.FACING, Direction.DOWN));
        }
        for (int x = cx - R - 1; x <= cx + R + 1; x++) for (int z = cz - R - 1; z <= cz + R + 1; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > R + 0.5 || d <= R - 0.5) continue;
            if (Math.abs(z - cz) <= Geo.PONT_DEMI + 1 && Math.abs(x - cx) > R - 4) continue;
            if (z < cz - 1 && ((x >= Geo.PORTE_TOUR_X - 16) || (x <= Geo.PORTE_CUBE_X + 16))) continue;
            if (z > cz && Math.abs(x - Geo.LAVE_X) <= Geo.PONT_DEMI + 1) continue;   // ouverture du pont de la Mer de lave (sud)
            b(x, 64, z, "glass_pane");
        }
        relierVitres(cx - R - 2, cx + R + 2, cz - R - 2, cz + R + 2, 64);
    }
    /** vitres et barrières : relie chaque élément à ses voisins de même sorte (les blocs sont posés sans mise à jour des voisins) */
    private void relierVitres(int x1, int x2, int z1, int z2, int y) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            BlockState st = get(x, y, z);
            if (!(st.getBlock() instanceof CrossCollisionBlock)) continue;
            st = st.setValue(CrossCollisionBlock.NORTH, relie(st, x, y, z - 1)).setValue(CrossCollisionBlock.SOUTH, relie(st, x, y, z + 1))
                   .setValue(CrossCollisionBlock.EAST, relie(st, x + 1, y, z)).setValue(CrossCollisionBlock.WEST, relie(st, x - 1, y, z));
            b(x, y, z, st);
        }
    }
    private boolean relie(BlockState soi, int x, int y, int z) { BlockState v = get(x, y, z); return v.getBlock() == soi.getBlock() || v.isSolidRender(); }

    private void centre() {
        int cx = Geo.HUB_X, cz = Geo.HUB_Z;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) b(x, 62, z, "iron_block");
        b(cx, 63, cz, "beacon");
        for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 5; z <= cz + 5; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > 5.5) continue;
            if (x == cx && z == cz) { b(x, 64, z, "cyan_stained_glass"); continue; }
            b(x, 64, z, d > 4.5 ? "polished_blackstone_slab" : "polished_blackstone");
            if (d > 3.5 && d <= 4.5 && (x + z) % 2 == 0) b(x, 64, z, s("waxed_oxidized_copper_bulb").setValue(BlockStateProperties.LIT, true));
        }
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) if (x != cx || z != cz) b(x, 64, z, "tinted_glass");
    }
    private void salon() {
        int cx = Geo.HUB_X, cz = CERCLE_Z;
        for (int x = cx - CERCLE_R; x <= cx + CERCLE_R; x++) for (int z = cz - CERCLE_R; z <= cz + CERCLE_R; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d > CERCLE_R + 0.5) continue;
            b(x, 63, z, d > CERCLE_R - 0.5 ? "waxed_oxidized_cut_copper" : "smooth_quartz");
            if (d > 5.2 && d < 6.3) {
                if (Math.abs(x - cx) <= 1 && z > cz) continue;
                double a = Math.atan2(z - cz, x - cx);
                Direction f = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? Direction.EAST : Direction.WEST) : (Math.sin(a) > 0 ? Direction.SOUTH : Direction.NORTH);
                b(x, 64, z, s("smooth_quartz_stairs").setValue(StairBlock.FACING, f));
            }
        }
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) b(x, 64, z, x == cx && z == cz ? "sea_lantern" : "polished_blackstone_slab");
        for (int[] c : new int[][]{{7, 7}, {-7, 7}, {7, -7}, {-7, -7}}) jardiniere(cx + c[0], cz + c[1]);
    }
    private void jardiniere(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) b(x + dx, 64, z + dz, dx == 0 && dz == 0 ? "moss_block" : "black_concrete");
        b(x, 65, z, "flowering_azalea");
    }
    private void arrivee() {
        int cx = Geo.HUB_X, cz = Geo.HUB_Z + 18;
        for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) {
            double d = Math.hypot(x - cx, z - cz);
            if (d <= 4.5) b(x, 63, z, d > 3.5 ? "sea_lantern" : d < 1 ? "pearlescent_froglight" : "smooth_quartz");
        }
    }
    private void ecrans() {
        for (int s : new int[]{-1, 1}) {
            int x1 = Geo.HUB_X + s * 4, x2 = Geo.HUB_X + s * 13;
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int y = 64; y <= 70; y++)
                b(x, y, Geo.HUB_Z + 11, x == x1 || x == x2 || y == 70 ? "verdant_froglight" : "black_concrete");
        }
    }
    private void murEcrans() {
        int z = ECRANS_Z - 1;
        for (int x = ECRANS_X0 - 1; x <= ECRANS_X0 + ECRANS_COLS; x++) for (int y = 63; y <= ECRANS_Y_HAUT + 1; y++) {
            boolean bord = x == ECRANS_X0 - 1 || x == ECRANS_X0 + ECRANS_COLS || y == ECRANS_Y_HAUT + 1 || y == ECRANS_Y_HAUT - ECRANS_LIGNES;
            b(x, y, z, y == 63 ? "black_concrete" : bord ? "verdant_froglight" : "black_concrete");
            b(x, y, z - 1, "black_concrete");
        }
        for (int x = ECRANS_X0; x < ECRANS_X0 + ECRANS_COLS; x++) for (int y = ECRANS_Y_HAUT - ECRANS_LIGNES + 1; y <= ECRANS_Y_HAUT; y++) b(x, y, ECRANS_Z, "air");
    }
    private void porteOasis() {
        int z = Geo.ATELIER_Z, cx = Geo.HUB_X;
        for (int x = cx - 3; x <= cx + 3; x++) for (int y = 63; y <= Geo.PORTE_HAUT + 2; y++) {
            boolean cadre = Math.abs(x - cx) == 3 || y >= Geo.PORTE_HAUT + 1 || y == 63;
            boolean lumiere = (y == Geo.PORTE_HAUT + 2 && Math.abs(x - cx) <= 2) || (Math.abs(x - cx) == 3 && y > 63 && y <= Geo.PORTE_HAUT);
            b(x, y, z, !cadre ? "air" : lumiere ? "ochre_froglight" : "waxed_cut_copper");
        }
        for (int zz = z - 3; zz < z; zz++) for (int x = cx - 2; x <= cx + 2; x++) b(x, 63, zz, x == cx ? "ochre_froglight" : "waxed_cut_copper");
    }

    /* ------------------------------------------------------------------ ponts */
    private void ponts() {
        pont(Geo.HUB_X + R - 2, Geo.PONT_TOUR_FIN, "verdant_froglight");
        pont(Geo.VESTIBULE_X2, Geo.HUB_X - R + 2, "sea_lantern");
    }
    private void pont(int xa, int xb, String ligne) {
        for (int x = Math.min(xa, xb); x <= Math.max(xa, xb); x++) {
            if (Math.hypot(x - Geo.TOUR_X, Geo.TOUR_Z) <= Geo.TOUR_BASE + 0.4) continue;
            for (int z = -Geo.PONT_DEMI - 1; z <= Geo.PONT_DEMI + 1; z++) {
                if (Geo.distHub(x, z) <= R - 0.5) continue;
                if (Math.hypot(x - Geo.TOUR_X, z - Geo.TOUR_Z) <= Geo.TOUR_BASE + 0.4) continue;
                b(x, 63, z, z == 0 ? ligne : "black_concrete");
                b(x, 62, z, "polished_blackstone_slab");
                if (Math.abs(z) == Geo.PONT_DEMI + 1) { b(x, 64, z, "glass_pane"); if (Math.floorMod(x, 8) == 0) { b(x, 64, z, "black_concrete"); b(x, 65, z, "end_rod"); } }
            }
        }
        relierVitres(Math.min(xa, xb) - 1, Math.max(xa, xb) + 1, -Geo.PONT_DEMI - 2, Geo.PONT_DEMI + 2, 64);
    }
    private void porte(int px, boolean tour) {
        String lumiere = tour ? "verdant_froglight" : "sea_lantern";
        for (int z : new int[]{-3, 3}) for (int y = 63; y <= Geo.PORTE_HAUT + 1; y++) b(px, y, z, y == 63 || y == Geo.PORTE_HAUT + 1 ? "black_concrete" : lumiere);
        for (int z = -3; z <= 3; z++) { b(px, Geo.PORTE_HAUT + 1, z, "black_concrete"); b(px, Geo.PORTE_HAUT + 2, z, Math.abs(z) <= 2 ? lumiere : "black_concrete"); }
        for (int y = 64; y <= Geo.PORTE_HAUT; y++) for (int z = -2; z <= 2; z++) b(px, y, z, "air");
        for (int z = -2; z <= 2; z++) b(px, 63, z, lumiere);
    }

    /* ------------------------------------------------------------------ Mer de lave (27/09/2026) : pont sud et sa porte */
    private void pontLave() {
        int x0 = Geo.LAVE_X;
        for (int z = Geo.bordHubLave() - 1; z <= Geo.PONT_LAVE_FIN; z++) for (int x = x0 - Geo.PONT_DEMI - 1; x <= x0 + Geo.PONT_DEMI + 1; x++) {
            if (Geo.distHub(x, z) <= R - 0.5) continue;
            b(x, 63, z, x == x0 ? "ochre_froglight" : "black_concrete");
            b(x, 62, z, "polished_blackstone_slab");
            if (Math.abs(x - x0) == Geo.PONT_DEMI + 1) { b(x, 64, z, "glass_pane"); if (Math.floorMod(z, 8) == 0) { b(x, 64, z, "black_concrete"); b(x, 65, z, "end_rod"); } }
        }
        relierVitres(x0 - Geo.PONT_DEMI - 2, x0 + Geo.PONT_DEMI + 2, Geo.bordHubLave() - 2, Geo.PONT_LAVE_FIN + 1, 64);
        // la porte, sur l'axe z : même dessin que celles de La Tour et du Cube, lumière orange
        int pz = Geo.PORTE_LAVE_Z; String l = "ochre_froglight";
        for (int x : new int[]{x0 - 3, x0 + 3}) for (int y = 63; y <= Geo.PORTE_HAUT + 1; y++) b(x, y, pz, y == 63 || y == Geo.PORTE_HAUT + 1 ? "black_concrete" : l);
        for (int x = x0 - 3; x <= x0 + 3; x++) { b(x, Geo.PORTE_HAUT + 1, pz, "black_concrete"); b(x, Geo.PORTE_HAUT + 2, pz, Math.abs(x - x0) <= 2 ? l : "black_concrete"); }
        for (int y = 64; y <= Geo.PORTE_HAUT; y++) for (int x = x0 - 2; x <= x0 + 2; x++) b(x, y, pz, "air");
        for (int x = x0 - 2; x <= x0 + 2; x++) b(x, 63, pz, l);
    }

    /* ------------------------------------------------------------------ allées de la file d'attente */
    static final int ALLEE_LONG = 13, ALLEE_Z0 = -2, ALLEE_NB = 5;
    public static List<int[]> chemin(boolean tour) {
        List<int[]> c = new ArrayList<>();
        int s = tour ? 1 : -1, porte = tour ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X;
        int proche = porte - s, loin = porte - s * ALLEE_LONG;
        for (int n = 0; n < ALLEE_NB; n++) {
            int z = ALLEE_Z0 - 2 * n; boolean versLoin = n % 2 == 0;
            for (int k = 0; k < ALLEE_LONG; k++) c.add(new int[]{versLoin ? proche - s * k : loin + s * k, z});
            if (n < ALLEE_NB - 1) c.add(new int[]{versLoin ? loin : proche, z - 1});
        }
        return c;
    }
    public static int[] entree(boolean tour) { List<int[]> c = chemin(tour); int[] f = c.get(c.size() - 1); int s = tour ? 1 : -1; return new int[]{f[0] - s, f[1]}; }
    private void allees(boolean tour) {
        List<int[]> ch = chemin(tour); Set<Long> dans = new HashSet<>();
        for (int[] p : ch) dans.add(cle(p[0], p[1]));
        int[] ent = entree(tour); dans.add(cle(ent[0], ent[1]));
        int s = tour ? 1 : -1, porte = tour ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X;
        int xa = porte - s, xb = porte - s * (ALLEE_LONG + 1);
        int xmin = Math.min(xa, xb), xmax = Math.max(xa, xb), zmin = ALLEE_Z0 - 2 * (ALLEE_NB - 1) - 1, zmax = ALLEE_Z0 + 1;
        String ligne = tour ? "verdant_froglight" : "sea_lantern";
        for (int x = xmin; x <= xmax; x++) for (int z = zmin; z <= zmax; z++) {
            boolean chemin = dans.contains(cle(x, z));
            b(x, 63, z, chemin ? (Math.floorMod(x + z, 6) == 0 ? ligne : "polished_deepslate") : "black_concrete");
            b(x, 62, z, "black_concrete");
            for (int y = 64; y <= 66; y++) b(x, y, z, "air");
            if (!chemin) b(x, 64, z, "glass_pane");
        }
        relierVitres(xmin - 1, xmax + 1, zmin - 1, zmax + 1, 64);
    }
    private static long cle(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private void vestibule() {
        int d = Geo.VESTIBULE_DEMI;
        for (int x = Geo.VESTIBULE_X1; x <= Geo.VESTIBULE_X2; x++) for (int z = -d; z <= d; z++) for (int y = 63; y <= 69; y++) {
            boolean paroi = x == Geo.VESTIBULE_X1 || x == Geo.VESTIBULE_X2 || Math.abs(z) == d || y == 63 || y == 69;
            boolean entree = x == Geo.VESTIBULE_X2 && Math.abs(z) <= 1 && y >= 64 && y <= 66;
            b(x, y, z, !paroi || entree ? "air" : "white_concrete");
        }
        for (int z = -d + 1; z <= d - 1; z++) for (int x = Geo.VESTIBULE_X1 + 1; x < Geo.VESTIBULE_X2; x++) b(x, 69, z, (x + z) % 3 == 0 ? "sea_lantern" : "white_concrete");
    }
}
