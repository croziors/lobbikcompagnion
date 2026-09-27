package hk.krp.reseau;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Le mur d'écrans du hub (Kripy, 27/09/2026 : « des écrans sur les scores en direct, la Tour et les autres, dans le hub »),
 * sans mod : 16 × 7 cartes dans des cadres lumineux, au nord du salon, soit une image de 2048 × 896 pixels redessinée
 * toutes les 2 s. À gauche Le Cube (qui est dedans, avancée vers la sortie, records), à droite La Tour (chaque grimpeur à
 * sa hauteur sur la tour, top 10). Les données viennent des fichiers direct.json écrits par KrpTour et KrpCube.
 * Seules les cartes dont l'image change sont renvoyées aux joueurs.
 */
final class Ecrans {
    static final int COLS = 16, LIGNES = 7, X0 = Geo.HUB_X - 8, Y_HAUT = 71, Z_CADRES = Geo.HUB_Z - 30;
    static final int L = COLS * 128, H = LIGNES * 128;

    // identité Volt de Lobbik
    private static final Color FOND = new Color(0x0c0d0e), PANNEAU = new Color(0x18191b), TRAIT = new Color(0x2a2c2f),
        VERT = new Color(0xc6f500), CYAN = new Color(0x00e5ff), BLANC = new Color(0xf2f4f5), GRIS = new Color(0x9aa0a6), ROUGE = new Color(0xff5a52), OR = new Color(0xffc53d);

    private final KrpReseau pl; private final World w; private final File fTour, fCube;
    private volatile BufferedImage[] tuiles = new BufferedImage[COLS * LIGNES];
    private final int[] versions = new int[COLS * LIGNES];
    private final long[] empreintes = new long[COLS * LIGNES];
    private final List<GlowItemFrame> cadres = new ArrayList<>();
    private Font gras, normal;

    Ecrans(KrpReseau pl, World w, String tour, String cube) { this.pl = pl; this.w = w; this.fTour = new File(tour); this.fCube = new File(cube); }

    void creer() {
        String[] noms = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        String famille = Arrays.asList(noms).contains("DejaVu Sans") ? "DejaVu Sans" : Font.SANS_SERIF;
        gras = new Font(famille, Font.BOLD, 26); normal = new Font(famille, Font.PLAIN, 24);
        File f = new File(pl.getDataFolder(), "ecrans.yml");
        YamlConfiguration c = YamlConfiguration.loadConfiguration(f);
        List<Integer> ids = c.getIntegerList("cartes");
        List<Integer> nouveaux = new ArrayList<>();
        for (int i = 0; i < COLS * LIGNES; i++) {
            MapView v = i < ids.size() ? Bukkit.getMap(ids.get(i)) : null;
            if (v == null) v = Bukkit.createMap(w);
            nouveaux.add(v.getId());
            for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
            v.setTrackingPosition(false); v.setUnlimitedTracking(false); v.setScale(MapView.Scale.CLOSEST); v.setLocked(true);
            final int n = i;
            v.addRenderer(new MapRenderer(false) {
                private int dessine = -1;
                @Override public void render(MapView view, MapCanvas canvas, Player p) {
                    BufferedImage t = tuiles[n];
                    if (t == null || dessine == versions[n]) return;
                    dessine = versions[n];
                    canvas.drawImage(0, 0, t);
                }
            });
            ItemStack carte = new ItemStack(Material.FILLED_MAP);
            MapMeta mm = (MapMeta) carte.getItemMeta(); mm.setMapView(v); carte.setItemMeta(mm);
            int col = i % COLS, ligne = i / COLS;
            Location l = new Location(w, X0 + col, Y_HAUT - ligne, Z_CADRES);
            cadres.add(w.spawn(l, GlowItemFrame.class, fr -> {
                fr.setFacingDirection(BlockFace.SOUTH, true); fr.setItem(carte, false);
                fr.setVisible(false); fr.setFixed(true); fr.setPersistent(false); fr.setInvulnerable(true);
            }));
        }
        c.set("cartes", nouveaux);
        try { c.save(f); } catch (Exception ignored) { }
        Bukkit.getScheduler().runTaskTimerAsynchronously(pl, this::dessiner, 20, 40);
    }
    void retirer() { for (GlowItemFrame f : cadres) f.remove(); cadres.clear(); }

