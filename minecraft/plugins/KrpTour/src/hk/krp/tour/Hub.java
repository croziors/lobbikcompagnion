package hk.krp.tour;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

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
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_permis&cle=" + cle)).timeout(Duration.ofSeconds(10)).header("User-Agent", "krp-tour/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (rp.statusCode() != 200) { log.warning("Hub : liste des liés en erreur " + rp.statusCode()); return; }
            Set<UUID> n = ConcurrentHashMap.newKeySet();
            for (String l : rp.body().split("\n")) { l = l.trim(); if (l.length() == 36) try { n.add(UUID.fromString(l)); } catch (Exception ignored) {} }
            lies = n; liesLu = System.currentTimeMillis();
        } catch (Exception e) { log.warning("Hub injoignable (liste des liés) : " + e.getMessage()); }
    }

    /** Envoie un événement JSON au Hub (feu et oublie). */
    public void envoyer(String type, String json) {
        if (!envoi) return;
        String corps = "{\"cle\":\"" + cle + "\",\"type\":\"" + type + "\",\"donnees\":" + json + "}";
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_evenement")).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("User-Agent", "krp-tour/1.0").POST(HttpRequest.BodyPublishers.ofString(corps, StandardCharsets.UTF_8)).build();
        http.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((rp, e) -> { if (e != null) log.warning("Hub : envoi " + type + " raté : " + e.getMessage()); });
    }

    /** Messages du salon #minecraft du Hub depuis le dernier id lu (sondage toutes les 3 s). */
    public String lireChat(long depuis) {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_chat&cle=" + cle + "&depuis=" + depuis)).timeout(Duration.ofSeconds(8)).header("User-Agent", "krp-tour/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            return rp.statusCode() == 200 ? rp.body() : null;
        } catch (Exception e) { return null; }
    }
    public static String j(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
