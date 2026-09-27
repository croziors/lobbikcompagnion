package hk.krp.lobbik;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Billets d'arrivée : juste avant de faire passer un joueur par le pont, le proxy dépose ici (HTTP local, clé partagée) le numéro
 * d'entité que son client connaît déjà et la position exacte où il franchit la porte. À l'arrivée (PlayerList.placeNewPlayer,
 * voir PlayerListMixin), on donne ce numéro au joueur et on le pose à cet endroit : le client n'a pas à changer de monde.
 */
public final class Billets {
    public record Billet(UUID uuid, int id, double x, double y, double z, float yaw, float pitch, String mode, String depuis, long expire) { }
    private static final Map<UUID, Billet> BILLETS = new ConcurrentHashMap<>();
    /** joueurs arrivés par le pont (d'où ils venaient), lu par les jeux (Tour, Cube) à l'arrivée */
    public static final Map<UUID, String> PAR_LE_PONT = new ConcurrentHashMap<>();
    private static HttpServer http;

    public static void decalerCompteur(int base) {
        try {
            Field f = ServerLevel.class.getDeclaredField("ENTITY_COUNTER"); f.setAccessible(true);
            AtomicInteger n = (AtomicInteger) f.get(null);
            if (n.get() < base) n.set(base);
            Lobbik.LOG.info("Compteur d'entités de ce serveur à partir de {}", n.get());
        } catch (Exception e) { Lobbik.LOG.warn("Compteur d'entités introuvable ({}) : les numéros pourraient se croiser entre serveurs", e.toString()); }
    }

    public static void ouvrir(int port, String cle) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        http.createContext("/ticket", ex -> {
            int code = 403; String rep = "cle";
            try {
                if ("POST".equals(ex.getRequestMethod()) && cle.equals(ex.getRequestHeaders().getFirst("X-Cle"))) {
                    Map<String, String> m = new HashMap<>();
                    for (String kv : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                        int i = kv.indexOf('='); if (i > 0) m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                    }
                    UUID u = UUID.fromString(m.get("uuid"));
                    BILLETS.put(u, new Billet(u, Integer.parseInt(m.getOrDefault("id", "0")), d(m, "x"), d(m, "y"), d(m, "z"), (float) d(m, "yaw"), (float) d(m, "pitch"),
                        m.getOrDefault("mode", "pont"), m.getOrDefault("depuis", ""), System.currentTimeMillis() + 20_000));
                    code = 200; rep = "ok";
                }
            } catch (Exception e) { code = 400; rep = String.valueOf(e); }
            byte[] o = rep.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, o.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(o); }
        });
        http.start();
    }
    public static void fermer() { if (http != null) http.stop(0); }
    private static double d(Map<String, String> m, String k) { try { return Double.parseDouble(m.get(k)); } catch (Exception e) { return 0; } }

    /** Appelé par PlayerListMixin juste avant l'entrée du joueur dans le monde (après chargement de ses données). */
    public static void arrivee(ServerPlayer p) {
        Billet b = BILLETS.remove(p.getUUID());
        if (b == null || b.expire() < System.currentTimeMillis()) { PAR_LE_PONT.remove(p.getUUID()); return; }
        PAR_LE_PONT.put(p.getUUID(), b.depuis());
        boolean numero = false;
        if (b.id() > 0 && p.level().getEntity(b.id()) == null) { p.setId(b.id()); numero = true; }
        p.snapTo(b.x(), b.y(), b.z(), b.yaw(), b.pitch());
        Lobbik.LOG.info("Arrivée de {} depuis {} : numéro d'entité {}", p.getPlainTextName(), b.depuis(), numero ? b.id() + " repris" : "nouveau");
    }
}
