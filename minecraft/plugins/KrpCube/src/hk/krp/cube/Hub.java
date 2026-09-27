package hk.krp.cube;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Liaison avec Lobbik : même mécanisme que La Tour (liste des comptes Minecraft liés, codes de liaison, événements).
 *  Un compte lié pour La Tour l'est aussi pour le Cube : c'est le même compte Minecraft, la même liste. */
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
    public boolean configure() { return cle != null && !cle.isEmpty(); }

    public void relireLies() {
        if (!configure()) return;
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_permis&cle=" + cle)).timeout(Duration.ofSeconds(10)).header("User-Agent", "krp-cube/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (rp.statusCode() != 200) { log.warning("Lobbik : liste des liés en erreur " + rp.statusCode()); return; }
            Set<UUID> n = ConcurrentHashMap.newKeySet();
            for (String l : rp.body().split("\n")) { l = l.trim(); if (l.length() == 36) try { n.add(UUID.fromString(l)); } catch (Exception ignored) {} }
            lies = n; liesLu = System.currentTimeMillis();
        } catch (Exception e) { log.warning("Lobbik injoignable (liste des liés) : " + e.getMessage()); }
    }
    public void envoyer(String type, String json) {
        if (!envoi) return;
        if (!configure()) return;
        String corps = "{\"cle\":\"" + cle + "\",\"type\":\"" + type + "\",\"donnees\":" + json + "}";
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url + "/auth/steam.php?a=mc_evenement")).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("User-Agent", "krp-cube/1.0").POST(HttpRequest.BodyPublishers.ofString(corps, StandardCharsets.UTF_8)).build();
        http.sendAsync(rq, HttpResponse.BodyHandlers.ofString()).whenComplete((rp, e) -> { if (e != null) log.warning("Lobbik : envoi " + type + " raté : " + e.getMessage()); });
    }
    public static String j(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
