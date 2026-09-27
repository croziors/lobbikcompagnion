package hk.krp.lobbik.cube;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import hk.krp.lobbik.Billets;
import hk.krp.lobbik.Config;
import hk.krp.lobbik.Geo;
import hk.krp.lobbik.Hub;
import hk.krp.lobbik.Lobbik;
import hk.krp.lobbik.Outils;
import hk.krp.lobbik.Reseau;
import hk.krp.lobbik.cube.Labyrinthe.Piege;
import hk.krp.lobbik.cube.Labyrinthe.Salle;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Le Cube (23/09/2026, demande de Maxime) : on se réveille dans une salle cubique ; chaque salle se scelle quand on y entre.
 *  Comme dans le film, c'est la SALLE qui tue (depuis le 23/09 au soir) : les salles sûres forment un labyrinthe 3D (voir Plan),
 *  toutes les autres tuent, toujours, par quelque porte qu'on y entre, et ressortir ne sauve pas. Aucun numéro, aucune indication.
 *  On gagne par salles traversées ; la sortie est quelque part.
 *  Port Fabric du plugin KrpCube (27/09/2026) : toujours en mode réseau Lobbik (Cube à côté du hub, pont, vestibule, bouton de
 *  sortie, habillage extérieur). Actif seulement quand config/lobbik.properties dit role=cube ; réglages « cube.* » au même endroit.
 *  Tout tourne sur le fil du serveur (tick, événements, commandes) : un petit ordonnanceur remplace celui de Bukkit, et les joueurs
 *  sont toujours retrouvés par leur UUID (l'objet ServerPlayer change à chaque réapparition). */
public final class Cube {
    public static Cube I;
    private static final Identifier PHASE = Identifier.fromNamespaceAndPath("lobbik", "cube");

    /** Branche le Cube sur les événements Fabric — appelé une seule fois par Lobbik.onInitializeServer quand role=cube.
     *  Démarrage, tick et arrivée passent APRÈS ceux du réseau (phase lobbik:cube) : Reseau.I et le décor commun existent déjà. */
    public static void brancher() {
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        ServerLifecycleEvents.SERVER_STARTED.register(PHASE, Cube::demarrer);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { if (I != null) I.arreter(); });
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        ServerTickEvents.END_SERVER_TICK.register(PHASE, s -> { if (I != null) I.tic(); });
        ServerPlayConnectionEvents.JOIN.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        ServerPlayConnectionEvents.JOIN.register(PHASE, (h, envoi, s) -> { if (I != null) I.rejoint(h.getPlayer()); });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> { if (I != null) I.quitte(h.getPlayer()); });
        UseBlockCallback.EVENT.register((j, niveau, main, clic) -> I != null && j instanceof ServerPlayer p ? I.clic(p, main, clic) : InteractionResult.PASS);
        ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> { if (I != null && e instanceof ServerPlayer p) I.mort(p); });
        ServerPlayerEvents.AFTER_RESPAWN.register((ancien, nouveau, vivant) -> { if (I != null) I.reapparu(nouveau); });
        // le sol brûlant ne blesse pas qui s'accroupit (déjà vrai en vanilla ; gardé comme dans le plugin)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, n) -> I == null || !(e instanceof ServerPlayer p && source.is(DamageTypes.HOT_FLOOR) && p.isShiftKeyDown()));
        PlayerBlockBreakEvents.BEFORE.register((niveau, j, pos, st, be) -> I == null || j instanceof ServerPlayer p && I.estOp(p));   // rien ne se casse
        CommandRegistrationCallback.EVENT.register((d, contexte, selection) -> commandes(d));
    }
    private static void demarrer(MinecraftServer s) {
        if (Reseau.I == null) { Lobbik.LOG.error("Le Cube ne démarre pas : le réseau (Reseau.I) n'est pas prêt"); return; }
        try { Cube c = new Cube(Reseau.I); c.lancer(); I = c; }
        catch (RuntimeException e) { Lobbik.LOG.error("Le Cube n'a pas pu démarrer", e); }   // une exception ici arrêterait tout le serveur
    }

    /* ------------------------------------------------------------------ état */
    private final Reseau r; private final MinecraftServer serveur; private final ServerLevel monde; private final Config cfg;
    final Labyrinthe lab;
    private final Path dossier = FabricLoader.getInstance().getConfigDir();
    private Path marque, habille;
    private final Set<UUID> admins = new HashSet<>();
    private final double[] vestibule, sortiePont;
    private final Map<UUID, Salle> ou = new HashMap<>();
    private final Map<UUID, Set<Integer>> visitees = new HashMap<>();             // salles distinctes traversées dans la tentative en cours (le score)
    private final Map<UUID, Long> prochainFrisson = new HashMap<>();             // ambiance : prochain événement (ms)
    private final Map<Integer, Integer> scelleeJusquA = new HashMap<>();          // id de salle → tic de déverrouillage (les trappes restent fermées : au joueur de les rouvrir)
    private final Map<UUID, Long> debut = new HashMap<>();                       // début de la tentative en cours
    private final Map<UUID, Integer> morts = new HashMap<>();
    private final Map<UUID, String> causeMort = new HashMap<>();
    private final Set<UUID> livreDonne = new HashSet<>();
    private final Random alea = new Random();
    private int tic = 0;
    private final Map<Long, Long> bascules = new HashMap<>();
    private final Map<UUID, Object> condamnes = new HashMap<>();                 // joueur entré dans une salle mortelle → jeton de sa mise à mort (une seule à la fois)
    private final Map<UUID, Integer> derniereSure = new HashMap<>();             // dernière salle sûre traversée (avancement envoyé au site)
    private final Map<UUID, String> codes = new HashMap<>();

    private Cube(Reseau r) {
        this.r = r; serveur = r.serveur; monde = r.monde; cfg = r.cfg;
        // réseau Lobbik : le Cube posé à côté du hub (Geo : −300, −50), entrée par le vestibule au bout du pont — toujours, en Fabric
        Labyrinthe.X0 = cfg.i("cube.origine_x", Geo.CUBE_X0); Labyrinthe.Z0 = cfg.i("cube.origine_z", Geo.CUBE_Z0);
        Labyrinthe.HABILLAGE = true;   // tuiles de couleur et contours lumineux dehors, vus depuis le hub
        vestibule = nombres(cfg.s("cube.vestibule", (Geo.VESTIBULE_X1 + 1) + "," + (Geo.VESTIBULE_X2 - 1) + "," + -(Geo.VESTIBULE_DEMI - 1) + "," + (Geo.VESTIBULE_DEMI - 1) + ",64,68"));
        sortiePont = nombres(cfg.s("cube.sortie_pont", "-186.5,64,0.5,-90"));
        lab = new Labyrinthe(cfg.l("cube.graine", 20260923L), Math.max(2, cfg.i("cube.largeur", 10)), Math.max(2, cfg.i("cube.niveaux", 10)),
                             Math.max(1, cfg.i("cube.profondeur", 10)), cfg.i("cube.pieges", 20));
        for (String u : cfg.s("cube.admins", "caa13678-f5cd-4801-bbaf-f4fe4bc5924f").split("[,; ]+")) try { if (!u.isBlank()) admins.add(UUID.fromString(u.trim())); } catch (Exception e) { Lobbik.LOG.warn("cube.admins : UUID illisible « {} »", u); }
    }

    private void lancer() {
        // écran de mort normal (réapparition instantanée = ciel vide chez le client) ; on ne se soigne que dans la salle blanche, et un peu dans les salles sûres
        for (String g : new String[]{"immediate_respawn false", "spawn_mobs false", "spawn_monsters false", "show_advancement_messages false", "keep_inventory true",
                                     "show_death_messages true", "respawn_radius 0", "natural_health_regeneration false"}) commande("gamerule " + g);
        commande("difficulty normal"); commande("weather clear");
        Vec3 c = lab.depart.centre(); commande("setworldspawn " + (int) Math.floor(c.x) + " " + (int) c.y + " " + (int) Math.floor(c.z));
        chargerRecords();
        Lobbik.LOG.info("Plan du Cube : {}", lab.plan.resume());
        // le marqueur porte l'empreinte du plan (arbre + départ + sortie + dimensions) et l'origine : nouvelle graine ⇒ reconstruction
        marque = dossier.resolve("lobbik-cube-" + lab.plan.empreinte() + "@" + Labyrinthe.X0 + "," + Labyrinthe.Z0 + ".ok");
        habille = dossier.resolve("lobbik-cube-habillage-1-" + lab.plan.empreinte() + ".ok");
        if (!Files.exists(marque)) construire(true, null);
        else if (!Files.exists(habille)) construire(false, null);
        else pret();
        if (!cleSite()) Lobbik.LOG.warn("Pas de cle_site dans config/lobbik.properties : le Cube est OUVERT À TOUS et n'envoie rien au site.");
        chaque(20, 1200, this::relireMembres);
        chaque(60, 72000, () -> { if (pret) envoyerCarte(); });   // la carte pour la vue 3D du site, au démarrage puis toutes les heures
        chaque(200, 20, this::frissons);
        chaque(100, 3, this::clignoter);
        chaque(300, 40, this::musique);
        chaque(20, 4, this::suivre);
        chaque(40, 20, this::barre);
        chaque(60, 60, this::ecrireDirect);                        // écrans du hub : config/lobbik-cube-direct.json
        chaque(100, 60, this::envoyerPositions);                   // cube_positions toutes les 3 s
        chaque(100, 100, () -> { for (ServerPlayer p : joueurs()) { p.getFoodData().setFoodLevel(20); p.getFoodData().setSaturation(0f); } });   // pas de faim
        chaque(1200, 1200, () -> { if (recordsModifies) sauverRecords(); });
        Lobbik.LOG.info("Le Cube prêt — {} salles, {} sûres, {} mortelles, chemin le plus court de {} salles jusqu'à la sortie.",
            lab.salles.size(), lab.salles.size() - lab.nbMortelles(), lab.nbMortelles(), lab.plan.chemin.length);
    }
    private void arreter() {
        sauverRecords();
        if (!chantier.isEmpty()) { chantier.clear(); forcer(false); Lobbik.LOG.warn("Arrêt pendant la construction du Cube : elle reprendra au prochain démarrage."); }
    }
    private void commande(String c) { serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack().withSuppressedOutput(), c); }

    /* ------------------------------------------------------------------ ordonnanceur (remplace BukkitScheduler) */
    private record Tache(long quand, long ordre, Runnable r) { }
    private final PriorityQueue<Tache> taches = new PriorityQueue<>(Comparator.comparingLong(Tache::quand).thenComparingLong(Tache::ordre));
    private long ordre;
    private void plus(int delai, Runnable r) { taches.add(new Tache(tic + Math.max(1, delai), ordre++, r)); }
    /** plus tard, pour ce joueur s'il est toujours là (retrouvé par son UUID : l'objet change à la réapparition) */
    private void plus(int delai, UUID u, Consumer<ServerPlayer> f) { plus(delai, () -> { ServerPlayer p = joueur(u); if (p != null) f.accept(p); }); }
    private void chaque(int debut, int periode, Runnable r) { plus(debut, new Runnable() { @Override public void run() { try { r.run(); } finally { plus(periode, this); } } }); }

    void tic() {
        tic++;
        if (!chantier.isEmpty()) chantier();
        for (Tache t; (t = taches.peek()) != null && t.quand() <= tic; ) {
            taches.poll();
            try { t.r().run(); } catch (RuntimeException e) { Lobbik.LOG.warn("Cube : tâche en erreur", e); }
        }
        if (!figes.isEmpty()) tenirFiges();
    }
    private ServerPlayer joueur(UUID u) { return serveur.getPlayerList().getPlayer(u); }
    private List<ServerPlayer> joueurs() { return serveur.getPlayerList().getPlayers(); }
    private static String nom(ServerPlayer p) { return p.getPlainTextName(); }

    /* ------------------------------------------------------------------ construction, par petites étapes */
    // 1 000 salles × 1 000 blocs d'un bloc : d'un seul tenant, le chien de garde du serveur (60 s) pourrait l'arrêter en pleine
    // construction, et le marqueur n'étant pas posé, tout recommencerait au démarrage suivant. On construit donc ~30 ms par tick.
    private final ArrayDeque<Runnable> chantier = new ArrayDeque<>();
    private boolean pret;
    private long chantierDebut;
    /** (Re)construit tout le Cube (chaque cellule est réécrite entièrement : l'ancien décor disparaît) ou seulement l'habillage. */
    private void construire(boolean coques, CommandSourceStack demandeur) {
        if (!chantier.isEmpty()) { if (demandeur != null) demandeur.sendSystemMessage(t("Une construction du Cube est déjà en cours.", ROUGE)); return; }
        pret = false; chantierDebut = System.currentTimeMillis();
        if (coques) Lobbik.LOG.info("Construction du Cube : {} salles ({} × {} niveaux × {})…", lab.salles.size(), lab.nx, lab.ny, lab.nz);
        else Lobbik.LOG.info("Habillage extérieur du Cube…");
        forcer(true);
        chantier.addAll(lab.etapes(monde, coques, Labyrinthe.HABILLAGE));
        chantier.add(() -> finConstruction(coques, demandeur));
    }
    private void chantier() { long t0 = System.nanoTime(); while (!chantier.isEmpty() && System.nanoTime() - t0 < 30_000_000L) chantier.poll().run(); }
    /** garde chargés les tronçons du Cube pendant la construction (sinon chargés et déchargés à chaque tick) */
    private void forcer(boolean oui) {
        int x1 = (Labyrinthe.X0 - 1) >> 4, x2 = (Labyrinthe.X0 + lab.nx * Labyrinthe.P) >> 4, z1 = (Labyrinthe.Z0 - 1) >> 4, z2 = (Labyrinthe.Z0 + lab.nz * Labyrinthe.P) >> 4;
        for (int cx = x1; cx <= x2; cx++) for (int cz = z1; cz <= z2; cz++) monde.setChunkForced(cx, cz, oui);
    }
    private void finConstruction(boolean coques, CommandSourceStack demandeur) {
        forcer(false);
        try {
            if (coques) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dossier, "lobbik-cube-*.ok")) { for (Path f : ds) if (!f.getFileName().toString().startsWith("lobbik-cube-habillage")) Files.deleteIfExists(f); }
                Files.writeString(marque, "1");
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dossier, "lobbik-cube-habillage-*.ok")) { for (Path f : ds) Files.deleteIfExists(f); }
            Files.writeString(habille, "1");
        } catch (Exception e) { Lobbik.LOG.warn("Cube : marqueur non écrit ({})", e.toString()); }
        if (coques) {
            scelleeJusquA.clear(); visitees.clear(); debut.clear(); ou.clear(); derniereSure.clear(); condamnes.clear();
            for (ServerPlayer p : joueurs()) { UUID u = p.getUUID(); if (!surLePont.contains(u)) teleporter(p, lab.depart.centre(), 0f); rec(u).plan = lab.plan.empreinte(); }   // tout le monde au départ (sauf qui est dehors, sur le pont)
            sauverRecords();
            Lobbik.LOG.info("Cube construit en {} ms. Départ {}, sortie {} (niveau {}).", System.currentTimeMillis() - chantierDebut, lab.depart.coord(), lab.sortie.coord(), lab.sortie.j + 1);
        } else Lobbik.LOG.info("Habillage extérieur posé (tuiles de couleur, contours lumineux) en {} ms.", System.currentTimeMillis() - chantierDebut);
        pret();
        envoyerCarte();
        if (demandeur != null) demandeur.sendSystemMessage(t("Cube reconstruit (même plan : pour un autre labyrinthe, changer « cube.graine » dans config/lobbik.properties et redémarrer).", VERT));
    }
    private void pret() { pret = true; poserBoutonSortie(); poserPenombres(); }

    private void envoyerCarte() {
        // couleurs des salles, départ, sortie — jamais les salles mortelles ni les passages
        StringBuilder b = new StringBuilder("[");
        for (Salle s : lab.salles) { if (b.length() > 1) b.append(','); b.append('[').append(s.i).append(',').append(s.j).append(',').append(s.k).append(',').append(s.couleur).append(']'); }
        envoyer("cube_carte", "{\"nx\":" + lab.nx + ",\"ny\":" + lab.ny + ",\"nz\":" + lab.nz + ",\"depart\":[" + lab.depart.i + "," + lab.depart.j + "," + lab.depart.k + "],\"sortie\":[" + lab.sortie.i + "," + lab.sortie.j + "," + lab.sortie.k + "],\"salles\":" + b.append(']') + "}");
    }
    private void envoyerPositions() {
        if (joueurs().isEmpty()) return;
        StringBuilder b = new StringBuilder("["); boolean prem = true;
        for (ServerPlayer q : joueurs()) {
            UUID u = q.getUUID(); Salle s = ou.get(u); if (s == null) continue;
            long t = debut.containsKey(u) ? (System.currentTimeMillis() - debut.get(u)) / 1000 : 0;
            if (!prem) b.append(','); prem = false;
            b.append("{\"uuid\":").append(Hub.j(u.toString())).append(",\"nom\":").append(Hub.j(nom(q))).append(",\"niveau\":").append(s.j + 1)
             .append(",\"salles\":").append(salles(u)).append(",\"salles_max\":").append(Math.max(salles(u), sallesMax(u)))
             .append(",\"avance\":").append(lab.plan.avance(s.sure ? s.id : derniereSure.getOrDefault(u, -1))).append(",\"avance_max\":").append(avanceMax(u)).append(",\"code\":").append(Hub.j(s.code))
             .append(",\"i\":").append(s.i).append(",\"j\":").append(s.j).append(",\"k\":").append(s.k).append(",\"morts\":").append(morts.getOrDefault(u, 0)).append(",\"temps\":").append(t).append('}');
        }
        envoyer("cube_positions", "{\"joueurs\":" + b.append(']') + ",\"nx\":" + lab.nx + ",\"ny\":" + lab.ny + ",\"nz\":" + lab.nz + ",\"depart\":[" + lab.depart.i + "," + lab.depart.j + "," + lab.depart.k + "],\"sortie\":[" + lab.sortie.i + "," + lab.sortie.j + "," + lab.sortie.k + "]}");
    }
    /** État en direct pour les écrans du hub : qui est dans le Cube (niveau, salles, avancée vers la sortie, morts), records. */
    private void ecrireDirect() {
        StringBuilder b = new StringBuilder("{\"t\":").append(System.currentTimeMillis()).append(",\"salles\":").append(lab.salles.size())
            .append(",\"sures\":").append(lab.salles.size() - lab.nbMortelles()).append(",\"chemin\":").append(lab.plan.chemin.length).append(",\"niveaux\":").append(lab.ny).append(",\"joueurs\":[");
        boolean prem = true;
        for (ServerPlayer q : joueurs()) {
            UUID u = q.getUUID(); Salle s = ou.get(u);
            if (!prem) b.append(','); prem = false;
            int av = s == null ? 0 : lab.plan.avance(s.sure ? s.id : derniereSure.getOrDefault(u, -1));
            b.append("{\"nom\":").append(Hub.j(nom(q))).append(",\"dedans\":").append(s != null).append(",\"niveau\":").append(s == null ? 0 : s.j + 1)
             .append(",\"salles\":").append(salles(u)).append(",\"avance\":").append(av).append(",\"morts\":").append(morts.getOrDefault(u, 0)).append('}');
        }
        b.append("],\"loin\":[");
        List<Map.Entry<String, Integer>> loin = new ArrayList<>(); List<Map.Entry<String, Long>> vite = new ArrayList<>(); int sorties = 0;
        for (Rec rc : records.values()) { if (rc.sallesMax > 0) loin.add(Map.entry(rc.nom, rc.sallesMax)); if (rc.meilleur > 0) vite.add(Map.entry(rc.nom, rc.meilleur)); sorties += rc.sorties; }
        loin.sort((x, y) -> y.getValue() - x.getValue()); vite.sort(Map.Entry.comparingByValue());
        for (int i = 0; i < Math.min(8, loin.size()); i++) { if (i > 0) b.append(','); b.append("{\"nom\":").append(Hub.j(loin.get(i).getKey())).append(",\"salles\":").append(loin.get(i).getValue()).append('}'); }
        b.append("],\"vite\":[");
        for (int i = 0; i < Math.min(5, vite.size()); i++) { if (i > 0) b.append(','); b.append("{\"nom\":").append(Hub.j(vite.get(i).getKey())).append(",\"temps\":").append(vite.get(i).getValue()).append('}'); }
        b.append("],\"sorties\":").append(sorties).append('}');
        String json = b.toString(); Path f = dossier.resolve("lobbik-cube-direct.json"), tmp = dossier.resolve("lobbik-cube-direct.json.tmp");
        CompletableFuture.runAsync(() -> {
            try { Files.writeString(tmp, json, StandardCharsets.UTF_8); Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (Exception ignored) { }
        });
    }
    private int avanceMax(UUID u) { Set<Integer> v = visitees.get(u); int m = 0; if (v != null) for (int id : v) m = Math.max(m, lab.plan.avance(id)); return m; }
    private int salles(UUID u) { Set<Integer> v = visitees.get(u); return v == null ? 0 : v.size(); }

    /* ------------------------------------------------------------------ site Lobbik (même liste de membres que La Tour) */
    private boolean cleSite() { return !cfg.s("cle_site", "").isEmpty(); }
    private void envoyer(String type, String json) { if (cleSite()) r.site.envoyer(type, json); }   // envoi_site=false : le Hub ne fait rien
    private void relireMembres() { if (cleSite()) Thread.startVirtualThread(r.site::relireLies); }
    /** Connexion directe (pas par le pont, dont la porte est déjà réservée aux membres) d'un compte non lié : code de liaison et au revoir. */
    private void verifierMembre(ServerPlayer p) {
        if (!cleSite() || !cfg.b("membres_seulement", true) || r.site.estLie(p.getUUID())) return;
        UUID u = p.getUUID(); String n = nom(p);
        Thread.startVirtualThread(() -> { r.site.relireLies(); serveur.execute(() -> {
            ServerPlayer q = joueur(u); if (q == null || r.site.estLie(u)) return;
            String code = codes.computeIfAbsent(u, this::codePour);
            envoyer("code", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(n) + ",\"code\":" + Hub.j(code) + "}");
            q.connection.disconnect(t("Serveur réservé aux membres de Lobbik · Members-only server\n\nCode : ", OR).append(t(code, AQUA))
                .append(t("\n\nhttps://lobbik.com → Minecraft → Jouer → « Lier mon compte Minecraft », puis revenez.\nLink your Minecraft account on the site, then come back.", GRIS)));
        }); });
    }
    private String codePour(UUID u) {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder b = new StringBuilder();
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(cfg.s("cle_site", "krp").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(u.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 6; i++) b.append(a.charAt((h[i] & 0xff) % a.length()));
        } catch (Exception ex) { for (int i = 0; i < 6; i++) b.append(a.charAt(alea.nextInt(a.length()))); }
        return b.toString();
    }

    /* ------------------------------------------------------------------ réseau Lobbik : vestibule et pont */
    /** Figé quelques secondes au retour à sa dernière position (le temps que le terrain arrive) — seulement en venant du hub par la porte. */
    private record Fige(long fin, double x, double y, double z) { }
    private final Map<UUID, Fige> figes = new HashMap<>();
    private void figer(ServerPlayer p, int secondes) {
        figes.put(p.getUUID(), new Fige(System.currentTimeMillis() + secondes * 1000L, p.getX(), p.getY(), p.getZ()));
        Outils.titre(p, Component.empty(), t("Retour dans le Cube…", GRIS), 0, secondes * 20, 8);
    }
    /** pas d'événement de mouvement en Fabric : chaque tick, qui a bougé est remis en place (le regard reste libre) */
    private void tenirFiges() {
        long n = System.currentTimeMillis();
        figes.entrySet().removeIf(e -> {
            ServerPlayer p = joueur(e.getKey()); Fige f = e.getValue();
            if (p == null || f.fin() < n) return true;
            if (Math.abs(p.getX() - f.x()) > 1e-3 || Math.abs(p.getY() - f.y()) > 1e-3 || Math.abs(p.getZ() - f.z()) > 1e-3) { Outils.teleporter(p, f.x(), f.y(), f.z(), p.getYRot(), p.getXRot()); p.setDeltaMovement(Vec3.ZERO); }
            return false;
        });
    }
    private final Set<UUID> absorbes = new HashSet<>();
    /** Joueurs dehors (arrivés par le pont, ou sortis) — ni suivis salle par salle, ni tués par le vide. */
    private final Set<UUID> surLePont = new HashSet<>();
    private final Map<UUID, double[]> retour = new HashMap<>();
    private static double[] nombres(String s) { String[] t = s.split(","); double[] d = new double[t.length]; for (int i = 0; i < t.length; i++) d[i] = Double.parseDouble(t[i].trim()); return d; }
    private boolean dansVestibule(double x, double y, double z) {
        return x >= vestibule[0] && x < vestibule[1] + 1 && z >= vestibule[2] && z < vestibule[3] + 1 && y >= vestibule[4] && y <= vestibule[5];
    }
    private void versLePont(ServerPlayer p) { Outils.teleporter(p, sortiePont[0], sortiePont[1], sortiePont[2], (float) sortiePont[3], 0f); }
    private static void teleporter(ServerPlayer p, Vec3 v, float lacet) { Outils.teleporter(p, v.x, v.y, v.z, lacet, 0f); p.fallDistance = 0; }
    /** Bouton de sortie dans la salle blanche : sur le mur nord, à gauche de la porte. */
    private BlockPos boutonSortie() { return new BlockPos(lab.depart.ox() + 2, lab.depart.oy() + 2, lab.depart.oz() + 1); }
    private void poserBoutonSortie() {
        monde.setBlock(boutonSortie(), Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.SOUTH),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
    private void sortirParLeBouton(ServerPlayer p) {
        UUID u = p.getUUID();
        finTentative(p); morts.remove(u); ou.remove(u);
        p.removeAllEffects();
        effet(p, MobEffects.BLINDNESS, 30, 0);
        son(p, S.PORTE_OUVRE, 1f, 0.8f);
        surLePont.add(u);
        plus(20, u, q -> { versLePont(q); q.setHealth(Math.max(q.getHealth(), 1f));
            q.sendSystemMessage(t("Vous êtes sorti du Cube. Le pont ramène à Lobbik ; le vestibule blanc, à l'intérieur.", GRIS)); });
    }
    /** Le vestibule se referme, la lumière s'éteint… et l'on se réveille dans la salle blanche (comme dans le film : personne ne sait comment il est entré). */
    private void absorber(ServerPlayer p) {
        UUID u = p.getUUID();
        if (!absorbes.add(u)) return;
        effet(p, MobEffects.BLINDNESS, 50, 0); effet(p, MobEffects.DARKNESS, 60, 0);
        son(p, S.PORTE_FERME, 1f, 0.6f); son(p, S.CAVE, 0.8f, 0.7f);
        plus(35, () -> {
            absorbes.remove(u);
            ServerPlayer q = joueur(u); if (q == null) return;
            surLePont.remove(u);
            teleporter(q, lab.depart.centre(), 0f); finTentative(q); morts.remove(u);
            ou.put(u, lab.depart);
            Outils.titre(q, Outils.tg("Le Cube", BLANC), t("Une pièce. Six issues. Le Cube ne pardonne pas.", GRIS), 16, 80, 20);
            donnerLivre(q);
        });
    }

    /** Fin de tentative (mort, sortie, /recommencer) : le record de salles traversées est gardé ; renvoie le score de la tentative. */
    private int finTentative(ServerPlayer p) {
        UUID u = p.getUUID(); int n = salles(u); Rec rc = rec(u);
        if (n > rc.sallesMax) rc.sallesMax = n;
        rc.nom = nom(p);
        visitees.remove(u); debut.remove(u); ou.remove(u); derniereSure.remove(u);
        return n;
    }

    /* ------------------------------------------------------------------ suivi des joueurs */
    private void suivre() {
        if (!pret) return;   // Cube en construction : personne n'est suivi ni tué
        for (ServerPlayer p : new ArrayList<>(joueurs())) {
            if (p.level() != monde || p.isDeadOrDying()) continue;   // mort en attente de réapparition : on ne le retue pas à chaque passage
            UUID u = p.getUUID(); double x = p.getX(), y = p.getY(), z = p.getZ();
            int bx = (int) Math.floor(x) - Labyrinthe.X0, by = (int) Math.floor(y) - Labyrinthe.Y0, bz = (int) Math.floor(z) - Labyrinthe.Z0, P = Labyrinthe.P;
            boolean dehors = bx < -1 || bz < -1 || bx >= lab.nx * P || bz >= lab.nz * P || by >= lab.ny * P || by < -1;
            // sur le pont ou dans le vestibule, on n'est pas encore entré dans le Cube (ou on en est sorti) : jamais tué ni suivi
            if (surLePont.contains(u)) { if (dansVestibule(x, y, z)) absorber(p); continue; }
            if (dehors || y < Labyrinthe.Y0 - 12) {
                if (!p.isCreative() && !p.isSpectator()) { causeMort.put(u, "est sorti du Cube, dans le vide"); tuer(p); }
                continue;
            }
            Salle s = lab.salleDe(x, y, z);
            if (s == null) continue;
            Salle avant = ou.put(u, s);
            if (avant != s) entrer(p, s);
            // en haut de la colonne centrale, on pousse la trappe du plafond : elle s'ouvre toute seule (hors de portée du clic depuis le sol)
            if ((int) Math.floor(x) == s.ox() + 4 && (int) Math.floor(z) == s.oz() + 4 && (int) Math.floor(y) >= s.oy() + 5 && s.portes[4] && !lab.ouverte(monde, s, 4)) {
                Integer fin = scelleeJusquA.get(s.id); Salle v = lab.voisin(s, 4); Integer finV = v != null ? scelleeJusquA.get(v.id) : null;
                if ((fin == null || fin <= tic) && (finV == null || finV <= tic)) { lab.basculer(monde, s, 4, true); son(p, S.TRAPPE, 1f, 0.7f); }
            }
        }
    }
    private void tuer(ServerPlayer p) { p.kill(monde); }

    private void entrer(ServerPlayer p, Salle s) {
        UUID u = p.getUUID();
        if (condamnes.containsKey(u)) return;                        // déjà condamné : ressortir ne change rien, rien ne compte plus
        // 23/09/2026 au soir (Maxime) : c'est la SALLE qui tue, quelle que soit la porte ; aucune échappatoire (voir salleFatale)
        if (!s.sure) { if (joueurVulnerable(p)) salleFatale(p, s); return; }
        derniereSure.put(u, s.id);
        if (s.depart) { soigner(p, 20); Outils.action(p, t("Salle blanche · le bouton noir, à côté de la porte nord, ramène dehors (ou /lobbik)", GRIS)); return; }   // le chronomètre ne part qu'en quittant la salle blanche
        debut.putIfAbsent(u, System.currentTimeMillis());
        if (boiteAMusique(s)) jouerBoite(p);
        if (visitees.computeIfAbsent(u, k -> new HashSet<>()).add(s.id)) {
            int n = salles(u), rec = sallesMax(u);
            if (n > rec && rec > 0 && n > 1) Outils.action(p, t("Nouveau record : " + n + " salles.", OR));
        }
        if (s.sortie) { victoire(p); return; }
        Integer fin = scelleeJusquA.get(s.id);
        if (fin != null && fin > tic) return;                       // déjà scellée : la séquence est en cours
        soigner(p, 4);                                              // une salle sûre : on souffle, deux cœurs
        activer(s);
    }
    private static void soigner(ServerPlayer p, double pv) { p.setHealth((float) Math.min(p.getMaxHealth(), p.getHealth() + pv)); if (pv >= 20) { p.clearFire(); p.removeAllEffects(); } }
    /** les joueurs dont le corps est dans la salle (position réelle) */
    private List<ServerPlayer> dedans(Salle s) { List<ServerPlayer> l = new ArrayList<>(); for (ServerPlayer p : joueurs()) if (p.level() == monde && lab.salleDe(p.position()) == s) l.add(p); return l; }
    /** les joueurs que le suivi range dans la salle */
    private List<ServerPlayer> joueursDans(Salle s) { List<ServerPlayer> l = new ArrayList<>(); for (ServerPlayer p : joueurs()) if (ou.get(p.getUUID()) == s) l.add(p); return l; }
    private void son(Salle s, Holder<SoundEvent> son, float vol, float hauteur) { for (ServerPlayer p : dedans(s)) son(p, son, vol, hauteur); }
    private void particules(Salle s, ParticleOptions part, int n, double vitesse) { Vec3 c = s.centre(); part(part, c.x, c.y + 3, c.z, n, 3.0, 2.5, 3.0, vitesse); }

    /** Une salle sûre : elle se scelle, un peu d'ambiance, puis les trappes se déverrouillent. Elle ne tue jamais. */
    private void activer(Salle s) {
        int duree = 70;
        scelleeJusquA.put(s.id, tic + duree);
        plus(12, () -> {
            lab.ouvrir(monde, s, false);
            son(s, S.PORTE_FERME, 1f, 0.6f); son(s, S.ENCLUME, 0.6f, 0.5f);
            for (ServerPlayer p : dedans(s)) Outils.action(p, t("La salle se scelle.", ROUGE));
        });
        plus(30, () -> ambiance(s));
        plus(duree, () -> { son(s, S.TRAPPE, 0.6f, 0.7f); for (ServerPlayer p : dedans(s)) Outils.action(p, t("Les trappes se déverrouillent.", GRIS)); });
    }
    private void ambiance(Salle s) {
        List<ServerPlayer> l = dedans(s); if (l.isEmpty()) return;
        switch (alea.nextInt(7)) {
            case 0 -> son(s, S.CAVE, 1f, 0.8f);
            case 1 -> { son(s, S.COEUR, 1f, 1f); for (ServerPlayer p : l) effet(p, MobEffects.DARKNESS, 80, 0); }
            case 2 -> { for (ServerPlayer p : l) effet(p, MobEffects.NAUSEA, 120, 0); son(s, S.CONDUIT, 1f, 0.6f); }
            case 3 -> { for (ServerPlayer p : l) effet(p, MobEffects.SLOWNESS, 60, 1); particules(s, ParticleTypes.CLOUD, 60, 0.02); }
            case 4 -> { for (ServerPlayer p : l) effet(p, MobEffects.GLOWING, 100, 0); particules(s, ParticleTypes.END_ROD, 40, 0.05); }
            default -> { }   // le plus souvent : rien, juste le silence et le bruit des portes
        }
    }

    /* ------------------------------------------------------------------ mort, réapparition, victoire */
    /** Le message de mort (chat et écran de mort), via CubeMessageMortMixin : la cause posée par le Cube, sinon « est mort dans le Cube ». */
    public static Component messageMort(LivingEntity e) {
        if (I == null || !(e instanceof ServerPlayer p)) return null;
        String cause = I.causeMort.get(p.getUUID());
        return t(nom(p), BLANC).append(t(" " + (cause != null ? cause : "est mort dans le Cube") + ".", GRIS));
    }
    /** Point de réapparition, via CubeReapparitionMixin : toujours la salle blanche (null : laisser faire le jeu). */
    public static TeleportTransition reapparition(ServerPlayer p, TeleportTransition.PostTeleportTransition apres) {
        if (I == null) return null;
        return new TeleportTransition(I.monde, I.lab.depart.centre(), Vec3.ZERO, 0f, 0f, apres);
    }
    void mort(ServerPlayer p) {
        UUID u = p.getUUID();
        String cause = causeMort.remove(u); condamnes.remove(u);   // mort d'autre chose entre-temps : la salle ne retue pas
        morts.merge(u, 1, Integer::sum);
        Salle s = ou.get(u);
        int n = salles(u), rec = Math.max(n, sallesMax(u));
        envoyer("cube_mort", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(nom(p)) + ",\"niveau\":" + (s != null ? s.j + 1 : 0) + ",\"salles\":" + n + ",\"salles_max\":" + rec + ",\"cause\":" + Hub.j(cause != null ? cause : "est mort dans le Cube") + "}");
        rec(u).morts++;
        finTentative(p);
        if (n > 0) p.sendSystemMessage(t(n + " salle" + (n > 1 ? "s" : "") + " traversée" + (n > 1 ? "s" : "") + " cette fois" + (rec > n ? " (votre record : " + rec + ")" : n == rec && rec > 1 ? " — votre record" : "") + ".", GRIS));
    }
    void reapparu(ServerPlayer p) {
        UUID u = p.getUUID();
        surLePont.remove(u);
        // 24/09/2026 (Maxime : « je réapparais pas vraiment, je suis dans le vide mais pas mort ») : cause = la réapparition instantanée,
        // coupée au démarrage (écran de mort normal, bouton Réapparaître). Ici, on coupe seulement tout effet d'obscurité restant.
        plus(20, u, q -> { if (!q.isDeadOrDying()) { q.removeEffect(MobEffects.DARKNESS); q.removeEffect(MobEffects.BLINDNESS); } });
        plus(2, u, q -> { q.removeAllEffects(); q.clearFire();
            Outils.titre(q, t("Le Cube", BLANC), t("Vous vous réveillez à nouveau dans la salle blanche.", GRIS), 10, 60, 20); });
    }
    private void victoire(ServerPlayer p) {
        UUID u = p.getUUID();
        long t = debut.containsKey(u) ? (System.currentTimeMillis() - debut.get(u)) / 1000 : 0;
        int m = morts.getOrDefault(u, 0);
        Rec rc = rec(u);
        if (t > 0 && (rc.meilleur == 0 || t < rc.meilleur)) rc.meilleur = t;
        rc.sorties++;
        final int n = salles(u); finTentative(p); ou.put(u, lab.sortie); sauverRecords();   // on reste « dans la sortie » jusqu'au retour : pas de seconde victoire
        envoyer("cube_sortie", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(nom(p)) + ",\"temps\":" + t + ",\"morts\":" + m + ",\"salles\":" + n + ",\"salles_max\":" + rc.sallesMax + "}");
        Outils.titre(p, Outils.tg("VOUS ÊTES SORTI", OR), t("du Cube en " + duree(t) + (m > 0 ? " · " + m + " mort" + (m > 1 ? "s" : "") : " · sans mourir") + " · " + n + " salle" + (n > 1 ? "s" : ""), BLANC), 6, 120, 40);
        son(p, S.DEFI, 1f, 1f);
        diffuser(t("✦ ", OR).append(t(nom(p), BLANC)).append(t(" est sorti du Cube en " + duree(t) + (m > 0 ? " après " + m + " mort" + (m > 1 ? "s" : "") : ", sans mourir") + ".", GRIS)));
        for (int q = 0; q < 4; q++) plus(10 + q * 12, u, this::feuDArtifice);
        morts.remove(u);
        plus(140, u, q -> { surLePont.add(u); versLePont(q); ou.remove(u); visitees.remove(u);
            q.sendSystemMessage(t("Vous voilà dehors. Le pont ramène à Lobbik ; le vestibule blanc, dans le Cube.", GRIS)); });
    }
    private void feuDArtifice(ServerPlayer p) {
        ItemStack fus = new ItemStack(Items.FIREWORK_ROCKET);
        fus.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, IntList.of(0xFFFFFF, 0xFFFF00), IntList.of(), true, false))));
        monde.addFreshEntity(new FireworkRocketEntity(monde, p.getX() + alea.nextDouble() * 4 - 2, p.getY() + 1, p.getZ() + alea.nextDouble() * 4 - 2, fus));
    }
    private static String duree(long s) { return s >= 3600 ? String.format("%dh%02d", s / 3600, (s % 3600) / 60) : String.format("%d:%02d", s / 60, s % 60); }

    /* ------------------------------------------------------------------ records : config/lobbik-cube-records.properties */
    private static final class Rec { String nom = "?"; int sallesMax, sorties, morts; long meilleur, plan; }
    private final Map<String, Rec> records = new TreeMap<>();
    private boolean recordsModifies;
    private Path fichierRecords() { return dossier.resolve("lobbik-cube-records.properties"); }
    private Rec rec(UUID u) { recordsModifies = true; return records.computeIfAbsent(u.toString(), k -> new Rec()); }
    private int sallesMax(UUID u) { Rec rc = records.get(u.toString()); return rc == null ? 0 : rc.sallesMax; }
    private void chargerRecords() {
        Properties p = new Properties(); Path f = fichierRecords();
        if (Files.exists(f)) try (Reader rd = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(rd); } catch (Exception e) { Lobbik.LOG.warn("Records du Cube illisibles : {}", e.toString()); }
        else importerRecordsPlugin(p);
        for (String k : p.stringPropertyNames()) {
            int i = k.lastIndexOf('.'); if (i <= 0) continue;
            Rec rc = records.computeIfAbsent(k.substring(0, i), x -> new Rec()); String v = p.getProperty(k).trim();
            try {
                switch (k.substring(i + 1)) {
                    case "nom" -> rc.nom = v; case "salles_max" -> rc.sallesMax = Integer.parseInt(v); case "sorties" -> rc.sorties = Integer.parseInt(v);
                    case "morts" -> rc.morts = Integer.parseInt(v); case "meilleur" -> rc.meilleur = Long.parseLong(v); case "plan" -> rc.plan = Long.parseLong(v);
                    default -> { }
                }
            } catch (NumberFormatException ignored) { }
        }
        if (!Files.exists(f) && !records.isEmpty()) sauverRecords();
    }
    /** Premier démarrage en Fabric : on reprend les records du plugin (plugins/KrpCube/records.yml, YAML simple à deux niveaux) s'ils sont là. */
    private void importerRecordsPlugin(Properties p) {
        Path y = FabricLoader.getInstance().getGameDir().resolve(cfg.s("cube.records_plugin", "plugins/KrpCube/records.yml"));
        if (!Files.exists(y)) return;
        try {
            String cle = null; int n = 0;
            for (String l : Files.readAllLines(y, StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.trim().startsWith("#")) continue;
                int dp = l.indexOf(':'); if (dp < 0) continue;
                String k = sansGuillemets(l.substring(0, dp).trim()), v = sansGuillemets(l.substring(dp + 1).trim());
                if (!Character.isWhitespace(l.charAt(0))) { cle = k; n++; } else if (cle != null && !v.isEmpty()) p.setProperty(cle + "." + k, v);
            }
            Lobbik.LOG.info("Records du plugin KrpCube repris : {} joueurs ({})", n, y);
        } catch (Exception e) { Lobbik.LOG.warn("Records du plugin illisibles : {}", e.toString()); }
    }
    private static String sansGuillemets(String s) { return s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s; }
    private void sauverRecords() {
        Properties p = new Properties();
        for (Map.Entry<String, Rec> e : records.entrySet()) {
            String k = e.getKey(); Rec rc = e.getValue();
            p.setProperty(k + ".nom", rc.nom); p.setProperty(k + ".salles_max", "" + rc.sallesMax); p.setProperty(k + ".sorties", "" + rc.sorties);
            p.setProperty(k + ".morts", "" + rc.morts); p.setProperty(k + ".meilleur", "" + rc.meilleur); p.setProperty(k + ".plan", "" + rc.plan);
        }
        Path f = fichierRecords(), tmp = dossier.resolve("lobbik-cube-records.properties.tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { p.store(w, "Le Cube : records par joueur (UUID)"); }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            recordsModifies = false;
        } catch (Exception e) { Lobbik.LOG.warn("records du Cube : {}", e.toString()); }
    }

    /* ------------------------------------------------------------------ arrivée, départ, interface */
    void rejoint(ServerPlayer p) {
        UUID u = p.getUUID();
        p.setGameMode(GameType.ADVENTURE);
        // arrivé à pied par le pont (billet du proxy) — ou posé côté hub, que le réseau remet sur le pont : il entre quand il veut, par le vestibule
        boolean pont = Billets.PAR_LE_PONT.containsKey(u) || p.getX() > Geo.PORTE_CUBE_X && p.getX() < Geo.PORTE_TOUR_X;
        if (pont) {
            diffuser(t(nom(p), BLANC).append(t(" s'approche du Cube.", GRIS)));
            ou.remove(u); surLePont.add(u);
            double[] ici = retour.remove(u);
            if (ici != null && lab.salleDe(ici[0], ici[1], ici[2]) != null) {   // il avait quitté le jeu dans une salle : retour à cet endroit
                plus(2, u, q -> { surLePont.remove(u); Outils.teleporter(q, ici[0], ici[1], ici[2], (float) ici[3], (float) ici[4]); q.fallDistance = 0; figer(q, 3);
                    ou.put(u, lab.salleDe(ici[0], ici[1], ici[2])); Outils.action(q, t("De retour là où vous étiez, dans le Cube.", GRIS)); });
                return;
            }
            Outils.action(p, t("Le Cube. Entrez dans la pièce blanche… si vous l'osez.", GRIS));
            return;
        }
        verifierMembre(p);
        Rec rc = rec(u); rc.nom = nom(p);
        if (lab.salleDe(p.position()) == null || rc.plan != lab.plan.empreinte()) { teleporter(p, lab.depart.centre(), 0f); rc.plan = lab.plan.empreinte(); }   // jamais venu, ou Cube reconstruit depuis : retour au départ
        diffuser(t(nom(p), BLANC).append(t(" se réveille dans le Cube.", GRIS)));
        donnerLivre(p);
        plus(20, u, q -> Outils.titre(q, Outils.tg("Le Cube", BLANC), t("Une pièce. Six issues. Le Cube ne pardonne pas.", GRIS), 16, 80, 20));
        int record = rc.sallesMax;
        p.sendSystemMessage(t("Le Cube — " + lab.salles.size() + " salles. Quelque part, une sortie. Certaines salles tuent, par quelque porte qu'on y entre, et rien ne les distingue. On compte les salles traversées"
            + (record > 0 ? " (votre record : " + record + ")" : "") + ". Le livre dans votre barre raconte le reste.", GRIS));
    }
    void quitte(ServerPlayer p) {
        UUID u = p.getUUID();
        diffuser(t(nom(p), BLANC).append(t(" a quitté le Cube.", GRIS)));
        // parti du jeu depuis une salle → la porte du Cube, au hub, l'y ramènera (parti par le pont → rien à retenir)
        if (!surLePont.contains(u) && lab.salleDe(p.position()) != null && !p.isDeadOrDying()) retour.put(u, new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()});
        else retour.remove(u);
        ou.remove(u); surLePont.remove(u); figes.remove(u); absorbes.remove(u);
    }
    private void diffuser(Component c) { serveur.getPlayerList().broadcastSystemMessage(c, false); }
    private void donnerLivre(ServerPlayer p) { if (livreDonne.add(p.getUUID()) && !p.getInventory().contains(i -> i.is(Items.WRITTEN_BOOK))) p.getInventory().setItem(8, livre()); }
    private static ItemStack livre() {
        ItemStack it = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String s : new String[]{
            "Vous vous réveillez dans une pièce cubique. Aucun souvenir d'y être entré.\n\nSix issues : quatre portes, une trappe au plafond, une trappe au sol. Derrière chacune, une autre pièce, identique ou presque.",
            "Toutes les portes s'ouvrent. Mais certaines pièces tuent, par quelque porte qu'on y entre : lames, gaz, flammes, acide, plafond qui s'abat. Une fois dedans, il est trop tard, même pour ressortir.",
            "Rien ne les distingue : ni la couleur, ni la lumière. Les pièces sûres se suivent, montent, redescendent, tournent, et finissent souvent en cul-de-sac. Le Cube ne change jamais : ce que vous apprenez en mourant reste vrai à la tentative suivante.",
            "Cliquez sur les portes et les trappes pour les ouvrir. L'échelle du milieu mène au plafond ; la trappe à son pied mène en bas.\n\nOn compte les pièces traversées. Quelque part, une pièce dorée : la sortie. On ne se soigne que dans la pièce blanche. Mourir vous y renvoie."})
            pages.add(Filterable.passThrough(Component.literal(s)));
        it.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Le Cube"), "Inconnu", 0, pages, true));
        return it;
    }
    private void barre() {
        for (ServerPlayer p : joueurs()) {
            UUID u = p.getUUID(); Salle s = ou.get(u); if (s == null) continue;
            long t = debut.containsKey(u) ? (System.currentTimeMillis() - debut.get(u)) / 1000 : 0;
            int n = salles(u);
            MutableComponent c = t(n + " salle" + (n > 1 ? "s" : ""), BLANC).append(t("  ·  " + duree(t), GRIS));
            int m = morts.getOrDefault(u, 0); if (m > 0) c.append(t("  ·  " + m + " mort" + (m > 1 ? "s" : ""), ROUGE));
            Outils.action(p, c);
        }
    }

    /* ------------------------------------------------------------------ les portes et trappes s'ouvrent au clic (Maxime : « c'est à moi de cliquer ») */
    InteractionResult clic(ServerPlayer p, InteractionHand main, BlockHitResult coup) {
        if (main != InteractionHand.MAIN_HAND) return InteractionResult.PASS;   // le client envoie aussi la main secondaire : sinon la porte s'ouvre puis se referme aussitôt
        BlockPos b = coup.getBlockPos(); BlockState st = monde.getBlockState(b);
        if (st.is(Blocks.POLISHED_BLACKSTONE_BUTTON) && b.equals(boutonSortie())) { if (pret) sortirParLeBouton(p); return InteractionResult.PASS; }   // le bouton s'enfonce, comme avant
        boolean porte = st.is(Blocks.IRON_DOOR);
        if (!porte && !st.is(Blocks.IRON_TRAPDOOR) && !st.is(Blocks.LADDER)) return !estOp(p) && p.getItemInHand(main).getItem() instanceof BlockItem ? InteractionResult.FAIL : InteractionResult.PASS;   // rien ne se pose
        if (!pret) return InteractionResult.PASS;
        int[] is = lab.issueDe(b.getX(), b.getY(), b.getZ());
        if (is == null && porte) is = lab.issueDe(b.getX(), b.getY() - 1, b.getZ());   // battant du haut
        if (is == null) return InteractionResult.PASS;
        Salle s = lab.salles.get(is[0]); int d = is[1];
        Integer fin = scelleeJusquA.get(s.id); Salle v = lab.voisin(s, d); Integer finV = v != null ? scelleeJusquA.get(v.id) : null;
        if ((fin != null && fin > tic) || (finV != null && finV > tic)) { son(p, S.VERROU, SoundSource.MASTER, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 1f, 0.5f); Outils.action(p, t("Scellée.", ROUGE)); return InteractionResult.SUCCESS; }
        long cle = ((long) s.id << 3) | d, maintenant = System.currentTimeMillis();
        Long dernier = bascules.get(cle); if (dernier != null && maintenant - dernier < 400) return InteractionResult.SUCCESS;
        bascules.put(cle, maintenant);
        boolean ouverte = lab.ouverte(monde, s, d);
        lab.basculer(monde, s, d, !ouverte);
        son(p, ouverte ? S.PORTE_FERME : S.PORTE_OUVRE, SoundSource.MASTER, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 1f, 0.7f);
        return InteractionResult.SUCCESS;
    }
    boolean estOp(ServerPlayer p) { return serveur.getPlayerList().isOp(p.nameAndId()); }

    /* ------------------------------------------------------------------ les salles mortelles */
    private boolean joueurVulnerable(ServerPlayer p) { return p.level() == monde && !p.isCreative() && !p.isSpectator() && !p.isDeadOrDying(); }
    /** On vient d'entrer dans une salle mortelle, par n'importe quelle porte : portes fermées, scellée, un grondement, ce qui va tuer
     *  se montre, puis la mort 1,5 s plus tard. Systématique : ressortir pendant ce temps ne sauve pas (la porte se referme sur lui).
     *  Une seule mise à mort par joueur (jeton) ; s'il meurt d'autre chose entre-temps, mort() retire le jeton et rien ne se passe. */
    private void salleFatale(ServerPlayer p, Salle s) {
        UUID u = p.getUUID(); Object jeton = new Object(); condamnes.put(u, jeton);
        Piege maniere = s.piege == Piege.AUCUN ? Piege.LAMES : s.piege;
        scelleeJusquA.put(s.id, tic + 80);
        lab.ouvrir(monde, s, false);
        for (ServerPlayer q : joueursDans(s)) { ambiant(q, S.PORTE_FERME, 1f, 0.5f); ambiant(q, S.BALISE_OFF, 1f, 0.5f); ambiant(q, S.COEUR, 1f, 0.8f); }
        plus(12, u, q -> { if (condamnes.get(u) == jeton && !q.isDeadOrDying()) presage(q, maniere); });
        plus(30, () -> {
            if (!condamnes.remove(u, jeton)) return;                                   // déjà mort d'autre chose : on ne tue pas deux fois
            ServerPlayer q = joueur(u);
            if (q == null || q.isDeadOrDying() || q.level() != monde) return;          // déconnecté : il revient dans la salle, elle le reprend
            boolean ressorti = lab.salleDe(q.position()) != s;
            causeMort.put(u, ressorti ? "a voulu ressortir : la porte s'est refermée sur lui" : switch (maniere) {
                case GAZ -> "a respiré le gaz du Cube"; case FLAMMES -> "a brûlé dans le Cube"; case ACIDE -> "s'est dissous dans l'acide du Cube";
                case CHUTE -> "a été écrasé par le plafond du Cube"; default -> "a été découpé en tranches par le Cube"; });
            coupFatal(q, ressorti ? null : maniere);
            tuer(q);
        });
    }
    /** Ce qui va tuer se montre (0,6 s après l'entrée) : autour du joueur seulement. */
    private void presage(ServerPlayer p, Piege m) {
        double x = p.getX(), y = p.getY() + 1, z = p.getZ();
        switch (m) {
            case GAZ -> { son(p, S.EXTINCTION, 1f, 0.5f); part(ParticleTypes.SNEEZE, x, y, z, 200, 2.5, 1.5, 2.5, 0.05); part(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 60, 2.5, 1.5, 2.5, 0.02);
                          effet(p, MobEffects.NAUSEA, 60, 0); effet(p, MobEffects.DARKNESS, 60, 0); }
            case FLAMMES -> { son(p, S.BLAZE, 1f, 0.7f); part(ParticleTypes.FLAME, x, y, z, 300, 2.5, 1.5, 2.5, 0.1); p.setRemainingFireTicks(60); }
            case ACIDE -> { son(p, S.LAVE, 1f, 0.6f); part(ParticleTypes.SMOKE, x, p.getY() + 0.2, z, 200, 2.5, 0.2, 2.5, 0.03); effet(p, MobEffects.SLOWNESS, 40, 3); }
            case CHUTE -> { son(p, S.PISTON_SORT, 1f, 0.5f); son(p, S.MEULE, 1f, 0.5f); part(poussiere("gray_concrete"), x, y + 2.5, z, 60, 2.5, 0.3, 2.5, 0.02); }
            default -> { son(p, S.MEULE, 1f, 0.6f); part(ParticleTypes.ELECTRIC_SPARK, x, y, z, 120, 2.5, 1.5, 2.5, 0.2); }
        }
    }
    /** Le coup fatal, entendu par les joueurs proches ; m = null : la porte s'est refermée sur celui qui ressortait. */
    private void coupFatal(ServerPlayer p, Piege m) {
        Vec3 l = p.position(); double x = l.x, y = l.y + 1, z = l.z;
        Holder<SoundEvent> s1 = m == null ? S.PORTE_FERME : m == Piege.FLAMMES ? S.BLAZE : m == Piege.GAZ ? S.EXTINCTION : m == Piege.ACIDE ? S.LAVE : m == Piege.CHUTE ? S.PISTON_RENTRE : S.BALAYAGE;
        for (ServerPlayer q : joueurs()) if (q.level() == monde && q.distanceToSqr(l) < 900) {
            son(q, s1, SoundSource.MASTER, l.x, l.y, l.z, 1f, 0.5f); son(q, S.ENCLUME, SoundSource.MASTER, l.x, l.y, l.z, 1f, 0.5f); son(q, S.BLESSE, SoundSource.MASTER, l.x, l.y, l.z, 1f, 0.8f);
        }
        if (m == null || m == Piege.LAMES) { part(ParticleTypes.SWEEP_ATTACK, x, y, z, 8, 0.6, 0.6, 0.6, 0); part(ParticleTypes.CRIT, x, y, z, 80, 0.6, 0.8, 0.6, 0.4); }
        else if (m == Piege.FLAMMES) part(ParticleTypes.LAVA, x, y, z, 30, 0.6, 0.6, 0.6, 0);
        else if (m == Piege.CHUTE) part(poussiere("gray_concrete"), x, y + 1, z, 80, 0.8, 1.0, 0.8, 0.1);
        part(poussiere("redstone_block"), x, y, z, 50, 0.6, 0.7, 0.6, 0.1);
    }

    /* ------------------------------------------------------------------ ambiance : le Cube respire, gronde, tremble (Maxime : « une ambiance qui fait un peu peur ») */
    /* Salles « à moitié éteintes » (Maxime, 23/09/2026) : environ une salle sur sept a un éclairage défaillant. Tant qu'un joueur
       s'y trouve, ses lumières invisibles baissent par à-coups (grésillements courts), et parfois s'éteignent presque 2 à 4 s.
       Choix déterministe (graine + id) : la même salle clignote toujours ; jamais le départ ni la sortie. */
    private final Map<Integer, Integer> penombre = new HashMap<>();   // salle → passages (toutes les 3 ticks) restants avant retour à la pleine lumière
    /** Salles sombres (Maxime, 23/09/2026 : « des salles mal éclairées, sombres, d'un seul côté ») : une salle sur six environ,
     *  une seule lumière faible dans un coin, les trois autres éteintes. Posé au démarrage, fixe. */
    private boolean sombre(Salle s) { if (s.depart || s.sortie || defaillante(s)) return false; long h = (s.id * 0xC2B2AE3D27D4EB4FL) ^ (lab.graine * 131); h ^= (h >>> 31); return Math.floorMod(h, 6) == 0; }
    private void poserPenombres() {
        int n = 0; int[][] coins = {{2, 2}, {6, 2}, {2, 6}, {6, 6}};
        for (Salle s : lab.salles) {
            if (!sombre(s)) continue; n++;
            int garde = Math.floorMod(s.id * 7 + (int) lab.graine, 4);
            for (int c = 0; c < 4; c++) lumiere(new BlockPos(s.ox() + coins[c][0], s.oy() + 6, s.oz() + coins[c][1]), c == garde ? 8 : 0);
        }
        Lobbik.LOG.info("Salles sombres : {}", n);
    }
    private void lumiere(BlockPos b, int niveau) {
        BlockState st = monde.getBlockState(b);
        if (st.is(Blocks.LIGHT) && st.getValue(LightBlock.LEVEL) != niveau) monde.setBlock(b, st.setValue(LightBlock.LEVEL, niveau), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
    /* Boîtes à musique (Maxime, 24/09/2026 : « parfois dans un cube un peu de musique, mais pas tout le temps ») : environ une salle
       sur douze cache une boîte à musique. En y entrant, une fois sur trois, une petite mélodie lente en mineur, jouée aux clochettes,
       grave et un peu désaccordée, s'élève pendant une vingtaine de secondes puis s'éteint. Pas de musique de Minecraft. */
    private boolean boiteAMusique(Salle s) { if (s.depart || s.sortie || !s.sure) return false; long h = (s.id * 0x94D049BB133111EBL) ^ (lab.graine * 977); h ^= (h >>> 27); return Math.floorMod(h, 12) == 0; }
    private final Map<UUID, Long> derniereBoite = new HashMap<>();
    // la mineur, lente : degrés de la gamme (demi-tons depuis fa#3 = hauteur 0.5) ; -1 = silence
    private static final int[][] MELODIES = {
        {9, -1, 12, 11, 9, -1, 7, 9, -1, 4, -1, 5, 4, 2, -1, 0, -1, -1, 4, 5, 7, -1, 9, -1, -1},
        {12, 11, 12, -1, 9, -1, 7, 5, 4, -1, -1, 5, 7, 9, 7, 5, 4, -1, 2, 4, -1, 0, -1, -1},
        {4, -1, 9, -1, 12, -1, 11, -1, 9, 7, -1, 9, -1, -1, 4, -1, 9, -1, 14, -1, 12, 11, 9, -1, -1}
    };
    private void jouerBoite(ServerPlayer p) {
        UUID u = p.getUUID(); long t = System.currentTimeMillis(); Long d = derniereBoite.get(u);
        if (d != null && t - d < 120_000) return;                                   // jamais deux mélodies à moins de 2 min
        if (alea.nextInt(3) != 0) return;                                           // une fois sur trois seulement
        derniereBoite.put(u, t);
        int[] m = MELODIES[alea.nextInt(MELODIES.length)]; Holder<SoundEvent> inst = alea.nextBoolean() ? S.CLOCHETTES : S.CLOCHE;
        float desaccord = 0.97f + alea.nextFloat() * 0.02f;
        for (int n = 0; n < m.length; n++) { if (m[n] < 0) continue; float h = (float) (Math.pow(2, (m[n] - 12) / 12.0) * desaccord);
            plus(16 * n, u, q -> { if (!q.isDeadOrDying()) ambiant(q, inst, 0.35f, Math.max(0.5f, Math.min(2f, h))); }); }
    }
    private boolean defaillante(Salle s) { if (s.depart || s.sortie) return false; long h = (s.id * 0x9E3779B97F4A7C15L) ^ (lab.graine * 31); h ^= (h >>> 29); return Math.floorMod(h, 7) == 0; }
    private void lumieres(Salle s, int niveau) {
        for (int[] c : new int[][]{{2, 2}, {6, 6}}) lumiere(new BlockPos(s.ox() + c[0], s.oy() + 6, s.oz() + c[1]), niveau);   // deux lumières sur quatre : l'effet reste, deux fois moins de recalcul
    }
    private void clignoter() {
        Set<Integer> occupees = new HashSet<>();
        for (ServerPlayer p : joueurs()) { if (p.level() != monde) continue; Salle s = ou.get(p.getUUID()); if (s != null && defaillante(s)) occupees.add(s.id); }
        // salles quittées : on rallume
        for (Integer id : new ArrayList<>(penombre.keySet())) if (!occupees.contains(id)) { lumieres(lab.salles.get(id), 15); penombre.remove(id); }
        for (int id : occupees) {
            Salle s = lab.salles.get(id); Integer rr = penombre.get(id);
            if (rr != null && rr > 0) { if (rr == 1) { lumieres(s, 15); penombre.put(id, 0); } else penombre.put(id, rr - 1); continue; }
            double t = alea.nextDouble();
            if (t < 0.012) {                                  // panne : presque noir 2 à 4 s
                lumieres(s, 1); penombre.put(id, 20 + alea.nextInt(20));
                for (ServerPlayer p : joueursDans(s)) ambiant(p, S.BALISE_OFF, 0.35f, 0.6f);
            } else if (t < 0.035) {                           // grésillement : baisse brève (rare : chaque changement de lumière recalcule l'éclairage chez le client)
                lumieres(s, 2 + alea.nextInt(6)); penombre.put(id, 1 + alea.nextInt(3));
                if (alea.nextInt(3) == 0) for (ServerPlayer p : joueursDans(s)) ambiant(p, S.TORCHE, 0.12f, 1.6f + alea.nextFloat() * 0.3f);
            }
        }
    }
    /* Musique étrange (Maxime, 23/09/2026 : « des musiques plus étranges, dans les caves et autre ») ; 24/09/2026 (Maxime : « pas les
       sons du jeu normal, que des bruits de vent, du silence ») : plus aucune musique de Minecraft. Des nappes d'ambiance seulement —
       vent (le souffle de l'élytre, grave), bouches d'aération (deltas de basalte), plainte sourde (vallée des âmes), son étouffé
       (sous l'eau), cavernes — jouées bas, et de longs silences entre elles. Jamais dans la salle blanche. */
    private final Map<UUID, Long> prochaineMusique = new HashMap<>();
    private final Map<UUID, Holder<SoundEvent>> musiqueEnCours = new HashMap<>();
    private void musique() {
        long t = System.currentTimeMillis();
        for (ServerPlayer p : joueurs()) {
            if (p.level() == monde) p.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));   // jamais la musique habituelle de Minecraft dans le Cube
            UUID u = p.getUUID(); Salle s = ou.get(u);
            if (p.level() != monde || s == null || s.depart) { Holder<SoundEvent> m = musiqueEnCours.remove(u); if (m != null) couper(p, m); prochaineMusique.remove(u); continue; }
            Long pro = prochaineMusique.get(u);
            if (pro == null) { prochaineMusique.put(u, t + 8_000 + alea.nextInt(12_000)); continue; }
            if (t < pro) continue;
            Holder<SoundEvent> ancien = musiqueEnCours.remove(u); if (ancien != null) couper(p, ancien);
            Holder<SoundEvent> m = S.MUSIQUES.get(alea.nextInt(S.MUSIQUES.size()));
            float hauteur = 0.7f + alea.nextFloat() * 0.25f;          // ralenti : plus grave, plus inquiétant
            ambiant(p, m, m == S.VENT ? 0.18f : 0.35f, m == S.VENT ? 0.5f : hauteur);
            musiqueEnCours.put(u, m);
            int duree = 40 + alea.nextInt(50);                        // on coupe au bout de 40 à 90 s, puis silence
            plus(20 * duree, u, q -> { if (musiqueEnCours.get(u) == m) { couper(q, m); musiqueEnCours.remove(u); } });
            prochaineMusique.put(u, t + (duree + 30 + alea.nextInt(60)) * 1000L);
        }
    }
    /** Toutes les secondes : à chaque joueur dans le Cube son prochain frisson, toutes les 30 à 90 s, jamais dans la salle blanche. Rien ne blesse, rien ne pousse. */
    private void frissons() {
        long maintenant = System.currentTimeMillis();
        for (ServerPlayer p : joueurs()) {
            if (p.level() != monde) continue;
            UUID u = p.getUUID(); Salle s = ou.get(u);
            if (s == null || s.depart) { prochainFrisson.remove(u); continue; }
            Long prochain = prochainFrisson.get(u);
            if (prochain == null) { prochainFrisson.put(u, maintenant + 30_000 + alea.nextInt(60_000)); continue; }
            if (maintenant < prochain) continue;
            prochainFrisson.put(u, maintenant + 30_000 + alea.nextInt(60_000));
            frisson(p, s);
        }
    }
    private void frisson(ServerPlayer p, Salle s) {
        UUID u = p.getUUID(); int tirage = alea.nextInt(100);
        // 24/09/2026 (Maxime : « parfois des bruits, des tremblements, des choses étranges ») : un tiers des frissons tirés parmi
        // de nouveaux bruits, joués DERRIÈRE le joueur (à 3 blocs dans son dos) pour qu'il se retourne.
        if (alea.nextInt(3) == 0) { etrange(p); return; }
        if (tirage < 28) ambiant(p, S.CAVE, 0.7f, 0.6f + alea.nextFloat() * 0.5f);                  // le souffle des cavernes
        else if (tirage < 42) ambiant(p, S.CONDUIT, 0.6f, 0.5f);                                     // bourdonnement sourd
        else if (tirage < 56) { for (int n = 0; n < 4; n++) { float h = 0.8f + n * 0.05f; plus(n * 18, u, q -> ambiant(q, S.COEUR, 0.9f, h)); } }   // un cœur qui bat, quelque part
        else if (tirage < 66) ambiant(p, S.APPROCHE, 0.5f, 0.7f);                                    // quelque chose approche
        else if (tirage < 73) ambiant(p, S.HURLEUR, 0.2f, 0.6f);                                     // un cri, très loin
        else if (tirage < 78) ambiant(p, S.VENT, 0.25f, 0.4f);                                       // une rafale de vent, très grave
        else tremblement(p, s);
    }
    private void etrange(ServerPlayer p) {
        UUID u = p.getUUID(); Vec3 l = p.position(), regard = p.getLookAngle();
        Vec3 dos = l.subtract(new Vec3(regard.x, 0, regard.z).normalize().scale(3));
        switch (alea.nextInt(7)) {
            case 0 -> { for (int n = 0; n < 4; n++) plus(6 * n, u, q -> son(q, S.PAS, SoundSource.AMBIENT, dos.x, dos.y, dos.z, 0.55f, 0.7f)); }   // des pas, derrière
            case 1 -> son(p, S.PORTE_FERME, SoundSource.AMBIENT, dos.x, dos.y, dos.z, 0.45f, 0.5f);    // une porte claque, quelque part
            case 2 -> son(p, S.CHAINES, SoundSource.AMBIENT, dos.x, dos.y, dos.z, 0.6f, 0.5f);         // des chaînes
            case 3 -> ambiant(p, S.MURMURE, 0.7f, 0.6f);                                               // un murmure
            case 4 -> son(p, S.GHAST, SoundSource.AMBIENT, dos.x, dos.y, dos.z, 0.12f, 0.5f);          // un cri très lointain
            case 5 -> { ambiant(p, S.MEULE, 0.5f, 0.5f);                                               // un mécanisme se met en marche dans les murs
                        plus(25, u, q -> ambiant(q, S.PISTON_SORT, 0.6f, 0.5f)); }
            default -> { ambiant(p, S.BALISE, 1f, 0.5f); effet(p, MobEffects.DARKNESS, 60, 0); }       // la lumière faiblit un instant
        }
    }
    /** Le Cube tremble : grondement, secousses de caméra (animation de coup, sans dégât ni déplacement), poussière qui tombe du plafond, craquements. */
    private void tremblement(ServerPlayer p, Salle s) {
        UUID u = p.getUUID();
        ambiant(p, S.TONNERRE, 0.35f, 0.5f); ambiant(p, S.ARDOISE, 0.8f, 0.5f);
        int secousses = 5 + alea.nextInt(5);
        for (int n = 0; n < secousses; n++) { int k = n; plus(4 + n * 4, u, q -> {
            if (q.isDeadOrDying()) return;
            q.connection.send(new ClientboundHurtAnimationPacket(q.getId(), alea.nextFloat() * 360f - 180f));
            Vec3 c = s.centre();
            part(poussiere(Labyrinthe.LAINE[s.couleur]), c.x, c.y + 6.5, c.z, 25, 3.0, 0.3, 3.0, 0.02);
            part(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y + 6.5, c.z, 6, 3.0, 0.2, 3.0, 0.005);
            if (k % 3 == 0) ambiant(q, S.PIERRE, 0.6f, 0.4f + alea.nextFloat() * 0.3f);
            if (k == secousses - 1) ambiant(q, S.ARDOISE, 0.5f, 0.4f);
        }); }
    }

    /* ------------------------------------------------------------------ sons, effets, particules, textes */
    /** Les sons, pris au premier usage (le registre est prêt) ; certains sont des Holder en 26.x, d'autres de simples SoundEvent. */
    private static final class S {
        static Holder<SoundEvent> h(SoundEvent s) { return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(s); }
        static final Holder<SoundEvent> PORTE_OUVRE = h(SoundEvents.IRON_DOOR_OPEN), PORTE_FERME = h(SoundEvents.IRON_DOOR_CLOSE), CAVE = SoundEvents.AMBIENT_CAVE,
            ENCLUME = h(SoundEvents.ANVIL_LAND), TRAPPE = h(SoundEvents.IRON_TRAPDOOR_OPEN), COEUR = h(SoundEvents.WARDEN_HEARTBEAT), CONDUIT = h(SoundEvents.CONDUIT_AMBIENT),
            BALISE_OFF = h(SoundEvents.BEACON_DEACTIVATE), EXTINCTION = h(SoundEvents.FIRE_EXTINGUISH), BLAZE = h(SoundEvents.BLAZE_SHOOT), LAVE = h(SoundEvents.LAVA_EXTINGUISH),
            PISTON_SORT = h(SoundEvents.PISTON_EXTEND), PISTON_RENTRE = h(SoundEvents.PISTON_CONTRACT), MEULE = h(SoundEvents.GRINDSTONE_USE), BALAYAGE = h(SoundEvents.PLAYER_ATTACK_SWEEP),
            BLESSE = h(SoundEvents.PLAYER_HURT), DEFI = h(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), VERROU = h(SoundEvents.CHEST_LOCKED), CLOCHETTES = SoundEvents.NOTE_BLOCK_CHIME,
            CLOCHE = SoundEvents.NOTE_BLOCK_BELL, TORCHE = h(SoundEvents.REDSTONE_TORCH_BURNOUT), VENT = h(SoundEvents.ELYTRA_FLYING), BASALTE = SoundEvents.AMBIENT_BASALT_DELTAS_LOOP,
            AMES = SoundEvents.AMBIENT_SOUL_SAND_VALLEY_LOOP, SOUS_EAU = h(SoundEvents.AMBIENT_UNDERWATER_LOOP), FORET = SoundEvents.AMBIENT_WARPED_FOREST_LOOP,
            MURMURE = SoundEvents.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS, APPROCHE = h(SoundEvents.WARDEN_NEARBY_CLOSER), HURLEUR = h(SoundEvents.SCULK_SHRIEKER_SHRIEK),
            PAS = h(SoundEvents.STONE_STEP), CHAINES = h(SoundEvents.CHAIN_PLACE), GHAST = h(SoundEvents.GHAST_SCREAM), BALISE = h(SoundEvents.BEACON_AMBIENT),
            TONNERRE = h(SoundEvents.LIGHTNING_BOLT_THUNDER), ARDOISE = h(SoundEvents.DEEPSLATE_BREAK), PIERRE = h(SoundEvents.STONE_BREAK);
        static final List<Holder<SoundEvent>> MUSIQUES = List.of(VENT, VENT, BASALTE, AMES, SOUS_EAU, CAVE, FORET);
    }
    private void son(ServerPlayer p, Holder<SoundEvent> s, SoundSource src, double x, double y, double z, float vol, float hauteur) { p.connection.send(new ClientboundSoundPacket(s, src, x, y, z, vol, hauteur, alea.nextLong())); }
    private void son(ServerPlayer p, Holder<SoundEvent> s, float vol, float hauteur) { son(p, s, SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), vol, hauteur); }
    private void ambiant(ServerPlayer p, Holder<SoundEvent> s, float vol, float hauteur) { son(p, s, SoundSource.AMBIENT, p.getX(), p.getY(), p.getZ(), vol, hauteur); }
    private static void couper(ServerPlayer p, Holder<SoundEvent> s) { p.connection.send(new ClientboundStopSoundPacket(s.value().location(), SoundSource.AMBIENT)); }
    private static void effet(ServerPlayer p, Holder<MobEffect> e, int duree, int niveau) { p.addEffect(new MobEffectInstance(e, duree, niveau, false, false)); }   // sans particules ni icône
    private void part(ParticleOptions o, double x, double y, double z, int n, double dx, double dy, double dz, double v) { monde.sendParticles(o, x, y, z, n, dx, dy, dz, v); }
    private static BlockParticleOption poussiere(String bloc) { return new BlockParticleOption(ParticleTypes.BLOCK, Labyrinthe.etat(bloc)); }
    private static MutableComponent t(String s, int c) { return Outils.t(s, c); }
    private static final int BLANC = Outils.BLANC, GRIS = Outils.GRIS, GRIS_FONCE = Outils.GRIS_FONCE, OR = Outils.OR, ROUGE = Outils.ROUGE, VERT = Outils.VERT, AQUA = Outils.AQUA, AQUA_FONCE = 0x00AAAA;

    /* ------------------------------------------------------------------ commandes */
    static void commandes(CommandDispatcher<CommandSourceStack> d) {
        for (String nom : new String[]{"recommencer", "restart", "depart"})
            d.register(Commands.literal(nom).executes(c -> I == null ? 0 : I.cmdRecommencer(c.getSource())));
        // « /cube … » : un seul argument libre, lu comme les arguments du plugin (les sous-commandes admin restent invisibles aux autres)
        d.register(Commands.literal("cube")
            .executes(c -> I == null ? 0 : I.cmdCube(c.getSource(), new String[0]))
            .then(Commands.argument("args", StringArgumentType.greedyString()).suggests(Cube::suggerer)
                .executes(c -> { String a = StringArgumentType.getString(c, "args").trim(); return I == null ? 0 : I.cmdCube(c.getSource(), a.isEmpty() ? new String[0] : a.split("\\s+")); })));
    }
    private static CompletableFuture<Suggestions> suggerer(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        if (I == null) return b.buildFuture();
        String reste = b.getRemaining(); String[] args = reste.split(" ", -1);
        SuggestionsBuilder dernier = b.createOffset(b.getStart() + reste.lastIndexOf(' ') + 1);
        for (String s : I.completer(c.getSource(), args)) dernier.suggest(s);
        return dernier.buildFuture();
    }
    /** Maxime (23/09/2026) : « seul moi, Kripy, peut le faire ». Les sous-commandes d'admin ne dépendent PAS du statut op :
     *  seulement la console / RCON (aucune entité) et les UUID listés dans « cube.admins » (par défaut : croziors). */
    private boolean estAdmin(CommandSourceStack src) { ServerPlayer p = src.getPlayer(); return p == null ? src.getEntity() == null : admins.contains(p.getUUID()); }
    private static final List<String> SOUS_ADMIN = List.of("porte", "ou", "ouvrir", "solution", "regenerer");
    private int cmdRecommencer(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer(); if (p == null) return 0;
        UUID u = p.getUUID();
        surLePont.remove(u);   // depuis le pont aussi : on se réveille dedans, et on est suivi
        teleporter(p, lab.depart.centre(), 0f); finTentative(p); morts.remove(u);
        p.removeAllEffects(); p.clearFire(); p.setHealth(20);
        p.sendSystemMessage(t("Nouvelle tentative. Le chronomètre repart quand vous quittez la salle blanche.", GRIS));
        return 1;
    }
    private int cmdCube(CommandSourceStack src, String[] args) {
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "info";
        boolean admin = estAdmin(src);
        if (!admin && !a.equals("info") && !a.equals("top") && !a.equals("loin")) { src.sendSystemMessage(Component.translatable("command.unknown.command").withStyle(ChatFormatting.RED)); return 0; }   // pour les autres, ces sous-commandes n'existent pas
        if (a.equals("loin")) {
            List<Map.Entry<String, Integer>> l = new ArrayList<>();
            for (Rec rc : records.values()) if (rc.sallesMax > 0) l.add(Map.entry(rc.nom, rc.sallesMax));
            l.sort((x, y) -> y.getValue() - x.getValue());
            src.sendSystemMessage(t("Le plus loin dans le Cube (salles traversées en une tentative) :", OR));
            int n = 0; for (Map.Entry<String, Integer> e : l) { if (++n > 10) break; src.sendSystemMessage(t(n + ". " + e.getKey() + " — " + e.getValue() + " salles", BLANC)); }
            if (l.isEmpty()) src.sendSystemMessage(t("Personne n'a encore quitté la salle blanche.", GRIS));
            return 1;
        }
        if (a.equals("top")) {
            List<Map.Entry<String, Long>> l = new ArrayList<>();
            for (Rec rc : records.values()) if (rc.meilleur > 0) l.add(Map.entry(rc.nom, rc.meilleur));
            l.sort(Map.Entry.comparingByValue());
            src.sendSystemMessage(t("Sortis du Cube, les plus rapides :", OR));
            int n = 0; for (Map.Entry<String, Long> e : l) { if (++n > 10) break; src.sendSystemMessage(t(n + ". " + e.getKey() + " — " + duree(e.getValue()), BLANC)); }
            if (l.isEmpty()) src.sendSystemMessage(t("Personne n'est encore sorti.", GRIS));
            return 1;
        }
        if (a.equals("regenerer")) {
            src.sendSystemMessage(t("Reconstruction du Cube en cours (quelques secondes)…", GRIS));
            construire(true, src); return 1;
        }
        if (a.equals("porte")) { cmdPorte(src, args); return 1; }
        if (a.equals("ou")) { cmdOu(src, args); return 1; }
        if (a.equals("ouvrir")) { cmdOuvrir(src, args); return 1; }
        src.sendSystemMessage(t("Le Cube : " + lab.salles.size() + " salles (" + lab.nx + " × " + lab.ny + " niveaux × " + lab.nz + "), " + lab.nbMortelles() + " mortelles, graine " + lab.graine + ". /cube top · /cube loin", GRIS));
        if (admin) src.sendSystemMessage(t("Admin : /cube porte [montrer] · /cube ou [joueur] · /cube ouvrir [joueur] [chemin|tout] · /cube solution · /cube regenerer", AQUA_FONCE));
        if (admin && (a.equals("info") || a.equals("solution"))) src.sendSystemMessage(t(lab.plan.resume(), GRIS_FONCE));
        if (admin && a.equals("solution")) {
            StringBuilder b = new StringBuilder("Chemin : ");
            for (int c : lab.plan.chemin) b.append(lab.salles.get(c).coord()).append(' ');
            src.sendSystemMessage(t(b.toString(), GRIS_FONCE));
        }
        return 1;
    }
    private List<String> completer(CommandSourceStack src, String[] args) {
        List<String> choix = new ArrayList<>();
        boolean admin = estAdmin(src);
        if (args.length == 1) { choix.addAll(List.of("info", "top", "loin")); if (admin) choix.addAll(SOUS_ADMIN); }
        else if (admin && args.length == 2 && args[0].equalsIgnoreCase("porte")) choix.add("montrer");
        else if (admin && args.length == 2 && (args[0].equalsIgnoreCase("ou") || args[0].equalsIgnoreCase("ouvrir"))) { for (ServerPlayer q : joueurs()) choix.add(nom(q)); if (args[0].equalsIgnoreCase("ouvrir")) choix.addAll(List.of("chemin", "tout")); }
        else if (admin && args.length == 3 && args[0].equalsIgnoreCase("ouvrir")) choix.addAll(List.of("chemin", "tout"));
        String debutMot = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
        choix.removeIf(c -> !c.toLowerCase(Locale.ROOT).startsWith(debutMot));
        return choix;
    }

    /* ------------------------------------------------------------------ commandes admin (Maxime, 23/09/2026) */
    private Salle salleDuJoueur(ServerPlayer p) { Salle s = ou.get(p.getUUID()); return s != null ? s : lab.salleDe(p.position()); }
    /** /cube porte [montrer] : dans la salle de l'admin, quelle porte mène à la sortie par le plus court chemin, lesquelles tuent. */
    private void cmdPorte(CommandSourceStack src, String[] args) {
        ServerPlayer p = src.getPlayer();
        if (p == null) { src.sendSystemMessage(t("À utiliser en jeu.", ROUGE)); return; }
        Salle s = salleDuJoueur(p);
        if (s == null) { p.sendSystemMessage(t("Vous n'êtes dans aucune salle (mur, porte ou hors du Cube).", ROUGE)); return; }
        int ici = lab.plan.distanceSortie(s.id);
        p.sendSystemMessage(t("Salle " + s.code + " " + s.coord() + (s.sure ? (s.sortie ? " — c'est la sortie." : " — sûre, sortie à " + ici + " salle" + (ici > 1 ? "s" : "") + ".") : " — MORTELLE."), s.sure ? VERT : ROUGE));
        List<Integer> bonnes = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            Salle v = lab.voisin(s, d); String dir = Labyrinthe.DIRECTIONS[d];
            if (v == null) { p.sendSystemMessage(t("  " + dir + " : le vide (bord du Cube)", GRIS_FONCE)); continue; }
            int dv = lab.plan.distanceSortie(v.id);
            boolean bonne = v.sure && dv >= 0 && ici >= 0 && dv == ici - 1;
            if (bonne) bonnes.add(d);
            p.sendSystemMessage(t("  " + dir + " → " + v.code + " : " + (!v.sure ? "MORTELLE" : bonne ? "vers la SORTIE (" + dv + " salle" + (dv > 1 ? "s" : "") + " restante" + (dv > 1 ? "s" : "") + ")" : v.sortie ? "la SORTIE" : "sûre (sortie à " + dv + ")"),
                !v.sure ? ROUGE : bonne ? VERT : GRIS));
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("montrer")) {
            if (bonnes.isEmpty()) { p.sendSystemMessage(t("Aucune porte à montrer.", GRIS)); return; }
            montrer(p.getUUID(), s, bonnes, 0);   // particules envoyées à ce joueur seul, 10 s
            p.sendSystemMessage(t("La bonne porte est surlignée 10 s, pour vous seul.", GRIS));
        }
    }
    private void montrer(UUID u, Salle s, List<Integer> bonnes, int n) {
        ServerPlayer p = joueur(u); if (p == null || n >= 20) return;
        for (int d : bonnes) { double[] c = centreIssue(s, d); monde.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, false, false, c[0], c[1], c[2], 12, 0.35, 0.5, 0.35, 0); }
        plus(10, () -> montrer(u, s, bonnes, n + 1));
    }
    private static double[] centreIssue(Salle s, int d) {
        return switch (d) {
            case 0 -> new double[]{s.ox() + 8.5, s.oy() + 4, s.oz() + 4.5}; case 1 -> new double[]{s.ox() + 0.5, s.oy() + 4, s.oz() + 4.5};
            case 2 -> new double[]{s.ox() + 4.5, s.oy() + 4, s.oz() + 8.5}; case 3 -> new double[]{s.ox() + 4.5, s.oy() + 4, s.oz() + 0.5};
            case 4 -> new double[]{s.ox() + 4.5, s.oy() + 7.5, s.oz() + 4.5}; default -> new double[]{s.ox() + 4.5, s.oy() + 1.2, s.oz() + 4.5};
        };
    }
    /** /cube ou [joueur] : où en est chaque joueur du Cube. */
    private void cmdOu(CommandSourceStack src, String[] args) {
        List<ServerPlayer> l = new ArrayList<>();
        if (args.length > 1) { ServerPlayer q = serveur.getPlayerList().getPlayerByName(args[1]); if (q == null) { src.sendSystemMessage(t("Joueur introuvable : " + args[1], ROUGE)); return; } l.add(q); }
        else for (ServerPlayer q : joueurs()) if (q.level() == monde) l.add(q);
        if (l.isEmpty()) { src.sendSystemMessage(t("Personne dans le Cube.", GRIS)); return; }
        for (ServerPlayer q : l) {
            Salle s = salleDuJoueur(q);
            if (s == null) { src.sendSystemMessage(t(nom(q) + " : entre deux salles (porte, mur) ou hors du Cube" + (q.isDeadOrDying() ? ", mort" : "") + ".", GRIS)); continue; }
            Integer ds = derniereSure.get(q.getUUID()); int ref = s.sure ? s.id : ds != null ? ds : -1;
            int reste = lab.plan.distanceSortie(ref), av = lab.plan.avance(ref), n = salles(q.getUUID());
            src.sendSystemMessage(t(nom(q) + " : niveau " + (s.j + 1) + ", salle " + s.coord() + " " + s.code + (s.sure ? "" : " (MORTELLE)") + (q.isDeadOrDying() ? ", mort" : "") + " · " + n + " salle" + (n > 1 ? "s" : "") + " traversée" + (n > 1 ? "s" : "")
                + " · avancement " + av + " % · " + (reste >= 0 ? reste + " salle" + (reste > 1 ? "s" : "") + " jusqu'à la sortie" : "distance à la sortie inconnue"), s.sure ? BLANC : ROUGE));
        }
    }
    /** /cube ouvrir [joueur] [chemin|tout] : ouvre des issues de la salle de ce joueur (ou de l'admin), même scellée.
     *  Sans option : les portes vers des salles sûres ; « chemin » : seulement celle du plus court chemin vers la sortie (depuis une
     *  salle mortelle : la salle sûre voisine la plus proche de la sortie) ; « tout » : toutes, bord compris. */
    private void cmdOuvrir(CommandSourceStack src, String[] args) {
        ServerPlayer q = null; String mode = "sures";
        for (int i = 1; i < args.length; i++) {
            String x = args[i].toLowerCase(Locale.ROOT);
            if (x.equals("chemin") || x.equals("tout")) mode = x;
            else if (x.equals("sure") || x.equals("sûre") || x.equals("sures")) mode = "sures";
            else { q = serveur.getPlayerList().getPlayerByName(args[i]); if (q == null) { src.sendSystemMessage(t("Joueur introuvable : " + args[i], ROUGE)); return; } }
        }
        if (q == null) { q = src.getPlayer(); if (q == null) { src.sendSystemMessage(t("Préciser un joueur : /cube ouvrir <joueur> [chemin|tout]", ROUGE)); return; } }
        Salle s = salleDuJoueur(q);
        if (s == null) { src.sendSystemMessage(t(nom(q) + " n'est dans aucune salle.", ROUGE)); return; }
        boolean[] ouvrir = new boolean[6];
        if (mode.equals("chemin")) {
            int meilleure = -1, meilleureDist = Integer.MAX_VALUE;
            for (int d = 0; d < 6; d++) { Salle v = lab.voisin(s, d); if (v == null || !v.sure) continue; int dv = lab.plan.distanceSortie(v.id); if (dv >= 0 && dv < meilleureDist) { meilleureDist = dv; meilleure = d; } }
            int ici = lab.plan.distanceSortie(s.id);
            if (s.sortie) { src.sendSystemMessage(t(nom(q) + " est déjà dans la sortie.", GRIS)); return; }
            if (meilleure < 0 || (ici >= 0 && meilleureDist >= ici)) { src.sendSystemMessage(t("Aucune porte ne rapproche " + nom(q) + " de la sortie depuis " + s.code + " " + s.coord() + ".", ROUGE)); return; }
            ouvrir[meilleure] = true;
        } else for (int d = 0; d < 6; d++) { Salle v = lab.voisin(s, d); ouvrir[d] = mode.equals("tout") || (v != null && v.sure); }
        scelleeJusquA.remove(s.id);
        int n = 0; StringBuilder dirs = new StringBuilder();
        for (int d = 0; d < 6; d++) {
            if (!ouvrir[d]) continue;
            Salle v = lab.voisin(s, d);
            if (v != null) scelleeJusquA.remove(v.id);   // la porte est partagée : on lève aussi le scellement du voisin pour qu'elle reste manipulable
            lab.basculer(monde, s, d, true); n++; dirs.append(dirs.length() > 0 ? ", " : "").append(Labyrinthe.DIRECTIONS[d]).append(v != null ? " → " + v.code : " → le vide");
        }
        for (ServerPlayer j : dedans(s)) { son(j, S.PORTE_OUVRE, 1f, 0.7f); son(j, S.TRAPPE, 1f, 0.7f); }
        String quoi = mode.equals("chemin") ? "porte du plus court chemin" : mode.equals("tout") ? "toutes les issues" : "issues vers des salles sûres";
        src.sendSystemMessage(t("Salle " + s.code + " " + s.coord() + " de " + nom(q) + (s.sure ? "" : " (MORTELLE)") + " — " + quoi + " : " + n + " ouverte" + (n > 1 ? "s" : "") + (n > 0 ? " (" + dirs + ")" : "") + ", scellement levé.", VERT));
    }
}
