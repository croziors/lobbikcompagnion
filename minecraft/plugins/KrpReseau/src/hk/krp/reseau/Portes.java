package hk.krp.reseau;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Les portes du hub, vues par chaque joueur séparément (Kripy, 27/09/2026) : un mur de verre coloré sur l'arche.
 * <ul>
 *   <li>vert : il reste de la place et personne n'attend → on passe ;</li>
 *   <li>rouge : plein → toucher le mur (ou entrer dans les allées) met dans la file ; le mur arrête le joueur ;</li>
 *   <li>doré : c'est ton tour → la porte s'ouvre pour toi seul pendant quelques secondes.</li>
 * </ul>
 * Chaque joueur voit son propre mur (affichages cachés par défaut, montrés un par un) et ses propres blocs invisibles
 * de barrière quand c'est fermé ; le serveur, lui, garde le passage vide et vérifie au franchissement.
 */
final class Portes {
    enum Etat { LIBRE, PLEIN, ATTENTE, OUVERT, VISITEUR }

    final class Porte {
        final String cible, nom; final boolean tour; final int x; final List<int[]> chemin;
        BlockDisplay vert, rouge, dore; TextDisplay titre;
        Porte(String cible, String nom, boolean tour) { this.cible = cible; this.nom = nom; this.tour = tour; this.x = tour ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X; this.chemin = Decor.chemin(tour); }
        int sens() { return tour ? 1 : -1; }
    }

    private final KrpReseau pl; private final World w;
    final Porte tour, cube;
    private final Map<UUID, Map<String, String>> etats = new ConcurrentHashMap<>();
    private volatile Map<String, String> commun = Map.of();
    private final Map<UUID, BossBar> barres = new ConcurrentHashMap<>();
    private final Map<UUID, String> fermes = new ConcurrentHashMap<>();   // dernières barrières envoyées (« tour,cube »)

    Portes(KrpReseau pl, World w) {
        this.pl = pl; this.w = w;
        tour = new Porte("tour", "La Tour", true); cube = new Porte("cube", "Le Cube", false);
    }
    List<Porte> toutes() { return List.of(tour, cube); }

