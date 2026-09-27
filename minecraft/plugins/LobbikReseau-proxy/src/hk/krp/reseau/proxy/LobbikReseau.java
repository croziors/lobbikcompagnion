package hk.krp.reseau.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.ClientWorldSwitches;
import com.velocitypowered.api.proxy.player.LobbikPassages;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;

/**
 * Lobbik (27/09/2026) — le réseau Minecraft : un hub (« Lobbik ») relié par des ponts à La Tour et au Cube.
 *
 * <ul>
 *   <li>Passage par le pont : le serveur de départ demande {@code passage} ; on envoie au serveur d'arrivée un ticket
 *       (numéro d'entité que le client connaît déjà, position exacte), on marque le passage « pont » et on connecte :
 *       le proxy garde alors le monde du client, sans écran de chargement.</li>
 *   <li>Places limitées (Tour 100, Cube 30, hub 1000) et file d'attente automatique : quand c'est son tour, la porte
 *       s'ouvre pour ce joueur seulement pendant {@code ouverture} secondes.</li>
 *   <li>Chat commun : un message dit sur un serveur s'affiche sur les autres, avec le nom du serveur.</li>
 * </ul>
 * Messages sur le canal {@code lobbik:reseau} : une ligne « type\tclé=valeur\t… ».
 */
public final class LobbikReseau {
    static final MinecraftChannelIdentifier CANAL = MinecraftChannelIdentifier.create("lobbik", "reseau");

    private final ProxyServer proxy; private final Logger log; private final Path dossier;
    private final Properties reglages = new Properties();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, File> files = new LinkedHashMap<>();
    private final Map<UUID, Long> enPassage = new ConcurrentHashMap<>();   // passage demandé, pas encore arrivé : pas de double demande

    /** File d'attente d'un serveur à places limitées. */
    private final class File {
        final String serveur; volatile int capacite;
        final Deque<UUID> attente = new ArrayDeque<>();
        final Map<UUID, Long> ouverts = new LinkedHashMap<>();   // porte ouverte pour ce joueur jusqu'à…
        File(String serveur, int capacite) { this.serveur = serveur; this.capacite = capacite; }
        int joueurs() { return proxy.getServer(serveur).map(s -> s.getPlayersConnected().size()).orElse(0); }
        synchronized int occupees() { return joueurs() + ouverts.size(); }
        synchronized boolean libre() { return attente.isEmpty() && occupees() < capacite; }
        synchronized int rang(UUID u) { int i = 1; for (UUID x : attente) { if (x.equals(u)) return i; i++; } return 0; }
    }

    @Inject
    public LobbikReseau(ProxyServer proxy, Logger log, @DataDirectory Path dossier) { this.proxy = proxy; this.log = log; this.dossier = dossier; }

