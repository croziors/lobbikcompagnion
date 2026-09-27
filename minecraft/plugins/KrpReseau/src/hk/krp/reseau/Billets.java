package hk.krp.reseau;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Billets d'arrivée : juste avant de faire passer un joueur par le pont, le proxy dépose ici (HTTP local, clé partagée)
 * le numéro d'entité que son client connaît déjà et la position exacte où il franchit la porte. À la connexion, on
 * donne ce numéro au joueur (le client n'a pas à changer de monde) et on le fait apparaître à cet endroit précis.
 */
final class Billets {
    record Billet(UUID uuid, int id, double x, double y, double z, float yaw, float pitch, String mode, String depuis, long expire) {
        Location lieu(World w) { return new Location(w, x, y, z, yaw, pitch); }
    }

    private final Map<UUID, Billet> billets = new ConcurrentHashMap<>();
    private final Logger log; private HttpServer http;
    private Method getHandle, setId, getEntityLevel, getHandleWorld;

    Billets(Logger log) { this.log = log; }

    /** Décale le compteur d'entités de ce serveur, pour qu'un numéro venu d'un autre serveur n'y soit jamais déjà pris. */
    void decalerCompteur(int base) {
        for (String c : new String[]{"net.minecraft.server.level.ServerLevel", "net.minecraft.world.level.Level", "net.minecraft.world.entity.Entity"}) {
            try {
                Field f = Class.forName(c).getDeclaredField("ENTITY_COUNTER"); f.setAccessible(true);
                AtomicInteger n = (AtomicInteger) f.get(null);
                if (n.get() < base) n.set(base);
                log.info("Compteur d'entités de ce serveur à partir de " + n.get() + " (" + c + ")");
                return;
            } catch (NoSuchFieldException | ClassNotFoundException ignored) {
            } catch (Exception e) { log.warning("Compteur d'entités : " + e); return; }
        }
        log.warning("Compteur d'entités introuvable : les numéros pourraient se croiser entre serveurs");
    }

    void ouvrir(int port, String cle) throws IOException {
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
                    Billet b = new Billet(u, Integer.parseInt(m.getOrDefault("id", "0")), d(m, "x"), d(m, "y"), d(m, "z"), (float) d(m, "yaw"), (float) d(m, "pitch"),
                        m.getOrDefault("mode", "pont"), m.getOrDefault("depuis", ""), System.currentTimeMillis() + 20_000);
                    billets.put(u, b); code = 200; rep = "ok";
                }
            } catch (Exception e) { code = 400; rep = String.valueOf(e); }
            byte[] o = rep.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, o.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(o); }
        });
        http.start();
    }
    void fermer() { if (http != null) http.stop(0); }
    private static double d(Map<String, String> m, String k) { try { return Double.parseDouble(m.get(k)); } catch (Exception e) { return 0; } }

    Billet voir(UUID u) { Billet b = billets.get(u); if (b != null && b.expire() < System.currentTimeMillis()) { billets.remove(u); return null; } return b; }
    Billet prendre(UUID u) { Billet b = voir(u); billets.remove(u); return b; }

    /** Donne au joueur (pas encore dans le monde) le numéro d'entité que son client connaît déjà. */
    boolean appliquerNumero(Player p, int id, World w) {
        if (id <= 0) return false;
        try {
            if (getHandle == null) {
                getHandle = p.getClass().getMethod("getHandle");
                setId = Class.forName("net.minecraft.world.entity.Entity").getDeclaredMethod("setId", int.class); setId.setAccessible(true);
                getHandleWorld = w.getClass().getMethod("getHandle");
                getEntityLevel = Class.forName("net.minecraft.world.level.Level").getMethod("getEntity", int.class);
            }
            Object niveau = getHandleWorld.invoke(w);
            if (getEntityLevel.invoke(niveau, id) != null) { log.warning("Numéro d'entité " + id + " déjà pris ici : passage ordinaire pour " + p.getName()); return false; }
            setId.invoke(getHandle.invoke(p), id);
            return true;
        } catch (Exception e) { log.warning("Numéro d'entité non appliqué pour " + p.getName() + " : " + e); return false; }
    }
}
