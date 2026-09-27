package hk.krp.lobbik;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Les écrans CC: Tweaked du hub (Kripy, 27/09/2026 : « de vrais ordinateurs et écrans » à la place des cartes de la version Paper).
 * Quatre ordinateurs avancés, cachés derrière ou sous des moniteurs avancés, font tourner lobbik/ecran-hub.lua (recopié du jar à
 * chaque démarrage, avec le fichier « role ») :
 *   - mur nord du salon (cadre lumineux de Decor.murEcrans) : Le Cube à gauche, La Tour à droite, comme sur la carte ;
 *   - cadres de l'arrivée (Decor.ecrans), face aux joueurs qui arrivent : les serveurs Lobbik à gauche, « En direct » à droite
 *     (progression de chaque joueur en ligne, jeu par jeu : Tour, Cube, Mer de lave).
 * Les données en direct sont écrites par le mod dans le dossier de chaque ordinateur (direct.json, toutes les 2 s) : un ordinateur
 * CC ne lit ni les fichiers du serveur ni 127.0.0.1 (règle $private). Le programme se replie sur l'API publique du site s'il se tait.
 * Lecture seule : ordinateurs verrouillés (« lock », clé = vide de structure), clic droit et casse refusés à tout joueur hors créatif.
 * Aucune dépendance de compilation à CC : blocs par leur nom et données de bloc en NBT, comme la commande /setblock.
 * Réglages facultatifs (config/lobbik.properties, valeurs par défaut = serveurs de test /srv/mctest/f-*) :
 *   ecrans.actifs=true · ecrans.{tour,cube,lave}_adresse=127.0.0.1:2560{2,3,4} (ping) ·
 *   ecrans.{tour,cube,lave}_direct=../f-tour/lobbik/tour/direct.json, ../f-cube/config/lobbik-cube-direct.json, ../f-lave/lobbik/lave/direct.json
 * Les tronçons restent forcés dans le monde : pour retirer les écrans, « forceload remove » sur (−8,−2) (−7,−2) (−8,0) (−7,0).
 */
public final class Ordinateurs {
    public static Ordinateurs I;

    /** Un écran : moniteurs de (x1, y1) à (x2, y2) dans le plan z, face au sud ; ordinateur en (ox, oy, oz). */
    record Ecran(String role, int id, int x1, int x2, int y1, int y2, int z, int ox, int oy, int oz, boolean mur) { }
    private record Etat(boolean enLigne, int joueurs, int places, String carte, long t) { }

    static final int ZN = Decor.ECRANS_Z - 1, ZA = Geo.HUB_Z + 11, X0 = Decor.ECRANS_X0;
    /** numéros d'ordinateur hors de la plage que CC attribue (ids.json part de 0) */
    static final List<Ecran> ECRANS = List.of(
        new Ecran("cube", 9101, X0, X0 + 6, 65, 70, ZN, X0 + 3, 65, ZN - 1, true),
        new Ecran("tour", 9102, X0 + 9, X0 + 15, 65, 70, ZN, X0 + 12, 65, ZN - 1, true),
        new Ecran("serveurs", 9103, Geo.HUB_X - 12, Geo.HUB_X - 5, 64, 69, ZA, Geo.HUB_X - 9, 63, ZA, false),
        new Ecran("direct", 9104, Geo.HUB_X + 5, Geo.HUB_X + 12, 64, 69, ZA, Geo.HUB_X + 8, 63, ZA, false));
    private static final int POSE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final List<String> JEUX = List.of("tour", "cube", "lave");

    private final MinecraftServer serveur; private final Config cfg; private final Path jeu;
    private final List<Path> dossiers = new ArrayList<>();
    private final Map<String, Etat> etats = new ConcurrentHashMap<>();
    private final ExecutorService ecriture = fil("lobbik-ecrans"), sondes = fil("lobbik-ecrans-sondes");
    private final AtomicBoolean ecrit = new AtomicBoolean(), sonde = new AtomicBoolean();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile long sondeT, siteT;
    private long tic;

