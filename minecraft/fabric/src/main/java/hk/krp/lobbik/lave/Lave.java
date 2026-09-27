package hk.krp.lobbik.lave;

import hk.krp.lobbik.Affichages;
import hk.krp.lobbik.Billets;
import hk.krp.lobbik.Config;
import hk.krp.lobbik.Hub;
import hk.krp.lobbik.Lobbik;
import hk.krp.lobbik.Outils;
import hk.krp.lobbik.Reseau;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * La Mer de lave (27/09/2026) — 4ᵉ serveur du réseau Lobbik (rôle « lave »). Un parcours de sauts de ~800 blocs au-dessus d'une mer
 * de lave, à ciel ouvert, avec fourches et fausses pistes ; 4 plateformes de réapparition ; tomber dans la lave ne tue pas : on
 * repart de sa dernière plateforme (sifflement, fumée). Même esprit et mêmes fichiers que La Tour (lobbik/lave/*.json).
 */
public final class Lave {
    public static Lave I;
    private final MinecraftServer serveur; private final ServerLevel monde; private final Config cfg; private final Hub hub; private final boolean reseau;
    public final Parcours parcours;
    private final Path dossier;
    /** uuid → {progrès (blocs), record (blocs), réapparition (0..4), chutes, temps de jeu (s), arrivées, meilleur temps (s, 0 = aucun)} */
    private final Map<UUID, int[]> joueurs = new ConcurrentHashMap<>();
    private final Map<UUID, String> noms = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> dernierePos = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> dernierSol = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> avant = new HashMap<>();
    private final Map<UUID, Long> arrivees = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportes = new HashMap<>();
    private final Map<UUID, Long> figes = new HashMap<>();
    private final Map<UUID, Long> chrono = new HashMap<>();          // départ de la course en cours (quitte la dalle de départ)
    private final Map<UUID, List<String>> lignesTableau = new HashMap<>();
    private final List<Object[]> plusTard = new ArrayList<>();
    private final Deque<Runnable> chantier = new ArrayDeque<>();      // construction par étapes (quelques ms par tick)
    private long tic;
    private boolean construit = false;
    private static final Identifier FIGE = Identifier.fromNamespaceAndPath("lobbik", "fige");
    private static final int SANS_VOISINS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Map<String, BlockState> ETATS = new HashMap<>();
    static BlockState s(String id) { return ETATS.computeIfAbsent(id, k -> BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(k)).defaultBlockState()); }
    private void b(int x, int y, int z, String id) { monde.setBlock(new BlockPos(x, y, z), s(id), SANS_VOISINS); }

    public Lave(MinecraftServer s, Config cfg) {
        this.serveur = s; this.monde = s.overworld(); this.cfg = cfg; this.hub = Reseau.I.site;
        this.reseau = cfg.b("lave.reseau", true);
        this.parcours = new Parcours(cfg.l("lave.graine", 20260927L), cfg.i("lave.longueur", 800));
        this.dossier = FabricLoader.getInstance().getGameDir().resolve("lobbik").resolve("lave");
        charger();
        I = this;
    }

    /* ================================================================== démarrage et construction */
    public void demarrer() {
        for (String c : new String[]{"gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false", "gamerule spawn_monsters false",
                "gamerule fall_damage false", "gamerule show_advancement_messages false", "gamerule immediate_respawn true", "gamerule fire_spread_radius_around_player 0",
                "gamerule do_fire_tick false", "time set 18000", "weather clear"}) commande(c);
        Parcours.Dalle d = parcours.depart;
        commande("setworldspawn " + d.x + " " + (d.y + 1) + " " + d.z);
        Path marque = dossier.resolve("lave-" + parcours.graine + "-" + parcours.longueur + ".ok");
        if (!Files.exists(marque)) {
            Lobbik.LOG.info("Construction de la Mer de lave : {} dalles sur {} blocs…", parcours.dalles.size(), parcours.longueur);
            planifierConstruction(() -> {
                try { Files.createDirectories(dossier); Files.writeString(marque, "1"); } catch (IOException ignored) { }
                construit = true; poserTextes();
                Lobbik.LOG.info("Mer de lave construite.");
            });
        } else { construit = true; poserTextes(); }
        ecrireAtomique(dossier.resolve("parcours.json"), parcours.json());
        hub.envoyer("lave_carte", parcours.json());
        new Thread(hub::relireLies, "lave-membres").start();
    }
    public void arreter() { sauver(); }
    private void commande(String c) { serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack().withSuppressedOutput(), c); }

    /** La mer (fond, lave, rebord au ras de la lave : pas de murs), puis les dalles et leurs décors — tranche par tranche. */
    private void planifierConstruction(Runnable fin) { chantier.addAll(etapes(monde, parcours)); chantier.add(fin); }
    /** Étapes de construction de la Mer de lave dans un monde (le serveur du jeu, ou une copie vue de loin depuis le hub, La Tour, Le Cube). */
    public static List<Runnable> etapes(ServerLevel w, Parcours parcours) {
        List<Runnable> l = new ArrayList<>();
        int x1 = Parcours.X0 - Parcours.DEMI_LARGEUR, x2 = Parcours.X0 + Parcours.DEMI_LARGEUR, z1 = parcours.zMin(), z2 = parcours.zMax();
        for (int z0 = z1; z0 <= z2; z0 += 4) {
            final int za = z0, zb = Math.min(z2, z0 + 3);
            l.add(() -> {
                for (int z = za; z <= zb; z++) for (int x = x1 - 1; x <= x2 + 1; x++) {
                    boolean bord = x < x1 || x > x2 || z == z1 || z == z2;
                    bs(w, x, Parcours.Y_LAVE - 2, z, "basalt");
                    bs(w, x, Parcours.Y_LAVE - 1, z, bord ? "blackstone" : "magma_block");
                    bs(w, x, Parcours.Y_LAVE, z, bord ? "polished_blackstone" : "lava");
                }
            });
        }
        for (Parcours.Dalle d : parcours.dalles) l.add(() -> poserDalle(w, parcours, d, true));
        return l;
    }
    private static void bs(ServerLevel w, int x, int y, int z, String id) { w.setBlock(new BlockPos(x, y, z), s(id), SANS_VOISINS); }
    private static final String[] ZONES = {"white_concrete", "light_blue_concrete", "cyan_concrete", "lime_concrete", "yellow_concrete"};
    void poser(Parcours.Dalle d, boolean visible) { poserDalle(monde, parcours, d, visible); }
    static void poserDalle(ServerLevel monde, Parcours parcours, Parcours.Dalle d, boolean visible) {
        int m = d.largeur / 2;
        String mat = !visible ? "air" : switch (d.genre) {
            case 1 -> "slime_block"; case 2 -> "blue_ice"; case 3 -> "orange_concrete"; case 4 -> "gold_block"; case 5 -> "polished_blackstone"; case 6 -> "emerald_block";
            default -> d.largeur == 3 ? "smooth_quartz" : ZONES[Math.min(4, d.progres * 5 / Math.max(1, parcours.longueur))];
        };
        for (int dx = -m; dx <= m; dx++) for (int dz = -m; dz <= m; dz++) bs(monde, d.x + dx, d.y, d.z + dz, mat);
        if (!visible) return;
        if (d.genre == 4 || d.genre == 5 || d.genre == 6) {
            // réapparition / départ / arrivée : lanterne au centre, colonne de lumière visible de loin
            bs(monde, d.x, d.y, d.z, "sea_lantern");
            int h = d.genre == 6 ? 6 : 3;
            for (int k = 1; k <= h; k++) bs(monde, d.x, d.y + k, d.z, "end_rod");
            for (int dx = -m; dx <= m; dx += Math.max(1, 2 * m)) for (int dz = -m; dz <= m; dz += Math.max(1, 2 * m)) bs(monde, d.x + dx, d.y + 1, d.z + dz, d.genre == 6 ? "sea_lantern" : "lantern");
        }
    }
    void avertir(Parcours.Dalle d) { int m = d.largeur / 2; for (int dx = -m; dx <= m; dx++) for (int dz = -m; dz <= m; dz++) b(d.x + dx, d.y, d.z + dz, "red_concrete"); }
    private void poserTextes() {
        Affichages.nettoyer(monde, "lobbik_lave");
        Parcours.Dalle d = parcours.depart;
        Affichages.texte(monde, "lobbik_lave", d.x + 0.5, d.y + 3.2, d.z + 0.5, 1.4f, Display.BillboardConstraints.CENTER, 0f,
            Outils.tg("MER DE LAVE", 0xFF7A1A).append("\n").append(Outils.t(parcours.longueur + " blocs · 4 réapparitions · tomber ne tue pas", Outils.GRIS)));
        for (int k = 0; k < parcours.reapparitions.size(); k++) { Parcours.Dalle c = parcours.dalles.get(parcours.reapparitions.get(k));
            Affichages.texte(monde, "lobbik_lave", c.x + 0.5, c.y + 4.4, c.z + 0.5, 1.2f, Display.BillboardConstraints.CENTER, 0f, Outils.tg("Réapparition " + (k + 1) + " / 4", Outils.OR)); }
        Parcours.Dalle a = parcours.arrivee;
        Affichages.texte(monde, "lobbik_lave", a.x + 0.5, a.y + 7.5, a.z + 0.5, 2f, Display.BillboardConstraints.CENTER, 0f, Outils.tg("ARRIVÉE", Outils.VOLT));
    }

    /* ================================================================== chaque tick */
    public void tic() {
        tic++;
        if (!chantier.isEmpty()) { long t0 = System.nanoTime(); while (!chantier.isEmpty() && System.nanoTime() - t0 < 25_000_000L) chantier.poll().run(); }
        if (!plusTard.isEmpty()) { List<Object[]> l = new ArrayList<>(plusTard); plusTard.clear(); for (Object[] o : l) { if ((long) o[0] <= tic) ((Runnable) o[1]).run(); else plusTard.add(o); } }
        if (!construit) return;
        for (ServerPlayer p : serveur.getPlayerList().getPlayers()) deplacement(p);
        if (tic % 20 == 0) cycleEphemeres();
        if (tic % 100 == 40) tableau();
        if (tic % 100 == 0) for (ServerPlayer q : serveur.getPlayerList().getPlayers()) j(q.getUUID())[4] += 5;
        if (tic % 60 == 0 && !serveur.getPlayerList().getPlayers().isEmpty()) positionsVersSite();
        if (tic % 60 == 30) ecrireDirect();
        if (tic % 1200 == 0) { new Thread(hub::relireLies, "lave-membres").start(); new Thread(this::sauver, "lave-sauvegarde").start(); }
    }
    private int cycle = 0;
    /** Dalles éphémères : 6 s visibles (la dernière en rouge), 3 s disparues, décalées pour ne pas clignoter ensemble. */
    private void cycleEphemeres() {
        cycle++;
        for (Parcours.Dalle d : parcours.dalles) {
            if (d.genre != 3) continue;
            int ph = (cycle + d.id * 3) % 9;
            if (ph == 0) poser(d, true); else if (ph == 5) avertir(d); else if (ph == 6) poser(d, false);
        }
    }
    private void plusTard(int t, Runnable r) { plusTard.add(new Object[]{tic + t, r}); }
    private int[] j(UUID u) { return joueurs.computeIfAbsent(u, k -> new int[]{0, 0, 0, 0, 0, 0, 0}); }

    /* ================================================================== arrivée, départ */
    public void rejoint(ServerPlayer p) {
        UUID u = p.getUUID();
        if (cfg.b("membres_seulement", true) && hub.listeChargee() && !hub.estLie(u)) {
            p.connection.disconnect(Component.literal("Serveur réservé aux membres de Lobbik · code : " + Reseau.I.codePour(u) + "\nlobbik.com → Minecraft → « Lier mon compte »")); return; }
        noms.put(u, p.getPlainTextName());
        p.setGameMode(GameType.ADVENTURE); p.getFoodData().setFoodLevel(20); p.setHealth(p.getMaxHealth());
        p.addEffect(new MobEffectInstance(MobEffects.SATURATION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));   // la lave brûle sans tuer
        arrivees.put(u, System.currentTimeMillis()); avant.remove(u); lignesTableau.remove(u);
        int[] j = j(u);
        double[] ici = dernierePos.remove(u);
        if (ici != null) {
            // il avait quitté en plein parcours : on le remet là (par la porte du hub : figé 3 s, le temps que le terrain arrive)
            boolean pont = Billets.PAR_LE_PONT.containsKey(u);
            plusTard(2, () -> { if (!p.hasDisconnected()) { teleporter(p, ici[0], ici[1], ici[2], (float) ici[3], (float) ici[4]); if (pont) figer(p, 3);
                p.sendSystemMessage(Outils.t("Vous reprenez exactement là où vous aviez quitté.", Outils.GRIS)); } });
        } else if (!(reseau && Billets.PAR_LE_PONT.containsKey(u))) plusTard(2, () -> { if (!p.hasDisconnected()) versPoint(p, j); });
        p.sendSystemMessage(Outils.tg("Mer de lave", 0xFF7A1A).append(Outils.t(" — sautez de dalle en dalle jusqu'à l'arrivée. /cp : dernière réapparition · /depart : recommencer · /top", Outils.GRIS)));
        if (j[1] > 0) p.sendSystemMessage(Outils.t("Votre record : " + j[1] + " blocs" + (j[6] > 0 ? " · meilleur temps " + temps(j[6]) : ""), Outils.GRIS));
        hub.envoyer("lave_connexion", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + "}");
    }
    public void quitte(ServerPlayer p) {
        UUID u = p.getUUID(); avant.remove(u); teleportes.remove(u); lignesTableau.remove(u); chrono.remove(u);
        if (figes.remove(u) != null) defiger(p);
        double[] sol = dernierSol.remove(u);
        if (p.onGround() && p.getY() > Parcours.Y_LAVE + 2 && p.getZ() > parcours.zMin()) sol = new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
        if (sol != null && sol[2] < parcours.zMin()) sol = null;   // parti par le pont : pas dans le décor du hub
        if (sol != null) dernierePos.put(u, sol); else dernierePos.remove(u);
        Long a = arrivees.remove(u); int[] j = j(u);
        hub.envoyer("lave_deconnexion", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"progres\":" + j[0] + ",\"record\":" + j[1]
            + ",\"chutes\":" + j[3] + ",\"session\":" + (a == null ? 0 : (System.currentTimeMillis() - a) / 1000) + "}");
        new Thread(this::sauver, "lave-sauvegarde").start();
    }
    public void reapparu(ServerPlayer p) {
        p.addEffect(new MobEffectInstance(MobEffects.SATURATION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        versPoint(p, j(p.getUUID()));
    }

    /* ================================================================== figé 3 s (retour à sa position depuis le hub) */
    private void figer(ServerPlayer p, int sec) {
        figes.put(p.getUUID(), System.currentTimeMillis() + sec * 1000L);
        for (var a : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) { AttributeInstance ai = p.getAttribute(a);
            if (ai != null) { ai.removeModifier(FIGE); ai.addTransientModifier(new AttributeModifier(FIGE, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)); } }
        Outils.titre(p, Component.empty(), Outils.t("Retour à votre position…", Outils.GRIS), 0, sec * 20, 8);
    }
    private void defiger(ServerPlayer p) { for (var a : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) { AttributeInstance ai = p.getAttribute(a); if (ai != null) ai.removeModifier(FIGE); } }

    private Parcours.Dalle pointDeRetour(int[] j) { return parcours.reapparition(j[2]); }
    private void versPoint(ServerPlayer p, int[] j) { Parcours.Dalle d = pointDeRetour(j); teleporter(p, d.x + 0.5, d.y + 1.02, d.z + 0.5, 180f, p.getXRot()); }
    private void teleporter(ServerPlayer p, double x, double y, double z, float yaw, float pitch) {
        Outils.teleporter(p, x, y, z, yaw, pitch); p.fallDistance = 0; p.clearFire();
        teleportes.put(p.getUUID(), System.currentTimeMillis()); avant.put(p.getUUID(), new double[]{x, y, z});
    }
    private static String temps(int s) { return s >= 3600 ? (s / 3600) + " h " + String.format("%02d", (s % 3600) / 60) + " min" : (s / 60) + " min " + String.format("%02d", s % 60) + " s"; }

    /* ================================================================== sauts, chutes dans la lave, progression (à chaque tick) */
    private void deplacement(ServerPlayer p) {
        if (p.isSpectator() || p.isCreative()) { avant.remove(p.getUUID()); return; }
        UUID u = p.getUUID(); double x = p.getX(), y = p.getY(), z = p.getZ();
        double[] a = avant.put(u, new double[]{x, y, z});
        Long fige = figes.get(u);
        if (fige != null) {
            if (fige < System.currentTimeMillis()) { figes.remove(u); defiger(p); }
            else if (a != null && (Math.abs(a[0] - x) > 0.05 || Math.abs(a[2] - z) > 0.05 || y - a[1] > 0.05)) { Outils.teleporter(p, a[0], a[1], a[2], p.getYRot(), p.getXRot()); avant.put(u, a); return; }
        }
        if (a == null) return;
        int[] j = j(u);
        if (z < parcours.zMin() - 2) return;   // sur le pont ou côté hub : c'est le réseau qui s'en occupe
        boolean sol = p.onGround();
        if (sol && y > Parcours.Y_LAVE + 2) dernierSol.put(u, new double[]{x, y, z, p.getYRot(), p.getXRot()});
        // dans la lave (ou juste au-dessus) : sifflement, fumée, retour à la dernière réapparition
        if (y < Parcours.Y_LAVE + 1.4 || p.isInLava()) {
            j[3]++; chrono.remove(u);
            monde.sendParticles(ParticleTypes.LARGE_SMOKE, x, Parcours.Y_LAVE + 1, z, 24, 0.4, 0.3, 0.4, 0.02);
            Outils.son(p, SoundEvents.LAVA_EXTINGUISH, 1f, 0.8f);
            versPoint(p, j);
            Outils.action(p, Outils.t("Dans la lave ! Retour à " + (j[2] == 0 ? "au départ" : "la réapparition " + j[2]) + " (" + j[3] + " chutes)", Outils.ROUGE));
            hub.envoyer("lave_chute", "{\"uuid\":" + Hub.j(u.toString()) + ",\"progres\":" + j[0] + "}");
            return;
        }
        if (!sol) return;
        Parcours.Dalle d = parcours.sous(x, y, z);
        if (d == null) return;
        if (d.genre == 1 && a[1] - y > 0.01) Outils.son(p, SoundEvents.SLIME_BLOCK_FALL, 0.8f, 1.2f);   // rebond
        if (d.genre == 5) { chrono.remove(u); return; }          // sur la dalle de départ : le chrono part en la quittant
        if (!chrono.containsKey(u) && j[2] == 0) chrono.put(u, System.currentTimeMillis());
        if (d.progres > j[0] || d.genre == 4 || d.genre == 6) {
            boolean progres = d.progres > j[0];
            j[0] = Math.max(j[0], d.progres);
            if (d.progres > j[1]) { j[1] = d.progres; if (d.progres / 50 != (j[1] - 1) / 50) hub.envoyer("lave_record", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"record\":" + j[1] + "}"); }
            int n = parcours.reapparitions.indexOf(d.id) + 1;
            if (d.genre == 4 && n > j[2]) {
                j[2] = n;
                Outils.titre(p, Outils.tg("Réapparition " + n + " / 4", Outils.OR), Outils.t(d.progres + " blocs parcourus", Outils.GRIS), 4, 40, 10);
                Outils.son(p, SoundEvents.BEACON_ACTIVATE, 0.7f, 1.3f);
            } else if (progres && d.progres % 10 < 3) Outils.action(p, Outils.t(d.progres + " / " + parcours.longueur + " blocs" + (d.progres >= j[1] ? "  ★ record" : ""), Outils.VERT));
            if (d.genre == 6) arrivee(p, j);
        }
    }
    private void arrivee(ServerPlayer p, int[] j) {
        UUID u = p.getUUID(); Long t0 = chrono.remove(u);
        int sec = t0 == null ? 0 : (int) ((System.currentTimeMillis() - t0) / 1000);
        j[5]++; boolean meilleur = sec > 0 && (j[6] == 0 || sec < j[6]); if (meilleur) j[6] = sec;
        Outils.titre(p, Outils.tg("Arrivée !", Outils.VOLT), Outils.t(sec > 0 ? temps(sec) + (meilleur ? " · meilleur temps" : "") : "Bravo", Outils.BLANC), 6, 80, 20);
        Outils.son(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Parcours.Dalle a = parcours.arrivee;
        for (int k = 0; k < 6; k++) { final int kk = k; plusTard(kk * 8, () -> commande("summon firework_rocket " + (a.x + (kk % 3) - 1) + " " + (a.y + 2) + " " + (a.z + (kk / 3) - 1)
            + " {LifeTime:" + (18 + kk * 3) + ",FireworksItem:{id:\"firework_rocket\",count:1,components:{\"minecraft:fireworks\":{explosions:[{shape:\"large_ball\",colors:[I;13039616,58879],has_twinkle:true}]}}}}")); }
        for (ServerPlayer q : serveur.getPlayerList().getPlayers()) q.sendSystemMessage(Outils.tg("★ " + p.getPlainTextName() + " a traversé la Mer de lave" + (sec > 0 ? " en " + temps(sec) : "") + " !", 0xFF7A1A));
        hub.envoyer("lave_arrivee", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"temps\":" + sec + ",\"chutes\":" + j[3] + ",\"longueur\":" + parcours.longueur + "}");
        j[0] = 0; j[2] = 0;
        p.sendSystemMessage(Outils.t("Tapez /depart pour recommencer, ou /lobbik pour rentrer au hub.", Outils.GRIS));
    }

    /* ================================================================== commandes */
    public int cmdCp(ServerPlayer p) { int[] j = j(p.getUUID()); versPoint(p, j); chrono.remove(p.getUUID()); Outils.action(p, Outils.t(j[2] == 0 ? "Retour au départ" : "Retour à la réapparition " + j[2], Outils.GRIS)); return 1; }
    public int cmdDepart(ServerPlayer p) { int[] j = j(p.getUUID()); j[0] = 0; j[2] = 0; chrono.remove(p.getUUID()); versPoint(p, j); Outils.action(p, Outils.t("Nouveau départ !", Outils.VOLT)); return 1; }
    public int cmdTop(ServerPlayer p) {
        p.sendSystemMessage(Outils.tg("Mer de lave — meilleurs", 0xFF7A1A)); int k = 1;
        for (Map.Entry<UUID, int[]> e : classement(10)) { int[] v = e.getValue();
            p.sendSystemMessage(Outils.t(k++ + ". " + nom(e.getKey()) + " — " + (v[6] > 0 ? "arrivé en " + temps(v[6]) : v[1] + " blocs"), Outils.BLANC)); }
        return 1;
    }
    public String infos() { return "Mer de lave : " + parcours.dalles.size() + " dalles · " + parcours.longueur + " blocs · graine " + parcours.graine + " · " + joueurs.size() + " joueurs connus"; }
    private String nom(UUID u) { String n = noms.get(u); return n != null ? n : serveur.services().nameToIdCache().get(u).map(net.minecraft.server.players.NameAndId::name).orElse("?"); }
    /** Classement : d'abord ceux qui sont arrivés (meilleur temps), puis la distance record. */
    private List<Map.Entry<UUID, int[]>> classement(int n) {
        List<Map.Entry<UUID, int[]>> l = new ArrayList<>(joueurs.entrySet());
        l.sort((a, b) -> { int[] x = a.getValue(), y = b.getValue();
            if ((x[6] > 0) != (y[6] > 0)) return x[6] > 0 ? -1 : 1;
            if (x[6] > 0) return Integer.compare(x[6], y[6]);
            return Integer.compare(y[1], x[1]); });
        return l.subList(0, Math.min(n, l.size()));
    }

    /* ================================================================== tableau latéral (3 lignes) */
    private static final String OBJ = "lobbik_lave";
    private void tableau() {
        Map.Entry<UUID, int[]> tete = null; for (Map.Entry<UUID, int[]> e : classement(1)) tete = e;
        for (ServerPlayer p : serveur.getPlayerList().getPlayers()) {
            int[] j = joueurs.getOrDefault(p.getUUID(), new int[7]);
            List<String> l = new ArrayList<>();
            l.add("§f" + j[0] + " §7/ " + parcours.longueur + " blocs");
            l.add("§7réap. §6" + j[2] + "/4 §7· chutes §c" + j[3]);
            if (tete != null && !tete.getKey().equals(p.getUUID())) { String n = nom(tete.getKey()); if (n.length() > 8) n = n.substring(0, 8); int[] v = tete.getValue();
                l.add("§6★ §f" + n + " §7" + (v[6] > 0 ? temps(v[6]) : v[1] + " b")); }
            List<String> avantL = lignesTableau.get(p.getUUID());
            if (avantL == null) {
                Objective o = new Objective(new Scoreboard(), OBJ, ObjectiveCriteria.DUMMY, Outils.tg("Mer de lave", 0xFF7A1A), ObjectiveCriteria.RenderType.INTEGER, false, null);
                p.connection.send(new ClientboundSetObjectivePacket(o, 0)); p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, o));
                avantL = List.of();
            }
            for (String s : avantL) if (!l.contains(s)) p.connection.send(new ClientboundResetScorePacket(s, OBJ));
            int sc = 15; for (String s : l) p.connection.send(new ClientboundSetScorePacket(s, OBJ, sc--, Optional.empty(), Optional.empty()));
            lignesTableau.put(p.getUUID(), l);
        }
    }

    /* ================================================================== vers le site (vue 3D, page Jouer) et les écrans du hub */
    private void positionsVersSite() {
        StringBuilder b = new StringBuilder("["); boolean prem = true;
        for (ServerPlayer q : serveur.getPlayerList().getPlayers()) { int[] j = joueurs.getOrDefault(q.getUUID(), new int[7]);
            if (!prem) b.append(','); prem = false;
            b.append("{\"uuid\":").append(Hub.j(q.getUUID().toString())).append(",\"nom\":").append(Hub.j(q.getPlainTextName())).append(",\"progres\":").append(j[0]).append(",\"record\":").append(j[1])
             .append(",\"reap\":").append(j[2]).append(",\"chutes\":").append(j[3]).append(",\"x\":").append(Math.round(q.getX() * 10) / 10.0).append(",\"y\":").append(Math.round(q.getY() * 10) / 10.0)
             .append(",\"z\":").append(Math.round(q.getZ() * 10) / 10.0).append('}'); }
        hub.envoyer("lave_positions", "{\"joueurs\":" + b.append(']') + ",\"longueur\":" + parcours.longueur + "}");
    }
    private void ecrireDirect() {
        StringBuilder b = new StringBuilder("{\"t\":").append(System.currentTimeMillis()).append(",\"longueur\":").append(parcours.longueur).append(",\"connus\":").append(joueurs.size()).append(",\"joueurs\":[");
        boolean prem = true;
        for (ServerPlayer q : serveur.getPlayerList().getPlayers()) { int[] j = joueurs.getOrDefault(q.getUUID(), new int[7]);
            if (!prem) b.append(','); prem = false; b.append("{\"nom\":").append(Hub.j(q.getPlainTextName())).append(",\"progres\":").append(j[0]).append(",\"reap\":").append(j[2]).append('}'); }
        b.append("],\"top\":["); prem = true;
        for (Map.Entry<UUID, int[]> e : classement(10)) { int[] v = e.getValue(); if (v[1] <= 0) continue; if (!prem) b.append(','); prem = false;
            b.append("{\"nom\":").append(Hub.j(nom(e.getKey()))).append(",\"record\":").append(v[1]).append(",\"temps\":").append(v[6]).append('}'); }
        String json = b.append("]}").toString();
        new Thread(() -> ecrireAtomique(dossier.resolve("direct.json"), json), "lave-direct").start();
    }

    /* ================================================================== persistance */
    private static void ecrireAtomique(Path f, String s) {
        try { Files.createDirectories(f.getParent()); Path t = f.resolveSibling(f.getFileName() + ".tmp"); Files.writeString(t, s, StandardCharsets.UTF_8);
            Files.move(t, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (IOException e) { Lobbik.LOG.warn("écriture de {} : {}", f.getFileName(), e.getMessage()); }
    }
    private synchronized void sauver() {
        StringBuilder j = new StringBuilder(), p = new StringBuilder(), n = new StringBuilder();
        for (Map.Entry<UUID, int[]> e : joueurs.entrySet()) { j.append(e.getKey()); for (int v : e.getValue()) j.append(' ').append(v); j.append('\n'); }
        for (Map.Entry<UUID, double[]> e : dernierePos.entrySet()) { p.append(e.getKey()); for (double v : e.getValue()) p.append(' ').append(v); p.append('\n'); }
        for (Map.Entry<UUID, String> e : noms.entrySet()) n.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        ecrireAtomique(dossier.resolve("joueurs.txt"), j.toString()); ecrireAtomique(dossier.resolve("positions.txt"), p.toString()); ecrireAtomique(dossier.resolve("noms.txt"), n.toString());
    }
    private void charger() {
        try {
            Path f = dossier.resolve("joueurs.txt");
            if (Files.exists(f)) for (String l : Files.readAllLines(f)) { String[] v = l.trim().split(" "); if (v.length < 2) continue;
                int[] t = new int[7]; for (int i = 1; i < v.length && i <= 7; i++) t[i - 1] = Integer.parseInt(v[i]); joueurs.put(UUID.fromString(v[0]), t); }
            f = dossier.resolve("positions.txt");
            if (Files.exists(f)) for (String l : Files.readAllLines(f)) { String[] v = l.trim().split(" "); if (v.length != 6) continue;
                dernierePos.put(UUID.fromString(v[0]), new double[]{Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]), Double.parseDouble(v[4]), Double.parseDouble(v[5])}); }
            f = dossier.resolve("noms.txt");
            if (Files.exists(f)) for (String l : Files.readAllLines(f)) { String[] v = l.trim().split(" ", 2); if (v.length == 2) noms.put(UUID.fromString(v[0]), v[1]); }
        } catch (Exception e) { Lobbik.LOG.warn("lecture des joueurs de la Mer de lave : {}", e.getMessage()); }
    }
}