    @Subscribe
    public void demarrer(ProxyInitializeEvent e) {
        chargerReglages();
        proxy.getChannelRegistrar().register(CANAL);
        for (String s : reglages.getProperty("limites", "tour=100,cube=30").split(",")) {
            String[] kv = s.trim().split("="); if (kv.length == 2) files.put(kv[0].trim(), new File(kv[0].trim(), Integer.parseInt(kv[1].trim())));
        }
        proxy.getScheduler().buildTask(this, this::tic).repeat(1, TimeUnit.SECONDS).schedule();
        proxy.getScheduler().buildTask(this, this::entete).repeat(5, TimeUnit.SECONDS).schedule();
        // la capacité suit le réglage de chaque serveur (max-players de son server.properties), relu toutes les 5 s :
        // relancer La Tour avec 50 places suffit, rien à changer ici (Kripy, 27/09/2026)
        proxy.getScheduler().buildTask(this, this::sonder).repeat(5, TimeUnit.SECONDS).schedule();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("reseau").plugin(this).build(), new Commande(this));
        if (!reglages.getProperty("moderation", "").isBlank())
            proxy.getScheduler().buildTask(this, this::moderation).repeat(10, TimeUnit.SECONDS).schedule();
        log.info("Réseau Lobbik prêt — files : {}", files.keySet());
    }

    private void chargerReglages() {
        try {
            Files.createDirectories(dossier);
            Path f = dossier.resolve("reseau.properties");
            if (!Files.exists(f)) try (InputStream in = getClass().getResourceAsStream("/reseau.properties")) { Files.copy(in, f); }
            try (InputStream in = Files.newInputStream(f)) { reglages.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
        } catch (IOException ex) { log.error("reseau.properties illisible", ex); }
    }
    private String hub() { return reglages.getProperty("hub", "hub"); }
    private int ouverture() { return Integer.parseInt(reglages.getProperty("ouverture", "45")); }

    /* ------------------------------------------------------------ messages des serveurs */
    @Subscribe
    public void message(PluginMessageEvent e) {
        if (!CANAL.equals(e.getIdentifier())) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled());   // jamais transmis au client
        if (!(e.getSource() instanceof ServerConnection sc)) return;
        Player p = sc.getPlayer();
        Map<String, String> m = lire(new String(e.getData(), StandardCharsets.UTF_8));
        String type = m.getOrDefault("", "");
        switch (type) {
            case "passage" -> passage(p, sc, m);
            case "file" -> { File f = files.get(m.get("cible")); if (f != null) entrerFile(p, f); }
            case "quitter" -> { File f = files.get(m.get("cible")); if (f != null) synchronized (f) { f.attente.remove(p.getUniqueId()); f.ouverts.remove(p.getUniqueId()); } }
            case "aller" -> aller(p, m.getOrDefault("cible", hub()));
            case "chat" -> chat(sc, p, m.getOrDefault("texte", ""), m.get("tag"), m.get("couleur"));
            default -> { }
        }
    }

    /** Le joueur a franchi la porte du pont vers {@code cible}. */
    private void passage(Player p, ServerConnection depuis, Map<String, String> m) {
        String cible = m.get("cible");
        Optional<RegisteredServer> rs = cible == null ? Optional.<RegisteredServer>empty() : proxy.getServer(cible).map(x -> (RegisteredServer) x);
        if (rs.isEmpty() || cible.equals(depuis.getServerInfo().getName())) return;
        Long deja = enPassage.get(p.getUniqueId());
        if (deja != null && System.currentTimeMillis() - deja < 8000) return;
        File f = files.get(cible);
        if (f != null) {
            boolean ok;
            synchronized (f) {
                ok = f.ouverts.containsKey(p.getUniqueId()) || f.libre();
                if (ok) { f.ouverts.remove(p.getUniqueId()); f.attente.remove(p.getUniqueId()); f.ouverts.put(p.getUniqueId(), System.currentTimeMillis() + 15_000); }   // place gardée le temps d'arriver
            }
            if (!ok) { envoyer(depuis, "refus\tcible=" + cible + "\traison=plein"); entrerFile(p, f); return; }
        }
        enPassage.put(p.getUniqueId(), System.currentTimeMillis());
        int id = ClientWorldSwitches.clientEntityId(p.getUniqueId());
        String ticket = "uuid=" + p.getUniqueId() + "&id=" + id + "&x=" + m.get("x") + "&y=" + m.get("y") + "&z=" + m.get("z")
            + "&yaw=" + m.get("yaw") + "&pitch=" + m.get("pitch") + "&mode=pont&depuis=" + depuis.getServerInfo().getName();
        String port = reglages.getProperty("controle." + cible);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ticket")).timeout(Duration.ofSeconds(6))
            .header("X-Cle", reglages.getProperty("cle", "")).POST(HttpRequest.BodyPublishers.ofString(ticket)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((rep, err) -> {
            if (err != null || rep.statusCode() != 200) {
                log.warn("Ticket refusé par {} pour {} : {}", cible, p.getUsername(), err != null ? err.toString() : rep.statusCode() + " " + rep.body());
                echec(p, depuis, cible, f);
                return;
            }
            LobbikPassages.marquerPont(p.getUniqueId());
            p.createConnectionRequest(rs.get()).connect().whenComplete((r, ex) -> {
                if (ex != null || r == null || !r.isSuccessful()) {
                    LobbikPassages.oublier(p.getUniqueId());
                    log.warn("Passage de {} vers {} manqué : {}", p.getUsername(), cible, ex != null ? ex.toString() : r == null ? "?" : r.getStatus() + " " + r.getReasonComponent().map(Object::toString).orElse(""));
                    echec(p, depuis, cible, f);
                }
            });
        });
    }
    private void echec(Player p, ServerConnection depuis, String cible, File f) {
        enPassage.remove(p.getUniqueId());
        if (f != null) synchronized (f) { f.ouverts.remove(p.getUniqueId()); }
        p.getCurrentServer().ifPresent(sc -> envoyer(sc, "refus\tcible=" + cible + "\traison=indisponible"));
    }

    /** Changement de serveur par commande : ordinaire (écran de chargement), pas de ticket. */
    private void aller(Player p, String cible) {
        proxy.getServer(cible).ifPresent(rs -> {
            File f = files.get(cible);
            if (f != null && !f.libre()) { p.sendMessage(Component.text("Ce serveur est plein : vous êtes placé dans la file d'attente.", NamedTextColor.GOLD)); entrerFile(p, f); if (!p.getCurrentServer().map(s -> s.getServerInfo().getName().equals(hub())).orElse(false)) proxy.getServer(hub()).ifPresent(h -> p.createConnectionRequest(h).fireAndForget()); return; }
            p.createConnectionRequest(rs).fireAndForget();
        });
    }

    private void entrerFile(Player p, File f) {
        synchronized (f) {
            if (f.ouverts.containsKey(p.getUniqueId()) || f.attente.contains(p.getUniqueId())) return;
            f.attente.addLast(p.getUniqueId());
        }
        tic();
    }

    @Subscribe
    public void arrive(ServerPostConnectEvent e) {
        Player p = e.getPlayer(); enPassage.remove(p.getUniqueId());
        p.getCurrentServer().ifPresent(sc -> {
            File f = files.get(sc.getServerInfo().getName());
            if (f != null) synchronized (f) { f.ouverts.remove(p.getUniqueId()); f.attente.remove(p.getUniqueId()); }
        });
    }

    @Subscribe
    public void parti(DisconnectEvent e) {
        UUID u = e.getPlayer().getUniqueId(); enPassage.remove(u); LobbikPassages.oublier(u);
        for (File f : files.values()) synchronized (f) { f.attente.remove(u); f.ouverts.remove(u); }
    }

    /** Connexion directe à un serveur plein (adresse tour./cube.lobbik.com) : on arrive au hub, placé dans la file. */
    @Subscribe
    public void premierServeur(PlayerChooseInitialServerEvent e) {
        e.getInitialServer().ifPresent(rs -> {
            File f = files.get(rs.getServerInfo().getName());
            if (f == null || f.libre()) return;
            proxy.getServer(hub()).ifPresent(h -> { e.setInitialServer(h); synchronized (f) { f.attente.addLast(e.getPlayer().getUniqueId()); } });
        });
    }

    private final Set<String> capaciteForcee = ConcurrentHashMap.newKeySet();   // /reseau capacite : garde la main jusqu'au redémarrage du proxy
    private void sonder() {
        for (File f : files.values()) {
            if (capaciteForcee.contains(f.serveur)) continue;
            proxy.getServer(f.serveur).ifPresent(rs -> rs.ping().whenComplete((ping, err) -> {
                if (err != null || ping == null) return;
                ping.getPlayers().ifPresent(j -> { if (j.getMax() > 0 && j.getMax() != f.capacite) { log.info("Places de {} : {} → {}", f.serveur, f.capacite, j.getMax()); f.capacite = j.getMax(); } });
            }));
        }
    }

    /* ------------------------------------------------------------ modération depuis le site (27/09/2026)
       Kripy : « le modérateur du site peut expulser et bannir des serveurs de jeu ». Toutes les 10 s, le proxy lit sur le site
       (réglage moderation = adresse steam.php?a=mc_moderation&cle=…) les bannis du réseau et les expulsions demandées :
       un banni ne passe plus la porte du proxy (donc d'aucun serveur du réseau), et il est sorti s'il est connecté. */
    private volatile Map<UUID, String> bannis = Map.of();
    private final Set<Long> expulsionsFaites = ConcurrentHashMap.newKeySet();
    private volatile boolean moderationLue = false;

    private void moderation() {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(reglages.getProperty("moderation"))).timeout(Duration.ofSeconds(8))
            .header("User-Agent", "lobbik-proxy/1.0").GET().build();
        http.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((rp, err) -> {
            if (err != null || rp == null || rp.statusCode() != 200) { if (err != null) log.debug("modération : site injoignable ({})", err.toString()); return; }
            try {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(rp.body()).getAsJsonObject();
                Map<UUID, String> b = new HashMap<>();
                for (com.google.gson.JsonElement x : o.getAsJsonArray("bannis")) {
                    com.google.gson.JsonObject j = x.getAsJsonObject();
                    b.put(UUID.fromString(j.get("uuid").getAsString()), j.has("message") ? j.get("message").getAsString() : "Banni des serveurs Lobbik.");
                }
                bannis = b; moderationLue = true;
                for (Player p : proxy.getAllPlayers()) { String m = b.get(p.getUniqueId()); if (m != null) { log.info("Banni, sorti du réseau : {}", p.getUsername()); p.disconnect(Component.text(m, NamedTextColor.RED)); } }
                for (com.google.gson.JsonElement x : o.getAsJsonArray("expulser")) {
                    com.google.gson.JsonObject j = x.getAsJsonObject();
                    long id = j.get("id").getAsLong(); if (!expulsionsFaites.add(id)) continue;
                    UUID u = UUID.fromString(j.get("uuid").getAsString());
                    String m = j.has("message") ? j.get("message").getAsString() : "Expulsé par la modération de Lobbik.";
                    proxy.getPlayer(u).ifPresent(p -> { log.info("Expulsé par la modération : {}", p.getUsername()); p.disconnect(Component.text(m, NamedTextColor.GOLD)); });
                }
                if (expulsionsFaites.size() > 5000) expulsionsFaites.clear();
            } catch (Exception ex) { log.warn("modération : réponse illisible ({})", ex.toString()); }
        });
    }
    @Subscribe
    public void connexion(LoginEvent e) {
        String m = bannis.get(e.getPlayer().getUniqueId());
        if (m != null) e.setResult(ResultedEvent.ComponentResult.denied(Component.text(m, NamedTextColor.RED)));
    }

    /** Pas de /server (ni de file du proxy) pour les joueurs : on ne passe que par les ponts et leur file d'attente. */
    @Subscribe
    public void commande(CommandExecuteEvent e) {
        if (!(e.getCommandSource() instanceof Player p)) return;
        String c = e.getCommand().trim().toLowerCase(Locale.ROOT); int i = c.indexOf(' '); if (i > 0) c = c.substring(0, i);
        if (!Set.of("server", "send", "joinqueue", "queue", "leavequeue", "dequeue", "queueadmin").contains(c)) return;
        if (Arrays.asList(reglages.getProperty("admins", "").split(",")).contains(p.getUniqueId().toString())) return;
        e.setResult(CommandExecuteEvent.CommandResult.denied());
        p.sendMessage(Component.text("On change de serveur en passant les ponts de Lobbik.", NamedTextColor.GRAY));
    }

    /* ------------------------------------------------------------ chaque seconde : files, portes, état */
    private synchronized void tic() {
        long maintenant = System.currentTimeMillis();
        for (File f : files.values()) {
            List<UUID> nouveaux = new ArrayList<>(), perdus = new ArrayList<>();
            synchronized (f) {
                f.ouverts.entrySet().removeIf(en -> { if (en.getValue() < maintenant) { perdus.add(en.getKey()); return true; } return false; });
                f.attente.removeIf(u -> proxy.getPlayer(u).isEmpty());
                while (!f.attente.isEmpty() && f.joueurs() + f.ouverts.size() < f.capacite) {
                    UUID u = f.attente.pollFirst(); f.ouverts.put(u, maintenant + ouverture() * 1000L); nouveaux.add(u);
                }
            }
            for (UUID u : nouveaux) proxy.getPlayer(u).ifPresent(p -> {
                boolean auHub = p.getCurrentServer().map(s -> s.getServerInfo().getName().equals(hub())).orElse(false);
                if (!auHub) { synchronized (f) { f.ouverts.remove(u); } return; }   // parti ailleurs entre-temps
                p.getCurrentServer().ifPresent(sc -> envoyer(sc, "tour\tcible=" + f.serveur + "\tsecondes=" + ouverture()));
            });
            for (UUID u : perdus) proxy.getPlayer(u).ifPresent(p -> p.getCurrentServer().ifPresent(sc -> {
                if (sc.getServerInfo().getName().equals(hub())) envoyer(sc, "perdu\tcible=" + f.serveur);
            }));
        }
        // état des portes, pour chaque joueur du hub (couleur du mur, rang dans la file)
        proxy.getServer(hub()).ifPresent(h -> {
            StringBuilder commun = new StringBuilder();
            commun.append("\thub=").append(h.getPlayersConnected().size());
            for (File f : files.values()) commun.append("\tnb_").append(f.serveur).append('=').append(f.joueurs()).append('/').append(f.capacite).append('/').append(f.attente.size());
            for (Player p : h.getPlayersConnected()) {
                StringBuilder b = new StringBuilder("etat").append(commun);
                for (File f : files.values()) {
                    String etat; int rang = 0; long reste = 0;
                    synchronized (f) {
                        Long jusqua = f.ouverts.get(p.getUniqueId());
                        if (jusqua != null) { etat = "O"; reste = Math.max(0, (jusqua - maintenant) / 1000); }
                        else if ((rang = f.rang(p.getUniqueId())) > 0) etat = "A";
                        else etat = f.libre() ? "L" : "P";
                    }
                    b.append('\t').append(f.serveur).append('=').append(etat).append(':').append(rang).append(':').append(f.attente.size()).append(':').append(reste);
                }
                p.getCurrentServer().ifPresent(sc -> envoyer(sc, b.toString()));
            }
        });
    }

    /** En-tête et pied de la liste des joueurs (Tab) : tout le réseau d'un coup d'œil. */
    private void entete() {
        int total = proxy.getPlayerCount();
        StringBuilder pied = new StringBuilder();
        for (RegisteredServer s : proxy.getAllServers()) {
            String n = s.getServerInfo().getName(); File f = files.get(n);
            if (pied.length() > 0) pied.append("  ·  ");
            pied.append(nomServeur(n)).append(' ').append(s.getPlayersConnected().size()).append(f != null ? "/" + f.capacite : "");
            if (f != null && !f.attente.isEmpty()) pied.append(" (file ").append(f.attente.size()).append(')');
        }
        Component haut = Component.text("Lobbik", TextColor.color(0x7CFF4F)).append(Component.text("  ·  " + total + " en ligne", NamedTextColor.GRAY));
        Component bas = Component.text(pied.toString(), NamedTextColor.GRAY).append(Component.newline()).append(Component.text("lobbik.com", TextColor.color(0x3FE0FF)));
        for (Player p : proxy.getAllPlayers()) p.sendPlayerListHeaderAndFooter(haut, bas);
    }

    /* ------------------------------------------------------------ chat commun */
    private void chat(ServerConnection sc, Player p, String texte, String tag, String couleur) {
        if (texte.isBlank()) return;
        String depuis = sc.getServerInfo().getName();
        // tag de clan (27/09/2026) donné par le serveur de départ, à la couleur du clan
        Component clan = Component.empty();
        if (tag != null && tag.matches("[\\p{L}\\p{N}_.-]{1,6}")) { TextColor tc = couleur != null && couleur.matches("#[0-9a-fA-F]{6}") ? TextColor.fromHexString(couleur) : NamedTextColor.GREEN; clan = Component.text("[" + tag + "] ", tc); }
        Component c = Component.text("[" + nomServeur(depuis) + "] ", couleurServeur(depuis)).append(clan).append(Component.text(p.getUsername(), NamedTextColor.WHITE)).append(Component.text(" : " + texte, NamedTextColor.GRAY));
        for (Player q : proxy.getAllPlayers()) {
            if (q.getCurrentServer().map(s -> s.getServerInfo().getName().equals(depuis)).orElse(true)) continue;
            q.sendMessage(c);
        }
    }
    String nomServeur(String n) { return reglages.getProperty("nom." + n, n); }
    private TextColor couleurServeur(String n) { try { return TextColor.fromHexString(reglages.getProperty("couleur." + n, "#AAAAAA")); } catch (Exception e) { return NamedTextColor.GRAY; } }

    /* ------------------------------------------------------------ outils */
    static void envoyer(ServerConnection sc, String ligne) { sc.sendPluginMessage(CANAL, ligne.getBytes(StandardCharsets.UTF_8)); }
    static Map<String, String> lire(String ligne) {
        Map<String, String> m = new HashMap<>(); String[] parts = ligne.split("\t");
        m.put("", parts[0]);
        for (int i = 1; i < parts.length; i++) { int k = parts[i].indexOf('='); if (k > 0) m.put(parts[i].substring(0, k), parts[i].substring(k + 1)); }
        return m;
    }

    /* ------------------------------------------------------------ administration (/reseau, console ou permission lobbik.admin) */
    Map<String, File> files() { return files; }
    void capacite(String serveur, int n) { File f = files.get(serveur); if (f == null) return; if (n <= 0) { capaciteForcee.remove(serveur); sonder(); } else { capaciteForcee.add(serveur); f.capacite = n; } tic(); }
    String resume() {
        StringBuilder b = new StringBuilder();
        for (File f : files.values()) b.append(f.serveur).append(" : ").append(f.joueurs()).append('/').append(f.capacite).append(", file ").append(f.attente.size()).append(", portes ouvertes ").append(f.ouverts.size()).append('\n');
        return b.toString().trim();
    }
}