    private Ordinateurs(MinecraftServer s, Config cfg) { this.serveur = s; this.cfg = cfg; this.jeu = FabricLoader.getInstance().getGameDir(); }
    private static ExecutorService fil(String nom) { return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, nom); t.setDaemon(true); return t; }); }

    /** Appelé une fois par Lobbik (rôle hub) : les événements des écrans, après ceux du décor (enregistrés avant). */
    public static void brancher(Config cfg) {
        if (!cfg.b("ecrans.actifs", true)) return;
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            try { I = new Ordinateurs(s, cfg); I.installer(); }
            catch (Exception e) { Lobbik.LOG.error("Écrans CC : installation impossible", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { if (I != null) { I.ecriture.shutdownNow(); I.sondes.shutdownNow(); } });
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (I != null) I.tic(); });
        UseBlockCallback.EVENT.register((joueur, monde, main, coup) -> protege(joueur, monde.getBlockState(coup.getBlockPos())) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((monde, joueur, pos, etat, be) -> !protege(joueur, etat));
    }
    /** Blocs CC: Tweaked du hub : ni ouverts, ni touchés, ni cassés par un joueur (seuls les admins en créatif passent ; le verrou demande encore la clé). */
    static boolean protege(Player p, BlockState st) {
        return !p.isCreative() && "computercraft".equals(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace());
    }

    /* ================================================================== pose (à chaque démarrage : idempotent, répare) */
    private void installer() throws IOException {
        ServerLevel w = serveur.overworld();
        HolderLookup<Block> blocs = w.holderLookup(Registries.BLOCK);
        byte[] programme;
        try (InputStream in = Ordinateurs.class.getResourceAsStream("/lobbik/ecran-hub.lua")) {
            if (in == null) throw new FileNotFoundException("lobbik/ecran-hub.lua absent du jar");
            programme = in.readAllBytes();
        }
        Path racine = serveur.getWorldPath(new LevelResource("computercraft")).resolve("computer");
        Set<Long> troncons = new TreeSet<>();
        int n = 0;
        for (Ecran e : ECRANS) {
            Path d = racine.resolve(String.valueOf(e.id()));
            Files.createDirectories(d);
            ecrireAtomique(d.resolve("startup.lua"), programme);
            ecrireAtomique(d.resolve("role"), e.role().getBytes(StandardCharsets.UTF_8));
            dossiers.add(d);
            int lw = e.x2() - e.x1() + 1, lh = e.y2() - e.y1() + 1;
            if (!e.mur()) {   // cadre de l'arrivée : un dos identique à la face (lumière + noir) pour cacher l'arrière des moniteurs
                for (int x = e.x1() - 1; x <= e.x2() + 1; x++) for (int y = e.y1(); y <= e.y2() + 1; y++)
                    n += poser(w, blocs, x, y, e.z() - 1, x < e.x1() || x > e.x2() || y > e.y2() ? "minecraft:verdant_froglight" : "minecraft:black_concrete");
            }
            // moniteurs déjà assemblés (indices, taille, bords) : aucune dépendance à l'ordre de pose
            for (int x = e.x1(); x <= e.x2(); x++) for (int y = e.y1(); y <= e.y2(); y++) {
                int xi = x - e.x1(), yi = y - e.y1();
                n += poser(w, blocs, x, y, e.z(), "computercraft:monitor_advanced[facing=south,orientation=north,state=" + bords(xi, yi, lw, lh) + "]"
                    + "{XIndex:" + xi + ",YIndex:" + yi + ",Width:" + lw + ",Height:" + lh + "}");
                troncons.add(ChunkPos.pack(x >> 4, e.z() >> 4));
            }
            if (e.mur()) for (int y = 64; y <= e.oy(); y++) n += poser(w, blocs, e.ox(), y, e.oz() - 1, "minecraft:black_concrete");   // capot derrière le mur
            n += poser(w, blocs, e.ox(), e.oy(), e.oz(), "computercraft:computer_advanced[facing=south]{ComputerId:" + e.id() + ",On:1b,Label:\"lobbik-" + e.role()
                + "\",lock:{items:\"minecraft:structure_void\"}}");
            troncons.add(ChunkPos.pack(e.ox() >> 4, e.oz() >> 4));
        }
        // tronçons toujours chargés : les ordinateurs tournent dès qu'un joueur est sur le hub, même loin des écrans
        for (long c : troncons) w.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
        Lobbik.LOG.info("Écrans CC : {} ordinateurs ({}), {} blocs posés ou réparés, {} tronçons gardés chargés", ECRANS.size(),
            ECRANS.stream().map(e -> e.role() + "#" + e.id()).toList(), n, troncons.size());
        sonder();
    }
    /** état de bord d'un bloc de moniteur (MonitorEdgeState de CC : l, r, u, d) */
    static String bords(int xi, int yi, int w, int h) {
        String s = (xi > 0 ? "l" : "") + (xi < w - 1 ? "r" : "") + (yi < h - 1 ? "u" : "") + (yi > 0 ? "d" : "");
        return s.isEmpty() ? "none" : s;
    }
    private static int poser(ServerLevel w, HolderLookup<Block> blocs, int x, int y, int z, String bloc) {
        try {
            BlockStateParser.BlockResult r = BlockStateParser.parseForBlock(blocs, bloc, true);
            BlockPos p = new BlockPos(x, y, z);
            if (r.nbt() == null && w.getBlockState(p) == r.blockState()) return 0;
            return new BlockInput(r.blockState(), r.properties().keySet(), r.nbt()).place(w, p, POSE) ? 1 : 0;
        } catch (CommandSyntaxException ex) { Lobbik.LOG.error("Écrans CC : bloc illisible {} ({})", bloc, ex.getMessage()); return 0; }
    }

    /* ================================================================== données en direct (serveur en pause = rien d'écrit) */
    private void tic() {
        if (++tic % 40 != 0) return;
        int hubJ = serveur.getPlayerCount(), hubMax = serveur.getPlayerList().getMaxPlayers();
        Map<String, String> commun = Reseau.I != null && Reseau.I.portes != null ? Reseau.I.portes.commun() : Map.of();
        if (System.currentTimeMillis() - sondeT > 5000) sonder();
        if (ecrit.compareAndSet(false, true)) ecriture.execute(() -> { try { ecrire(hubJ, hubMax, commun); } finally { ecrit.set(false); } });
    }
    /** Tour, Cube, Mer de lave : ping local (joueurs, places) toutes les 5 s ; CS2 et Valheim : API publique du site toutes les 30 s. */
    private void sonder() {
        if (!sonde.compareAndSet(false, true)) return;
        sondeT = System.currentTimeMillis();
        sondes.execute(() -> {
            try {
                for (String k : JEUX) etats.put(k, ping(cfg.s("ecrans." + k + "_adresse", defautAdresse(k))));
                if (System.currentTimeMillis() - siteT > 30_000) {
                    siteT = System.currentTimeMillis();
                    Etat cs2 = site("cs_serveur"), valheim = site("cs_serveur&jeu=892970");
                    if (cs2 != null) etats.put("cs2", cs2);
                    if (valheim != null) etats.put("valheim", valheim);
                }
            } catch (Exception e) { Lobbik.LOG.warn("Écrans CC : sonde en erreur {}", e.toString()); }
            finally { sonde.set(false); }
        });
    }
    private static String defautAdresse(String k) { return "127.0.0.1:" + switch (k) { case "tour" -> 25602; case "cube" -> 25603; default -> 25604; }; }
    private static String defautDirect(String k) {
        return switch (k) { case "tour" -> "../f-tour/lobbik/tour/direct.json"; case "cube" -> "../f-cube/config/lobbik-cube-direct.json"; default -> "../f-lave/lobbik/lave/direct.json"; };
    }

    private void ecrire(int hubJ, int hubMax, Map<String, String> commun) {
        long t = System.currentTimeMillis();
        StringBuilder b = new StringBuilder(8192).append("{\"t\":").append(t).append(",\"v\":1,\"serveurs\":{\"hub\":{\"en_ligne\":true,\"joueurs\":").append(hubJ)
            .append(",\"places\":").append(hubMax).append('}');
        Map<String, String> direct = new HashMap<>();
        for (String k : JEUX) direct.put(k, lireDirect(k));
        for (String k : List.of("tour", "cube", "lave", "cs2", "valheim")) {
            Etat e = etats.get(k);
            if (e == null || t - e.t() > 60_000) continue;
            if (k.equals("lave") && !e.enLigne() && direct.get(k) == null) continue;   // pas encore ouverte : l'écran dit « bientôt »
            b.append(",\"").append(k).append("\":{\"en_ligne\":").append(e.enLigne()).append(",\"joueurs\":").append(e.joueurs()).append(",\"places\":").append(e.places());
            String nb = commun.get("nb_" + k);   // joueurs/capacité/attente, vu par le proxy
            if (nb != null && nb.split("/").length == 3) b.append(",\"attente\":").append(entier(nb.split("/")[2]));
            if (e.carte() != null) b.append(",\"carte\":").append(Hub.j(e.carte()));
            b.append('}');
        }
        b.append('}');
        for (String k : JEUX) b.append(",\"").append(k).append("\":").append(direct.get(k) == null ? "null" : direct.get(k));
        byte[] json = b.append('}').toString().getBytes(StandardCharsets.UTF_8);
        for (Path d : dossiers) {
            try { ecrireAtomique(d.resolve("direct.json"), json); } catch (IOException e) { Lobbik.LOG.warn("Écrans CC : écriture impossible dans {} ({})", d, e.toString()); }
        }
    }
    /** direct.json d'un jeu (écrit par le serveur du jeu, même machine), tel quel s'il ressemble à un objet JSON */
    private String lireDirect(String k) {
        try {
            Path f = jeu.resolve(cfg.s("ecrans." + k + "_direct", defautDirect(k))).normalize();
            if (!Files.isRegularFile(f) || Files.size(f) > 256_000) return null;
            String s = Files.readString(f, StandardCharsets.UTF_8).trim();
            return s.startsWith("{") && s.endsWith("}") ? s : null;
        } catch (Exception e) { return null; }
    }
    private static void ecrireAtomique(Path f, byte[] contenu) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, contenu);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /* ================================================================== sondes */
    /** Server List Ping (handshake + status) : joueurs en ligne et places du serveur, sans se connecter. */
    private static Etat ping(String adresse) {
        long t = System.currentTimeMillis();
        int i = adresse.lastIndexOf(':');
        String hote = i > 0 ? adresse.substring(0, i) : adresse; int port = i > 0 ? entier(adresse.substring(i + 1)) : 25565;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hote, port), 1500); s.setSoTimeout(2500);
            ByteArrayOutputStream p = new ByteArrayOutputStream(), paquet = new ByteArrayOutputStream();
            byte[] h = hote.getBytes(StandardCharsets.UTF_8);
            p.write(0x00); varint(p, -1); varint(p, h.length); p.write(h); p.write(port >> 8); p.write(port & 0xFF); varint(p, 1);
            varint(paquet, p.size()); p.writeTo(paquet); paquet.write(0x01); paquet.write(0x00);
            s.getOutputStream().write(paquet.toByteArray()); s.getOutputStream().flush();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            lireVarint(in);
            if (lireVarint(in) != 0x00) return new Etat(false, 0, 0, null, t);
            int n = lireVarint(in);
            if (n <= 0 || n > 1 << 20) return new Etat(false, 0, 0, null, t);
            byte[] b = new byte[n]; in.readFully(b);
            String j = new String(b, StandardCharsets.UTF_8);
            return new Etat(true, entier(trouver(j, "\"online\"\\s*:\\s*(\\d+)")), entier(trouver(j, "\"max\"\\s*:\\s*(\\d+)")), null, t);
        } catch (Exception e) { return new Etat(false, 0, 0, null, t); }
    }
    /** État d'un serveur de jeu vu par le site (action publique cs_serveur) : en ligne, joueurs, places, carte. */
    private Etat site(String action) {
        long t = System.currentTimeMillis();
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(cfg.s("site", "https://lobbik.com") + "/auth/steam.php?a=" + action)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "lobbik-ecrans/1.0").GET().build();
            HttpResponse<String> rp = http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (rp.statusCode() != 200) return null;
            String j = rp.body();
            boolean enLigne = "true".equals(trouver(j, "\"en_ligne\":(true|false)"));
            String carte = action.contains("892970") ? null : trouver(j, "\"carte\":\"([^\"]{0,40})\"");
            return new Etat(enLigne, entier(trouver(j, "\"joueurs\":(\\d+)")), entier(trouver(j, "\"places\":(\\d+)")), carte, t);
        } catch (Exception e) { return null; }
    }
    private static void varint(OutputStream o, int v) throws IOException { while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; } o.write(v); }
    private static int lireVarint(DataInputStream in) throws IOException {
        int v = 0, k = 0; byte b;
        do { b = in.readByte(); v |= (b & 0x7F) << k; k += 7; if (k > 35) throw new IOException("varint trop long"); } while ((b & 0x80) != 0);
        return v;
    }
    private static String trouver(String s, String re) { Matcher m = Pattern.compile(re).matcher(s); return m.find() ? m.group(1) : null; }
    private static int entier(String s) { try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
}
