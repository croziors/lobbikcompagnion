package hk.krp.reseau;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Panneaux lumineux du hub (Kripy, 27/09/2026 : « affiche tous les serveurs disponibles ») : le grand titre LOBBIK
 * au-dessus de la fontaine, et le tableau des serveurs à l'arrivée — La Tour et Le Cube (comptés par le proxy), puis
 * les serveurs du site : Counter-Strike 2, Valheim, et les serveurs Minecraft de la communauté.
 */
final class Panneaux {
    private final KrpReseau pl; private final World w; private final String site;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private TextDisplay titre, tableau, bienvenue;
    private volatile List<Component> lignesSite = List.of();

    Panneaux(KrpReseau pl, World w, String site) { this.pl = pl; this.w = w; this.site = site; }

    void creer() {
        titre = texte(new Location(w, Geo.HUB_X + 0.5, 73.5, Geo.HUB_Z + 0.5), 6f, Display.Billboard.VERTICAL, 0);
        titre.text(degrade("LOBBIK"));
        // le tableau des serveurs, face à l'arrivée (au sud de l'esplanade), et un mot d'accueil
        // sur les deux écrans noirs de l'arrivée (Decor.ecrans), face au sud : on les lit en arrivant
        tableau = texte(new Location(w, Geo.HUB_X - 8, 64.6, Geo.HUB_Z + 11.56), 1.0f, Display.Billboard.FIXED, 0);
        bienvenue = texte(new Location(w, Geo.HUB_X + 9, 64.6, Geo.HUB_Z + 11.56), 1.0f, Display.Billboard.FIXED, 0);
        bienvenue.text(Component.text("Bienvenue sur Lobbik", NamedTextColor.WHITE, TextDecoration.BOLD).append(Component.newline())
            .append(Component.text("Le point de départ du réseau.", NamedTextColor.GRAY)).append(Component.newline()).append(Component.newline())
            .append(Component.text("▶ ", TextColor.color(0xFFB347))).append(Component.text("À droite : La Tour", NamedTextColor.WHITE)).append(Component.newline())
            .append(Component.text("◀ ", TextColor.color(0x5AB4FF))).append(Component.text("À gauche : Le Cube", NamedTextColor.WHITE)).append(Component.newline())
            .append(Component.text("▲ ", TextColor.color(0x7CFF4F))).append(Component.text("Devant : le salon, pour discuter", NamedTextColor.WHITE)).append(Component.newline()).append(Component.newline())
            .append(Component.text("Mur vert : on passe · rouge : plein, file d'attente", NamedTextColor.GRAY)).append(Component.newline())
            .append(Component.text("/lobbik pour revenir ici de partout", NamedTextColor.DARK_GRAY)));
        Bukkit.getScheduler().runTaskTimerAsynchronously(pl, this::lireSite, 20, 20 * 30);
        Bukkit.getScheduler().runTaskTimer(pl, this::rendre, 40, 40);
    }
    void retirer() { for (TextDisplay t : new TextDisplay[]{titre, tableau, bienvenue}) if (t != null) t.remove(); }