    /* ------------------------------------------------------------------ dessin */
    private void dessiner() {
        try {
            String tour = lire(fTour), cube = lire(fCube);
            BufferedImage img = new BufferedImage(L, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);   // texte net : les cartes n'ont qu'une petite palette
            g.setColor(FOND); g.fillRect(0, 0, L, H);
            cube(g, 0, cube);
            g.setColor(TRAIT); g.fillRect(L / 2 - 2, 30, 4, H - 60);
            tour(g, L / 2, tour);
            g.dispose();
            // découpe en 112 tuiles ; seules celles qui ont changé seront renvoyées
            BufferedImage[] t = new BufferedImage[COLS * LIGNES];
            for (int i = 0; i < t.length; i++) {
                int x = (i % COLS) * 128, y = (i / COLS) * 128;
                BufferedImage s = img.getSubimage(x, y, 128, 128);
                BufferedImage copie = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB); copie.getGraphics().drawImage(s, 0, 0, null);
                long h = 1125899906842597L; int[] px = copie.getRGB(0, 0, 128, 128, null, 0, 128);
                for (int p : px) h = 31 * h + p;
                t[i] = copie;
                if (h != empreintes[i]) { empreintes[i] = h; versions[i]++; }
            }
            tuiles = t;
        } catch (Throwable e) { pl.getLogger().warning("Écrans : " + e); }
    }
    private static String lire(File f) { try { return f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : null; } catch (Exception e) { return null; } }
    private static boolean frais(String json) { long t = entier(json, "\"t\":(\\d+)"); return json != null && System.currentTimeMillis() - t < 30_000; }

    private void titre(Graphics2D g, int x0, String nom, Color c, String sous) {
        g.setColor(c); g.fillRoundRect(x0 + 40, 38, 10, 52, 6, 6);
        g.setFont(gras.deriveFont(52f)); g.drawString(nom, x0 + 66, 84);
        g.setFont(normal.deriveFont(24f)); g.setColor(GRIS); g.drawString(sous, x0 + 66, 118);
        // pastille « en direct »
        g.setColor(ROUGE); g.fillOval(x0 + 850, 56, 18, 18);
        g.setFont(gras.deriveFont(24f)); g.setColor(BLANC); g.drawString("EN DIRECT", x0 + 876, 73);
    }
    private void panneau(Graphics2D g, int x, int y, int l, int h, String titre) {
        g.setColor(PANNEAU); g.fillRoundRect(x, y, l, h, 22, 22);
        g.setFont(gras.deriveFont(24f)); g.setColor(GRIS); g.drawString(titre, x + 22, y + 38);
    }
    private static String sorties(long n) { return n == 0 ? "aucune sortie encore" : n == 1 ? "1 sortie réussie" : n + " sorties réussies"; }
    private static String court(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    /* ------------------------------------------------------------------ La Tour */
    private void tour(Graphics2D g, int x0, String json) {
        List<Map<String, String>> joueurs = objets(json, "joueurs"), top = objets(json, "top");
        int sommet = (int) Math.max(1, entier(json, "\"sommet\":(\\d+)"));
        titre(g, x0, "LA TOUR", VERT, json == null || !frais(json) ? "serveur hors ligne" : joueurs.size() + (joueurs.size() > 1 ? " grimpeurs" : " grimpeur") + " en ce moment · " + sommet + " niveaux");
        // la tour, de profil : chaque grimpeur à sa hauteur
        int tx = x0 + 60, ty = 160, th = 690, tl = 90;
        g.setColor(PANNEAU); g.fillRoundRect(tx, ty, tl, th, 20, 20);
        g.setColor(TRAIT); for (int k = 1; k < 10; k++) g.fillRect(tx + 12, ty + th - th * k / 10, tl - 24, 2);
        for (String cp : liste(json, "checkpoints")) {
            int n = (int) Long.parseLong(cp.trim()); int y = ty + th - (int) ((long) th * n / sommet);
            g.setColor(OR); g.fillRect(tx - 8, y - 2, tl + 16, 5);
        }
        g.setColor(VERT); g.fillRoundRect(tx + 20, ty - 14, tl - 40, 14, 8, 8);
        g.setFont(gras.deriveFont(20f)); g.setColor(GRIS); g.drawString("sommet", tx + tl + 14, ty + 8); g.drawString("départ", tx + tl + 14, ty + th);
        int i = 0;
        List<int[]> pris = new ArrayList<>();
        for (Map<String, String> j : joueurs) {
            int niv = (int) num(j.get("niveau")); int y = ty + th - (int) ((long) th * Math.min(niv, sommet) / sommet);
            Color c = i++ % 2 == 0 ? CYAN : VERT;
            g.setColor(c); g.fillOval(tx + tl / 2 - 11, y - 11, 22, 22);
            int yl = y; for (int[] p : pris) if (Math.abs(p[0] - yl) < 26) yl = p[0] + 26;   // étiquettes sans chevauchement
            pris.add(new int[]{yl});
            g.setFont(gras.deriveFont(22f)); g.drawString(court(j.getOrDefault("nom", "?"), 14) + " · " + niv, tx + tl + 14, yl + 8);
        }
        // en ligne
        int px = x0 + 390, pw = 594;
        panneau(g, px, 150, pw, 300, "EN LIGNE");
        g.setFont(normal); int y = 222;
        if (joueurs.isEmpty()) { g.setColor(GRIS); g.drawString("Personne ne grimpe en ce moment.", px + 22, y); }
        for (Map<String, String> j : joueurs.subList(0, Math.min(6, joueurs.size()))) {
            g.setColor(BLANC); g.drawString(court(j.getOrDefault("nom", "?"), 16), px + 22, y);
            g.setColor(VERT); g.drawString("niv. " + (int) num(j.get("niveau")), px + 300, y);
            g.setColor(GRIS); g.drawString("rec. " + (int) num(j.get("record")), px + 440, y);
            y += 36;
        }
        // top 10
        panneau(g, px, 470, pw, 380, "TOP 10 · RECORDS");
        y = 540; int r = 1;
        long max = 1; for (Map<String, String> t : top) max = Math.max(max, (long) num(t.get("record")));
        for (Map<String, String> t : top) {
            if (r > 9) break;
            long rec = (long) num(t.get("record"));
            g.setColor(r == 1 ? OR : GRIS); g.setFont(gras.deriveFont(24f)); g.drawString(String.valueOf(r), px + 22, y);
            g.setColor(TRAIT); g.fillRoundRect(px + 250, y - 18, 240, 16, 8, 8);
            g.setColor(r == 1 ? OR : VERT); g.fillRoundRect(px + 250, y - 18, (int) Math.max(8, 240 * rec / max), 16, 8, 8);
            g.setColor(BLANC); g.setFont(normal); g.drawString(court(t.getOrDefault("nom", "?"), 13), px + 56, y);
            g.setColor(GRIS); g.drawString(String.valueOf(rec), px + 504, y);
            y += 34; r++;
        }
        if (top.isEmpty()) { g.setColor(GRIS); g.setFont(normal); g.drawString("Aucun record pour l'instant.", px + 22, y); }
    }

    /* ------------------------------------------------------------------ Le Cube */
    private void cube(Graphics2D g, int x0, String json) {
        List<Map<String, String>> joueurs = objets(json, "joueurs"), loin = objets(json, "loin"), vite = objets(json, "vite");
        long dedans = joueurs.stream().filter(j -> "true".equals(j.get("dedans"))).count();
        titre(g, x0, "LE CUBE", CYAN, json == null || !frais(json) ? "serveur hors ligne" : dedans + " dans le Cube · " + entier(json, "\"salles\":(\\d+)") + " salles · " + sorties(entier(json, "\"sorties\":(\\d+)")));
        // dans le Cube : avancée vers la sortie
        int px = x0 + 40, pw = 944;
        panneau(g, px, 150, pw, 330, "DANS LE CUBE · AVANCÉE VERS LA SORTIE");
        int y = 226;
        g.setFont(normal);
        if (dedans == 0) { g.setColor(GRIS); g.drawString("Personne dans le Cube. Le vestibule blanc attend…", px + 22, y); }
        for (Map<String, String> j : joueurs) {
            if (!"true".equals(j.get("dedans")) || y > 460) continue;
            int av = (int) num(j.get("avance"));
            g.setColor(BLANC); g.setFont(normal); g.drawString(court(j.getOrDefault("nom", "?"), 14), px + 22, y);
            g.setColor(TRAIT); g.fillRoundRect(px + 260, y - 20, 420, 22, 11, 11);
            g.setColor(CYAN); g.fillRoundRect(px + 260, y - 20, Math.max(12, 420 * av / 100), 22, 11, 11);
            g.setColor(BLANC); g.setFont(gras.deriveFont(22f)); g.drawString(av + " %", px + 694, y);
            g.setColor(GRIS); g.setFont(normal.deriveFont(20f)); g.drawString("niv. " + (int) num(j.get("niveau")) + " · " + (int) num(j.get("salles")) + " salles · " + (int) num(j.get("morts")) + " †", px + 770, y);
            y += 44;
        }
        // records
        panneau(g, px, 500, 520, 350, "LE PLUS LOIN · SALLES");
        y = 570; int r = 1;
        for (Map<String, String> t : loin) {
            if (r > 8) break;
            g.setColor(r == 1 ? OR : GRIS); g.setFont(gras.deriveFont(24f)); g.drawString(String.valueOf(r), px + 22, y);
            g.setColor(BLANC); g.setFont(normal); g.drawString(court(t.getOrDefault("nom", "?"), 14), px + 60, y);
            g.setColor(CYAN); g.drawString(String.valueOf((long) num(t.get("salles"))), px + 430, y);
            y += 34; r++;
        }
        if (loin.isEmpty()) { g.setColor(GRIS); g.setFont(normal); g.drawString("Personne n'a encore quitté la salle blanche.", px + 22, y); }
        panneau(g, px + 540, 500, 404, 350, "LES PLUS RAPIDES");
        y = 570; r = 1;
        for (Map<String, String> t : vite) {
            long s = (long) num(t.get("temps"));
            g.setColor(r == 1 ? OR : GRIS); g.setFont(gras.deriveFont(24f)); g.drawString(String.valueOf(r), px + 562, y);
            g.setColor(BLANC); g.setFont(normal); g.drawString(court(t.getOrDefault("nom", "?"), 12), px + 598, y);
            g.setColor(CYAN); g.drawString(String.format("%d:%02d", s / 60, s % 60), px + 850, y);
            y += 34; r++;
        }
        if (vite.isEmpty()) { g.setColor(GRIS); g.setFont(normal); g.drawString("Aucune sortie encore.", px + 562, y); }
    }

    /* ------------------------------------------------------------------ lecture JSON minimale */
    private static long entier(String s, String re) { if (s == null) return 0; Matcher m = Pattern.compile(re).matcher(s); return m.find() ? Long.parseLong(m.group(1)) : 0; }
    private static double num(String s) { try { return s == null ? 0 : Double.parseDouble(s); } catch (Exception e) { return 0; } }
    private static List<String> liste(String json, String cle) {
        if (json == null) return List.of();
        Matcher m = Pattern.compile("\"" + cle + "\":\\[([^\\[\\]]*)\\]").matcher(json);
        if (!m.find() || m.group(1).isBlank()) return List.of();
        return Arrays.asList(m.group(1).split(","));
    }
    /** Les objets plats {…} du tableau « clé » (valeurs simples : nombres, booléens, chaînes). */
    private static List<Map<String, String>> objets(String json, String cle) {
        List<Map<String, String>> l = new ArrayList<>();
        if (json == null) return l;
        int i = json.indexOf("\"" + cle + "\":["); if (i < 0) return l;
        int j = json.indexOf(']', i); if (j < 0) return l;
        Matcher o = Pattern.compile("\\{([^{}]*)\\}").matcher(json.substring(i, j));
        Pattern kv = Pattern.compile("\"(\\w+)\":(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,}]+)");
        while (o.find()) {
            Map<String, String> m = new HashMap<>();
            Matcher k = kv.matcher(o.group(1));
            while (k.find()) m.put(k.group(1), k.group(3) != null ? k.group(3).replace("\\\"", "\"").replace("\\\\", "\\") : k.group(2).trim());
            l.add(m);
        }
        return l;
    }
}
