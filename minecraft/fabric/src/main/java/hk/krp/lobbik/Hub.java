package hk.krp.lobbik;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;

/** Liaison avec le KRP Hub : liste des comptes Minecraft liés (membres), codes de liaison, scores. Tout en asynchrone. */
public final class Hub {
    private final String url, cle; private final Logger log; private final boolean envoi;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private volatile Set<UUID> lies = ConcurrentHashMap.newKeySet();
    private volatile long liesLu = 0;
    public Hub(String url, String cle, Logger log) { this(url, cle, true, log); }
    /** envoi = false (serveur de test du réseau Lobbik, 27/09/2026) : on lit la liste des membres, on n'envoie rien au site. */
    public Hub(String url, String cle, boolean envoi, Logger log) { this.url = url; this.cle = cle; this.envoi = envoi; this.log = log; }

    public boolean estLie(UUID u) { return lies.contains(u); }
    public boolean listeChargee() { return liesLu > 0; }

    /** Relit la liste des UUID liés (à appeler hors du fil principal, toutes les 60 s). */
    public void relireLies() {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_permis&cle=" + cle)).timeout(Duration.ofSeconds(10)).header("User-Agent", "lobbik-fabric/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (rp.statusCode() != 200) { log.warn("Hub : liste des liés en erreur " + rp.statusCode()); return; }
            Set<UUID> n = ConcurrentHashMap.newKeySet();
            for (String l : rp.body().split("\n")) { l = l.trim(); if (l.length() == 36) try { n.add(UUID.fromString(l)); } catch (Exception ignored) {} }
            lies = n; liesLu = System.currentTimeMillis();
        } catch (Exception e) { log.warn("Hub injoignable (liste des liés) : " + e.getMessage()); }
        relireClans();
    }

    /* Tags de clan (27/09/2026, Kripy : « le nom du clan quand on se connecte ») : uuid → {TAG, #couleur}, relus avec la liste des liés. */
    private volatile Map<UUID, String[]> clans = new ConcurrentHashMap<>();
    public String[] clan(UUID u) { return clans.get(u); }
    public void relireClans() {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_clans&cle=" + cle)).timeout(Duration.ofSeconds(10)).header("User-Agent", "lobbik-reseau/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (rp.statusCode() != 200) return;
            Map<UUID, String[]> n = new ConcurrentHashMap<>();
            for (String l : rp.body().split("\n")) { String[] t = l.trim().split("\t"); if (t.length == 3 && t[0].length() == 36) try { n.put(UUID.fromString(t[0]), new String[]{t[1], t[2]}); } catch (Exception ignored) {} }
            clans = n;
        } catch (Exception ignored) { }
        relireReussites();
    }
    /* Badges de réussite (27/09/2026) : uuid → jeux terminés (tour, cube, lave), relus avec les clans. */
    private volatile Map<UUID, Set<String>> reussites = new ConcurrentHashMap<>();
    public Set<String> reussites(UUID u) { return reussites.getOrDefault(u, Set.of()); }
    public void relireReussites() {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_reussites&cle=" + cle)).timeout(Duration.ofSeconds(10)).header("User-Agent", "lobbik-reseau/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (rp.statusCode() != 200) return;
            Map<UUID, Set<String>> n = new ConcurrentHashMap<>();
            for (String l : rp.body().split("\n")) { String[] t = l.trim().split("\t"); if (t.length == 2 && t[0].length() == 36) try { n.put(UUID.fromString(t[0]), Set.of(t[1].split(","))); } catch (Exception ignored) {} }
            reussites = n;
        } catch (Exception ignored) { }
    }

    /** Envoie un événement JSON au Hub (feu et oublie). */
    public void envoyer(String type, String json) {
        if (!envoi) return;
        String corps = "{\"cle\":\"" + cle + "\",\"type\":\"" + type + "\",\"donnees\":" + json + "}";
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_evenement")).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("User-Agent", "lobbik-fabric/1.0").POST(HttpRequest.BodyPublishers.ofString(corps, StandardCharsets.UTF_8)).build();
        http.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((rp, e) -> { if (e != null) log.warn("Hub : envoi " + type + " raté : " + e.getMessage()); });
    }

    /** Messages du salon #minecraft du Hub depuis le dernier id lu (sondage toutes les 3 s). */
    public String lireChat(long depuis) {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_chat&cle=" + cle + "&depuis=" + depuis)).timeout(Duration.ofSeconds(8)).header("User-Agent", "lobbik-fabric/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            return rp.statusCode() == 200 ? rp.body() : null;
        } catch (Exception e) { return null; }
    }
    public static String j(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