    private TextDisplay texte(Location l, float echelle, Display.Billboard bb, float lacet) {
        l.setYaw(lacet);
        return w.spawn(l, TextDisplay.class, t -> {
            t.setPersistent(false); t.setBillboard(bb); t.setShadowed(false); t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)); t.setLineWidth(300);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(echelle, echelle, echelle), new AxisAngle4f()));
        });
    }
    /** Dégradé vert électrique → cyan (identité Volt de Lobbik), lettre par lettre, net (sans ombre). */
    static Component degrade(String s) {
        Component c = Component.empty();
        for (int i = 0; i < s.length(); i++) {
            float t = s.length() <= 1 ? 0 : i / (float) (s.length() - 1);
            int r = (int) (0x7C + (0x3F - 0x7C) * t), g = (int) (0xFF + (0xE0 - 0xFF) * t), b = (int) (0x4F + (0xFF - 0x4F) * t);
            c = c.append(Component.text(String.valueOf(s.charAt(i)), TextColor.color(r, g, b), TextDecoration.BOLD));
        }
        return c;
    }

    private void rendre() {
        if (tableau == null) return;
        Map<String, String> c = pl.portes() != null ? pl.portes().commun() : Map.of();
        Component t = Component.text("SERVEURS LOBBIK", TextColor.color(0x7CFF4F), TextDecoration.BOLD).append(Component.newline());
        t = t.append(ligne("La Tour", "Minecraft · parkour de 1 000 blocs", c.get("nb_tour"), TextColor.color(0xFFB347)));
        t = t.append(ligne("Le Cube", "Minecraft · 1 000 salles, certaines tuent", c.get("nb_cube"), TextColor.color(0x5AB4FF)));
        String hub = c.get("hub");
        t = t.append(Component.text("● ", NamedTextColor.GREEN)).append(Component.text("Lobbik (ici)", NamedTextColor.WHITE)).append(Component.text("  " + (hub == null ? Bukkit.getOnlinePlayers().size() : hub) + " / " + Bukkit.getMaxPlayers(), NamedTextColor.GRAY)).append(Component.newline());
        for (Component l : lignesSite) t = t.append(l);
        t = t.append(Component.newline()).append(Component.text("lobbik.com", TextColor.color(0x3FE0FF)));
        tableau.text(t);
    }
    private static Component ligne(String nom, String info, String nb, TextColor couleur) {
        String[] n = nb == null ? null : nb.split("/");
        boolean enLigne = n != null;
        Component c = Component.text("● ", enLigne ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY).append(Component.text(nom, couleur, TextDecoration.BOLD));
        if (enLigne) {
            boolean plein = Integer.parseInt(n[0]) >= Integer.parseInt(n[1]);
            c = c.append(Component.text("  " + n[0] + " / " + n[1], plein ? NamedTextColor.RED : NamedTextColor.GRAY));
            if (n.length >= 3 && !n[2].equals("0")) c = c.append(Component.text("  · file " + n[2], NamedTextColor.GOLD));
        } else c = c.append(Component.text("  …", NamedTextColor.DARK_GRAY));
        return c.append(Component.newline()).append(Component.text(info, NamedTextColor.DARK_GRAY)).append(Component.newline());
    }

    /** Serveurs du site (toutes les 30 s, hors du fil principal). */
    private void lireSite() {
        List<Component> l = new ArrayList<>();
        l.add(Component.newline());
        for (String[] j : new String[][]{{"730", "Counter-Strike 2"}, {"892970", "Valheim"}}) {
            String rep = get(site + "/auth/steam.php?a=cs_serveur&jeu=" + j[0]);
            if (rep == null) continue;
            boolean enLigne = rep.contains("\"en_ligne\":true");
            int joueurs = entier(rep, "\"joueurs\":(\\d+)"), places = entier(rep, "\"places\":(\\d+)");
            String carte = texteJson(rep, "\"carte\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            l.add(Component.text("● ", enLigne ? NamedTextColor.GREEN : NamedTextColor.RED).append(Component.text(j[1], NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(enLigne ? "  " + joueurs + " / " + places : "  hors ligne", NamedTextColor.GRAY)).append(Component.newline()));
            if (enLigne && j[0].equals("730") && carte != null) l.add(Component.text("carte " + carte, NamedTextColor.DARK_GRAY).append(Component.newline()));
        }
        String com = get(site + "/auth/steam.php?a=mc_com_liste");
        if (com != null) {
            Matcher m = Pattern.compile("\"nom\":\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\"joueurs\":(\\d+)[^{}]*?\"max\":(\\d+)").matcher(com);
            int n = 0;
            while (m.find() && n < 6) {
                if (n++ == 0) l.add(Component.newline().append(Component.text("Communauté Minecraft", NamedTextColor.GRAY, TextDecoration.ITALIC)).append(Component.newline()));
                l.add(Component.text("● ", NamedTextColor.GREEN).append(Component.text(m.group(1).replace("\\\"", "\""), NamedTextColor.WHITE)).append(Component.text("  " + m.group(2) + " / " + m.group(3), NamedTextColor.GRAY)).append(Component.newline()));
            }
        }
        lignesSite = l;
    }
    private String get(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).header("User-Agent", "krp-reseau/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) { return null; }
    }
    private static int entier(String s, String re) { Matcher m = Pattern.compile(re).matcher(s); return m.find() ? Integer.parseInt(m.group(1)) : 0; }
    private static String texteJson(String s, String re) { Matcher m = Pattern.compile(re).matcher(s); return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\/", "/") : null; }
}