    /* ------------------------------------------------------------------ affichages */
    void creer() {
        for (Porte p : toutes()) {
            p.vert = mur(p, Material.LIME_STAINED_GLASS); p.rouge = mur(p, Material.RED_STAINED_GLASS); p.dore = mur(p, Material.YELLOW_STAINED_GLASS);
            p.titre = w.spawn(new Location(w, p.x + 0.5, Geo.PORTE_HAUT + 3.4, 0.5), TextDisplay.class, t -> {
                t.setPersistent(false); t.setBillboard(Display.Billboard.CENTER); t.setShadowed(false); t.setSeeThrough(false);
                t.setBackgroundColor(Color.fromARGB(110, 10, 12, 16)); t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.6f, 1.6f, 1.6f), new AxisAngle4f()));
                t.text(Component.text(p.nom, NamedTextColor.WHITE));
            });
        }
    }
    private BlockDisplay mur(Porte p, Material verre) {
        return w.spawn(new Location(w, p.x + 0.42, Geo.PORTE_BAS, -Geo.PONT_DEMI), BlockDisplay.class, d -> {
            d.setPersistent(false); d.setVisibleByDefault(false);
            d.setBlock(verre.createBlockData());
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.16f, Geo.PORTE_HAUT - Geo.PORTE_BAS + 1, 2 * Geo.PONT_DEMI + 1), new AxisAngle4f()));
        });
    }
    void retirer() {
        for (Porte p : toutes()) for (Entity e : new Entity[]{p.vert, p.rouge, p.dore, p.titre}) if (e != null) e.remove();
        for (BossBar b : barres.values()) for (Player q : Bukkit.getOnlinePlayers()) q.hideBossBar(b);
    }

    /* ------------------------------------------------------------------ état reçu du proxy */
    void etat(Player p, Map<String, String> m) {
        Map<String, String> e = new HashMap<>();
        for (Porte po : toutes()) if (m.containsKey(po.cible)) e.put(po.cible, m.get(po.cible));
        etats.put(p.getUniqueId(), e);
        Map<String, String> c = new HashMap<>();
        for (String k : m.keySet()) if (k.equals("hub") || k.startsWith("nb_")) c.put(k, m.get(k));
        commun = c;
    }
    Map<String, String> commun() { return commun; }
    void oublier(Player p) { etats.remove(p.getUniqueId()); fermes.remove(p.getUniqueId()); BossBar b = barres.remove(p.getUniqueId()); if (b != null) p.hideBossBar(b); }

    Etat etatDe(Player p, Porte po) {
        if (!pl.estMembre(p)) return Etat.VISITEUR;
        Map<String, String> e = etats.get(p.getUniqueId());
        String v = e == null ? null : e.get(po.cible);
        if (v == null) return Etat.PLEIN;   // pas encore de nouvelles du proxy : fermé par prudence
        return switch (v.charAt(0)) { case 'L' -> Etat.LIBRE; case 'O' -> Etat.OUVERT; case 'A' -> Etat.ATTENTE; default -> Etat.PLEIN; };
    }
    /** {rang, taille de la file, secondes restantes} */
    int[] details(Player p, Porte po) {
        Map<String, String> e = etats.get(p.getUniqueId());
        String v = e == null ? null : e.get(po.cible);
        if (v == null) return new int[]{0, 0, 0};
        String[] t = v.split(":");
        try { return new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])}; } catch (Exception ex) { return new int[]{0, 0, 0}; }
    }
    boolean ouvertePour(Player p, Porte po) { Etat e = etatDe(p, po); return e == Etat.LIBRE || e == Etat.OUVERT; }

    /* ------------------------------------------------------------------ rendu, deux fois par seconde */
    void rendre() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            StringBuilder ferme = new StringBuilder();
            BossBar.Color couleurBarre = null; Component texteBarre = null; float progres = 1f;
            for (Porte po : toutes()) {
                Etat e = etatDe(p, po);
                BlockDisplay voir = switch (e) { case LIBRE -> po.vert; case OUVERT -> po.dore; default -> po.rouge; };
                for (BlockDisplay d : new BlockDisplay[]{po.vert, po.rouge, po.dore}) { if (d == null) continue; if (d == voir) p.showEntity(pl, d); else p.hideEntity(pl, d); }
                boolean ouvert = e == Etat.LIBRE || e == Etat.OUVERT;
                if (!ouvert) ferme.append(po.cible).append(',');
                int[] dt = details(p, po);
                if (e == Etat.OUVERT) {
                    couleurBarre = BossBar.Color.YELLOW; progres = Math.min(1f, dt[2] / (float) pl.ouverture());
                    texteBarre = Component.text("C'est ton tour ! La porte de " + po.nom + " est ouverte pour toi · " + dt[2] + " s", NamedTextColor.GOLD, TextDecoration.BOLD);
                } else if (e == Etat.ATTENTE && couleurBarre == null) {
                    couleurBarre = BossBar.Color.RED; progres = dt[1] <= 0 ? 0f : Math.max(0.02f, 1f - (dt[0] - 1) / (float) dt[1]);
                    texteBarre = Component.text("File d'attente · " + po.nom + " : tu es " + (dt[0] == 1 ? "le prochain" : dt[0] + "e") + " sur " + dt[1], NamedTextColor.WHITE);
                    // ta place dans les allées : quelques étincelles, pour toi seul
                    int[] c = po.chemin.get(Math.min(Math.max(dt[0] - 1, 0), po.chemin.size() - 1));
                    p.spawnParticle(Particle.HAPPY_VILLAGER, c[0] + 0.5, Geo.Y + 0.3, c[1] + 0.5, 4, 0.25, 0.15, 0.25, 0);
                }
            }
            // barres de progression
            BossBar b = barres.get(p.getUniqueId());
            if (texteBarre != null) {
                if (b == null) { b = BossBar.bossBar(texteBarre, progres, couleurBarre, BossBar.Overlay.PROGRESS); barres.put(p.getUniqueId(), b); p.showBossBar(b); }
                else { b.name(texteBarre); b.progress(progres); b.color(couleurBarre); }
            } else if (b != null) { p.hideBossBar(b); barres.remove(p.getUniqueId()); }
            // blocs de barrière invisibles côté client seulement, sur les portes fermées pour lui
            String f = ferme.toString();
            if (!f.equals(fermes.get(p.getUniqueId())) || (System.currentTimeMillis() / 500) % 4 == 0) {
                fermes.put(p.getUniqueId(), f);
                for (Porte po : toutes()) {
                    BlockData d = (f.contains(po.cible + ",") ? Material.BARRIER : Material.AIR).createBlockData();
                    for (int y = Geo.PORTE_BAS; y <= Geo.PORTE_HAUT; y++) for (int z = -Geo.PONT_DEMI; z <= Geo.PONT_DEMI; z++) p.sendBlockChange(new Location(w, po.x, y, z), d);
                }
            }
        }
        // titres au-dessus des portes : joueurs / places, file
        Map<String, String> c = commun;
        for (Porte po : toutes()) {
            if (po.titre == null) continue;
            String v = c.get("nb_" + po.cible);
            Component t = Component.text(po.nom.toUpperCase(Locale.ROOT), po.tour ? TextColor.color(0xFFB347) : TextColor.color(0x5AB4FF), TextDecoration.BOLD);
            if (v != null) {
                String[] n = v.split("/");
                boolean plein = n.length >= 2 && Integer.parseInt(n[0]) >= Integer.parseInt(n[1]);
                t = t.append(Component.newline()).append(Component.text(n[0] + " / " + n[1] + " joueurs", plein ? NamedTextColor.RED : NamedTextColor.GREEN));
                if (n.length >= 3 && !n[2].equals("0")) t = t.append(Component.newline()).append(Component.text("File d'attente : " + n[2], NamedTextColor.GOLD));
            }
            po.titre.text(t);
        }
    }

    /* ------------------------------------------------------------------ messages du proxy pour un joueur */
    void tonTour(Player p, String cible, int secondes) {
        Porte po = cible.equals("tour") ? tour : cube;
        p.showTitle(Title.title(Component.text("C'est ton tour !", NamedTextColor.GOLD, TextDecoration.BOLD),
            Component.text("La porte de " + po.nom + " s'ouvre pour toi · " + secondes + " s", NamedTextColor.YELLOW), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        Bukkit.getScheduler().runTaskLater(pl, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f), 4);
    }
    void perdu(Player p, String cible) {
        Porte po = cible.equals("tour") ? tour : cube;
        p.sendMessage(Component.text("Ton tour pour " + po.nom + " est passé : la porte s'est refermée. Touche le mur pour reprendre une place.", NamedTextColor.GRAY));
    }
    Porte porte(String cible) { return cible.equals("tour") ? tour : cible.equals("cube") ? cube : null; }
}
