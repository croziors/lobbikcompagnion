package hk.krp.lobbik.tour;

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
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * La Tour du Lobbik en Fabric (27/09/2026) : port du plugin Paper KrpTour (même graine → même Tour, mêmes fichiers joueurs.json /
 * positions.json, mêmes événements vers le site). Pas d'événement « déplacement » en Fabric : tout se lit à chaque tick.
 */
public final class Tour {
    public static Tour I;
    private final MinecraftServer serveur; private final ServerLevel monde; private final Config cfg; private final Hub hub; private final boolean reseau;
    final Carte carte;
    private final Path dossier;
    private final Map<UUID, int[]> joueurs = new ConcurrentHashMap<>();     // uuid → {niveau courant, record, checkpoint (id), chutes, temps de jeu (s)}
    private final Map<UUID, String> noms = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> dernierePos = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> dernierSol = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> avant = new HashMap<>();            // position au tick précédent
    private final Map<UUID, Integer> alertes = new HashMap<>();
    private final Map<UUID, Long> arrivees = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportes = new HashMap<>();          // téléportations faites par nous : pas d'anti-triche juste après
    private final Map<UUID, Long> reprise = new HashMap<>();
    private final Map<UUID, Long> figes = new HashMap<>();
    private final Map<UUID, List<String>> lignesTableau = new HashMap<>();
    private final List<Object[]> plusTard = new ArrayList<>();          // {tick, Runnable}
    private long tic;
    private volatile long chatDepuis = 0;
    private static final int REPRISE_X = -25, REPRISE_Z = 3;
    private static final Identifier FIGE = Identifier.fromNamespaceAndPath("lobbik", "fige");

    public Tour(MinecraftServer s, Config cfg) {
        this.serveur = s; this.monde = s.overworld(); this.cfg = cfg;
        this.hub = Reseau.I.site;   // même liaison au site que le réseau (liste des membres, événements)
        this.reseau = cfg.b("tour.reseau", true);
        this.carte = new Carte(cfg.l("tour.graine", 20260919L));
        this.dossier = FabricLoader.getInstance().getGameDir().resolve("lobbik").resolve("tour");
        chargerJoueurs(); chargerPositions(); chargerNoms();
        I = this;
    }

    public void demarrer() {
        for (String c : new String[]{"difficulty hard", "gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false", "gamerule spawn_monsters false",
                "gamerule fall_damage false", "gamerule show_advancement_messages false", "gamerule immediate_respawn true", "time set 18000", "weather clear"}) commande(c);
        Carte.Cube depart = carte.cubes.get(0);
        commande("setworldspawn " + depart.x + " " + (depart.y + 1) + " " + depart.z);
        Path marque = dossier.resolve("tour-" + carte.graine + ".ok");
        if (!Files.exists(marque)) {
            long t0 = System.currentTimeMillis();
            Lobbik.LOG.info("Construction de la Tour : {} cubes, {} points de réapparition…", carte.cubes.size(), carte.checkpoints.size());
            carte.construire(monde);
            try { Files.createDirectories(dossier); Files.writeString(marque, "1"); Files.writeString(dossier.resolve("cubes.json"), carte.json(), StandardCharsets.UTF_8); } catch (IOException ignored) { }
            Lobbik.LOG.info("Tour construite en {} s.", (System.currentTimeMillis() - t0) / 1000);
        }
        if (reseau) poserReprise();
        new Thread(hub::relireLies, "tour-membres").start();
        Lobbik.LOG.info("La Tour prête — {} cubes, {} sur le chemin, hauteur {}", carte.cubes.size(), carte.niveauMax + 1, carte.sommet().y - Carte.Y_DEPART);
    }
    public void arreter() { sauverJoueurs(); sauverPositions(); sauverNoms(); }
    private void commande(String c) { serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack().withSuppressedOutput(), c); }
    private void plusTard(int ticks, Runnable r) { plusTard.add(new Object[]{tic + ticks, r}); }
    private int[] j(UUID u) { return joueurs.computeIfAbsent(u, k -> new int[]{0, 0, 0, 0, 0}); }

    /* ================================================================== chaque tick */
    public void tic() {
        tic++;
        if (!plusTard.isEmpty()) { List<Object[]> l = new ArrayList<>(plusTard); plusTard.clear(); for (Object[] o : l) { if ((long) o[0] <= tic) ((Runnable) o[1]).run(); else plusTard.add(o); } }
        for (ServerPlayer p : serveur.getPlayerList().getPlayers()) deplacement(p);
        if (tic % 20 == 0) cycleEphemeres();
        if (tic % 100 == 40) tableau();
        if (tic % 100 == 0) for (ServerPlayer q : serveur.getPlayerList().getPlayers()) j(q.getUUID())[4] += 5;
        if (tic % 60 == 0 && !serveur.getPlayerList().getPlayers().isEmpty()) positionsVersSite();
        if (tic % 60 == 30) ecrireDirect();
        if (tic % 1200 == 0) { new Thread(hub::relireLies, "tour-membres").start(); new Thread(this::sauverJoueurs, "tour-sauvegarde").start(); }
        if (tic % 60 == 15 && cfg.b("lire_chat_site", false)) lireChatSite();
    }

    /* ---------------- cubes éphémères : 7 s visibles (la dernière en rouge), 3 s disparus, décalés selon l'id ---------------- */
    private int cycle = 0;
    private void cycleEphemeres() {
        cycle++;
        for (Carte.Cube c : carte.cubes) {
            if (!c.ephemere) continue;
            int phase = (cycle + c.id * 3) % 10;
            if (phase == 0) carte.poser(monde, c, true);
            else if (phase == 6) carte.avertir(monde, c);
            else if (phase == 7) carte.poser(monde, c, false);
        }
    }

    /* ================================================================== arrivée, départ */
    public void rejoint(ServerPlayer p) {
        UUID u = p.getUUID();
        if (cfg.b("membres_seulement", true) && hub.listeChargee() && !hub.estLie(u)) {
            p.connection.disconnect(Component.literal(Textes.t(null, "kick_membres") + Reseau.I.codePour(u) + Textes.t(null, "kick_suite")));
            return;
        }
        noms.put(u, p.getPlainTextName());
        p.setGameMode(GameType.ADVENTURE);
        p.getFoodData().setFoodLevel(20); p.getFoodData().setSaturation(20f); p.setHealth(p.getMaxHealth());
        p.addEffect(new MobEffectInstance(MobEffects.SATURATION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        int[] j = j(u);
        arrivees.put(u, System.currentTimeMillis()); avant.remove(u); lignesTableau.remove(u);
        if (reseau && Billets.PAR_LE_PONT.containsKey(u)) {
            // arrivé à pied par le pont : il reste où il est ; s'il avait quitté en pleine ascension, la porte le ramène à cet endroit précis
            double[] ici = dernierePos.remove(u);
            if (ici != null) {
                plusTard(2, () -> { if (!p.hasDisconnected()) { teleporter(p, ici[0], ici[1], ici[2], (float) ici[3], (float) ici[4]); figer(p, 3); p.sendSystemMessage(Component.literal(Textes.t(p, "retour_pos"))); } });
                new Thread(this::sauverPositions, "tour-positions").start();
            } else {
                j[0] = 0;
                p.sendSystemMessage(Component.literal(Textes.t(p, "aide")));
                if (j[2] > 0) p.sendSystemMessage(Component.literal(Textes.t(p, "attend")));
            }
        } else {
            double[] d = dernierePos.get(u);
            // téléportation 2 ticks après l'arrivée : faite tout de suite, elle est parfois écrasée par la position de connexion
            if (d != null) { plusTard(2, () -> { if (!p.hasDisconnected()) teleporter(p, d[0], d[1], d[2], (float) d[3], (float) d[4]); });
                p.sendSystemMessage(Component.literal(Textes.t(p, "aide"))); p.sendSystemMessage(Component.literal(Textes.t(p, "retour_pos"))); }
            else { plusTard(2, () -> { if (!p.hasDisconnected()) versPoint(p, j); }); p.sendSystemMessage(Component.literal(Textes.t(p, "aide")));
                if (j[1] > 0) p.sendSystemMessage(Component.literal(Textes.t(p, "record", j[1], nomPoint(p, j)))); }
        }
        hub.envoyer("connexion", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"niveau\":" + j[0] + "}");
    }

    public void quitte(ServerPlayer p) {
        UUID u = p.getUUID(); alertes.remove(u); avant.remove(u); teleportes.remove(u); reprise.remove(u); lignesTableau.remove(u);
        if (figes.remove(u) != null) defiger(p);
        // l'endroit exact où il quitte (posé sur un bloc de la Tour), pour l'y remettre à son retour
        double[] sol = dernierSol.remove(u);
        if (p.onGround() && p.getY() > Carte.Y_DEPART - 2) sol = new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
        if (sol != null && reseau && Math.hypot(sol[0], sol[2]) > Carte.RAYON_MUR + 32) sol = null;   // parti par le pont : pas dans le décor du hub
        if (sol != null) dernierePos.put(u, sol); else dernierePos.remove(u);
        Long arrivee = arrivees.remove(u); int[] j = joueurs.getOrDefault(u, new int[]{0, 0, 0, 0, 0});
        hub.envoyer("deconnexion", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"niveau\":" + j[0] + ",\"record\":" + j[1] + ",\"chutes\":" + j[3]
            + ",\"temps\":" + j[4] + ",\"session\":" + (arrivee == null ? 0 : (System.currentTimeMillis() - arrivee) / 1000) + "}");
        new Thread(() -> { sauverPositions(); sauverNoms(); }, "tour-positions").start();
    }

    /** Après une mort (rare : aucun dégât) : retour au point de réapparition. */
    public void reapparu(ServerPlayer p) {
        p.addEffect(new MobEffectInstance(MobEffects.SATURATION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
        versPoint(p, j(p.getUUID()));
    }

    /* ================================================================== figé 3 s au retour à sa position (seulement en venant du hub par la porte) */
    private void figer(ServerPlayer p, int secondes) {
        figes.put(p.getUUID(), System.currentTimeMillis() + secondes * 1000L);
        for (var a : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) {
            AttributeInstance ai = p.getAttribute(a);
            if (ai != null) { ai.removeModifier(FIGE); ai.addTransientModifier(new AttributeModifier(FIGE, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)); }
        }
        Outils.titre(p, Component.empty(), Outils.t(Textes.t(p, "retour_titre"), Outils.GRIS), 0, secondes * 20, 8);
    }
    private void defiger(ServerPlayer p) {
        for (var a : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) { AttributeInstance ai = p.getAttribute(a); if (ai != null) ai.removeModifier(FIGE); }
    }

    /* ================================================================== dalle dorée « reprendre mon ascension » au bout du pont */
    private boolean surReprise(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return bx >= REPRISE_X && bx <= REPRISE_X + 1 && bz >= REPRISE_Z && bz <= REPRISE_Z + 1 && y < Carte.Y_DEPART + 1;
    }
    private void poserReprise() {
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++)
            monde.setBlock(new net.minecraft.core.BlockPos(REPRISE_X + dx, Carte.Y_DEPART - 1, REPRISE_Z + dz), Carte.s("gold_block"), 18);
        Affichages.nettoyer(monde, "krptour_reprise");
        Affichages.texte(monde, "krptour_reprise", REPRISE_X + 1, Carte.Y_DEPART + 1.6, REPRISE_Z + 1, 1f, Display.BillboardConstraints.CENTER, 0f,
            Outils.t(Textes.t(null, "reprise"), Outils.OR).append("\n").append(Outils.t(Textes.t(null, "reprise2"), Outils.GRIS)));
    }
    private String nomPoint(ServerPlayer p, int[] j) { return j[2] == 0 ? Textes.t(p, "depart") : Textes.t(p, "num", carte.checkpoints.indexOf(j[2]) + 1); }
    private Carte.Cube pointDeRetour(int[] j) {
        if (j[2] > 0) for (Carte.Cube k : carte.cubes) if (k.id == j[2]) return k;
        return carte.cubes.get(0);
    }
    private void versPoint(ServerPlayer p, int[] j) { double[] l = carte.lieu(pointDeRetour(j)); teleporter(p, l[0], l[1], l[2], p.getYRot(), p.getXRot()); }
    private void teleporter(ServerPlayer p, double x, double y, double z, float yaw, float pitch) {
        Outils.teleporter(p, x, y, z, yaw, pitch); p.fallDistance = 0;
        teleportes.put(p.getUUID(), System.currentTimeMillis()); avant.put(p.getUUID(), new double[]{x, y, z});
    }

    /* ================================================================== progression, chute, anti-triche (à chaque tick) */
    private void deplacement(ServerPlayer p) {
        if (p.isSpectator() || p.isCreative()) { avant.remove(p.getUUID()); return; }
        UUID u = p.getUUID();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        double[] a = avant.put(u, new double[]{x, y, z});
        Long fige = figes.get(u);
        if (fige != null) {
            if (fige < System.currentTimeMillis()) { figes.remove(u); defiger(p); }
            else if (a != null && (Math.abs(a[0] - x) > 0.05 || Math.abs(a[2] - z) > 0.05 || y - a[1] > 0.05)) { Outils.teleporter(p, a[0], a[1], a[2], p.getYRot(), p.getXRot()); avant.put(u, a); return; }
        }
        if (a == null || (a[0] == x && a[1] == y && a[2] == z)) return;
        int[] j = j(u);
        boolean sol = p.onGround();
        if (sol && y > Carte.Y_DEPART - 2) dernierSol.put(u, new double[]{x, y, z, p.getYRot(), p.getXRot()});
        boolean presDeLaTour = Math.hypot(x, z) < Carte.RAYON_MUR + 32;
        if (reseau && !presDeLaTour) return;   // sur le pont ou dans le décor du hub : c'est le réseau qui s'en occupe
        if (reseau && sol && surReprise(x, y, z)) {
            Long t = reprise.get(u);
            if (t == null || System.currentTimeMillis() - t > 4000) {
                reprise.put(u, System.currentTimeMillis()); j[0] = j[2]; versPoint(p, j);
                p.sendSystemMessage(Component.literal(Textes.t(p, "cp_retour", nomPoint(p, j)))); Outils.son(p, SoundEvents.BEACON_POWER_SELECT, 0.7f, 1.4f);
            }
            return;
        }
        if (y < Carte.Y_DEPART - 12) {
            j[3]++; j[0] = j[2];
            versPoint(p, j); Outils.action(p, Outils.t(Textes.t(p, "chute", j[3]), Outils.ROUGE));
            hub.envoyer("chute", "{\"uuid\":" + Hub.j(u.toString()) + ",\"niveau\":" + j[0] + "}");
            return;
        }
        // anti-triche simple : vitesses horizontale et verticale impossibles sans vol ni vitesse (grâce de 3 s à l'arrivée et après nos téléportations)
        double dh = Math.hypot(x - a[0], z - a[2]), dv = y - a[1];
        Long arrive = arrivees.get(u), tp = teleportes.get(u); long n = System.currentTimeMillis();
        if ((dh > 1.3 || dv > 1.1) && (arrive == null || n - arrive > 3000) && (tp == null || n - tp > 1500)) {
            int k = alertes.merge(u, 1, Integer::sum);
            Outils.teleporter(p, a[0], a[1], a[2], p.getYRot(), p.getXRot()); avant.put(u, a);
            if (k % 8 == 0) p.sendSystemMessage(Component.literal(Textes.t(p, "triche", k)));
            if (k >= 40) { hub.envoyer("triche", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"alertes\":" + k + "}"); p.connection.disconnect(Outils.t(Textes.t(p, "kick_triche"), Outils.ROUGE)); }
            return;
        }
        if (p.getAbilities().flying && !p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) { p.getAbilities().flying = false; p.getAbilities().mayfly = false; p.onUpdateAbilities(); }
        // niveau : le cube sous les pieds
        if (!sol && y - Math.floor(y) > 0.05) return;
        Carte.Cube c = carte.cubeSous(x, y, z);
        if (c == null) {
            // retombé sur la base (ou le mur) alors qu'on était en hauteur : chute, retour au point de réapparition
            if (j[0] > 0 && y <= Carte.Y_DEPART + 1 && sol) {
                j[3]++; j[0] = j[2];
                Outils.action(p, Outils.t(Textes.t(p, "chute", j[3]), Outils.ROUGE));
                if (j[2] > 0) versPoint(p, j);
                hub.envoyer("chute", "{\"uuid\":" + Hub.j(u.toString()) + ",\"niveau\":" + j[0] + "}");
            }
            return;
        }
        if (c.niveau != j[0] || c.checkpoint) {
            boolean progres = c.niveau > j[0];
            j[0] = c.niveau;
            if (c.niveau > j[1]) { j[1] = c.niveau; hub.envoyer("niveau", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"niveau\":" + c.niveau + ",\"chutes\":" + j[3] + "}"); }
            if (c.checkpoint && j[2] != c.id) {
                j[2] = c.id; p.sendSystemMessage(Component.literal(Textes.t(p, "checkpoint", carte.checkpoints.indexOf(c.id) + 1)));
                Outils.son(p, SoundEvents.BEACON_ACTIVATE, 0.6f, 1.2f);
            }
            Outils.action(p, Outils.t(Textes.t(p, "niveau", c.niveau, carte.niveauMax) + (c.niveau == j[1] && progres ? Textes.t(p, "record_tag") : ""), progres ? Outils.VERT : Outils.GRIS));
            if (c.niveau == carte.niveauMax) {
                for (ServerPlayer q : serveur.getPlayerList().getPlayers()) q.sendSystemMessage(Outils.t(Textes.t(q, "sommet", p.getPlainTextName()), Outils.OR));
                hub.envoyer("sommet", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + "}");
            }
        }
    }

    /* ================================================================== commandes publiques */
    public int cmdCp(ServerPlayer p) { int[] j = j(p.getUUID()); j[0] = j[2]; versPoint(p, j); p.sendSystemMessage(Component.literal(Textes.t(p, "cp_retour", nomPoint(p, j)))); return 1; }
    public int cmdNiveau(ServerPlayer p) { int[] j = j(p.getUUID()); p.sendSystemMessage(Component.literal(Textes.t(p, "mon_niveau", j[0], j[1], carte.niveauMax, j[3]))); return 1; }
    public int cmdTop(ServerPlayer p) {
        p.sendSystemMessage(Component.literal(Textes.t(p, "top"))); int k = 1;
        for (Map.Entry<UUID, int[]> e : classement(10)) p.sendSystemMessage(Component.literal(Textes.t(p, "top_l", k++, nom(e.getKey()), e.getValue()[1])));
        return 1;
    }
    public String infos() { return "Tour : " + carte.cubes.size() + " cubes · " + joueurs.size() + " grimpeurs connus · graine " + carte.graine; }
    public void regenerer() { carte.construire(monde); if (reseau) poserReprise(); }
    private String nom(UUID u) {
        String n = noms.get(u); if (n != null) return n;
        return serveur.services().nameToIdCache().get(u).map(NameAndId::name).orElse("?");
    }
    private List<Map.Entry<UUID, int[]>> classement(int n) {
        List<Map.Entry<UUID, int[]>> l = new ArrayList<>(joueurs.entrySet()); l.sort((a, b) -> b.getValue()[1] - a.getValue()[1]); return l.subList(0, Math.min(n, l.size()));
    }

    /* ================================================================== tableau latéral (3 lignes : moi, mon rang, le meneur), propre à chaque joueur */
    private static final Objective OBJ = new Objective(new Scoreboard(), "lobbik_tour", ObjectiveCriteria.DUMMY, Component.literal("La Tour"), ObjectiveCriteria.RenderType.INTEGER, false, null);
    private void tableau() {
        Map.Entry<UUID, int[]> tete = null; for (Map.Entry<UUID, int[]> e : classement(1)) tete = e;
        for (ServerPlayer p : serveur.getPlayerList().getPlayers()) {
            int[] j = joueurs.getOrDefault(p.getUUID(), new int[]{0, 0, 0, 0, 0});
            List<String> l = new ArrayList<>();
            l.add(Textes.t(p, "sb_moi", j[0], j[1]));
            int rang = 1; for (int[] v : joueurs.values()) if (v[1] > j[1]) rang++;
            l.add(Textes.t(p, "sb_rang", rang, joueurs.size(), j[4] / 3600, (j[4] % 3600) / 60));
            if (tete != null && !tete.getKey().equals(p.getUUID())) { String n = nom(tete.getKey()); if (n.length() > 8) n = n.substring(0, 8); l.add("§6★ §f" + n + " §7" + tete.getValue()[1]); }
            List<String> avantL = lignesTableau.get(p.getUUID());
            if (avantL == null) {
                Objective o = new Objective(new Scoreboard(), "lobbik_tour", ObjectiveCriteria.DUMMY, Outils.t(Textes.t(p, "sb_titre"), Outils.OR), ObjectiveCriteria.RenderType.INTEGER, false, null);
                p.connection.send(new ClientboundSetObjectivePacket(o, 0));
                p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, o));
                avantL = List.of();
            }
            for (String s : avantL) if (!l.contains(s)) p.connection.send(new ClientboundResetScorePacket(s, OBJ.getName()));
            int score = 15;
            for (String s : l) p.connection.send(new ClientboundSetScorePacket(s, OBJ.getName(), score--, Optional.empty(), Optional.empty()));
            lignesTableau.put(p.getUUID(), l);
        }
    }

    /* ================================================================== vers le site et les écrans du hub */
    private void positionsVersSite() {
        StringBuilder b = new StringBuilder("["); boolean first = true;
        for (ServerPlayer q : serveur.getPlayerList().getPlayers()) {
            int[] j = joueurs.getOrDefault(q.getUUID(), new int[]{0, 0, 0, 0, 0});
            if (!first) b.append(','); first = false;
            b.append("{\"uuid\":").append(Hub.j(q.getUUID().toString())).append(",\"nom\":").append(Hub.j(q.getPlainTextName())).append(",\"niveau\":").append(j[0]).append(",\"record\":").append(j[1])
             .append(",\"temps\":").append(j[4]).append(",\"session\":").append((System.currentTimeMillis() - arrivees.getOrDefault(q.getUUID(), System.currentTimeMillis())) / 1000)
             .append(",\"x\":").append((int) q.getX()).append(",\"y\":").append((int) q.getY()).append(",\"z\":").append((int) q.getZ()).append('}');
        }
        StringBuilder cp = new StringBuilder("[");
        for (int k = 0; k < carte.checkpoints.size(); k++) for (Carte.Cube c : carte.cubes) if (c.id == carte.checkpoints.get(k)) { if (k > 0) cp.append(','); cp.append(c.y); break; }
        hub.envoyer("positions", "{\"joueurs\":" + b.append(']') + ",\"y_depart\":" + Carte.Y_DEPART + ",\"y_sommet\":" + carte.sommet().y + ",\"checkpoints\":" + cp.append(']') + ",\"cubes\":" + carte.niveauMax + "}");
    }
    /** État en direct pour les écrans du hub (lobbik/tour/direct.json) : grimpeurs en ligne, top 10, points de réapparition. */
    private void ecrireDirect() {
        StringBuilder b = new StringBuilder("{\"t\":").append(System.currentTimeMillis()).append(",\"sommet\":").append(carte.niveauMax)
            .append(",\"y_depart\":").append(Carte.Y_DEPART).append(",\"y_sommet\":").append(carte.sommet().y).append(",\"connus\":").append(joueurs.size()).append(",\"checkpoints\":[");
        for (int k = 0; k < carte.checkpoints.size(); k++) for (Carte.Cube c : carte.cubes) if (c.id == carte.checkpoints.get(k)) { if (k > 0) b.append(','); b.append(c.niveau); break; }
        b.append("],\"joueurs\":["); boolean prem = true;
        for (ServerPlayer q : serveur.getPlayerList().getPlayers()) { int[] j = joueurs.getOrDefault(q.getUUID(), new int[]{0, 0, 0, 0, 0});
            if (!prem) b.append(','); prem = false;
            b.append("{\"nom\":").append(Hub.j(q.getPlainTextName())).append(",\"niveau\":").append(j[0]).append(",\"record\":").append(j[1]).append(",\"y\":").append((int) q.getY()).append(",\"chutes\":").append(j[3]).append('}'); }
        b.append("],\"top\":["); prem = true;
        for (Map.Entry<UUID, int[]> e : classement(10)) { if (e.getValue()[1] <= 0) continue;
            if (!prem) b.append(','); prem = false;
            b.append("{\"nom\":").append(Hub.j(nom(e.getKey()))).append(",\"record\":").append(e.getValue()[1]).append('}'); }
        String json = b.append("]}").toString();
        new Thread(() -> ecrireAtomique(dossier.resolve("direct.json"), json), "tour-direct").start();
    }
    /** Salon #minecraft du site → joueurs de la Tour (jamais de commandes). */
    private void lireChatSite() {
        if (serveur.getPlayerList().getPlayers().isEmpty() && chatDepuis > 0) return;
        new Thread(() -> {
            String corps = hub.lireChat(chatDepuis); if (corps == null) return;
            java.util.regex.Matcher md = java.util.regex.Pattern.compile("\"dernier\":(\\d+)").matcher(corps); if (md.find()) chatDepuis = Long.parseLong(md.group(1));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\"id\":(\\d+),\"nick\":\"((?:[^\"\\\\]|\\\\.)*)\",\"texte\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}").matcher(corps);
            List<Component> l = new ArrayList<>();
            while (m.find()) l.add(Outils.t("[Hub] ", Outils.AQUA).append(Outils.t(dej(m.group(2)), Outils.BLANC)).append(Outils.t(": " + dej(m.group(3)), Outils.GRIS)));
            if (!l.isEmpty()) serveur.execute(() -> { for (ServerPlayer q : serveur.getPlayerList().getPlayers()) for (Component c : l) q.sendSystemMessage(c); });
        }, "tour-chat").start();
    }
    private static String dej(String s) { return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ").replace("\\\\", "\\"); }

    /* ================================================================== persistance (mêmes formats que le plugin Paper) */
    private static void ecrireAtomique(Path f, String s) {
        try { Files.createDirectories(f.getParent()); Path tmp = f.resolveSibling(f.getFileName() + ".tmp"); Files.writeString(tmp, s, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (IOException e) { Lobbik.LOG.warn("écriture de {} : {}", f.getFileName(), e.getMessage()); }
    }
    private synchronized void sauverJoueurs() {
        StringBuilder sb = new StringBuilder("{"); boolean first = true;
        for (Map.Entry<UUID, int[]> e : joueurs.entrySet()) { int[] v = e.getValue(); if (!first) sb.append(','); first = false;
            sb.append('"').append(e.getKey()).append("\":[").append(v[0]).append(',').append(v[1]).append(',').append(v[2]).append(',').append(v[3]).append(',').append(v[4]).append(']'); }
        ecrireAtomique(dossier.resolve("joueurs.json"), sb.append('}').toString());
    }
    /** positions.json : une ligne par joueur « uuid x y z yaw pitch ». */
    private synchronized void sauverPositions() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, double[]> e : dernierePos.entrySet()) { double[] v = e.getValue(); sb.append(e.getKey()).append(' ').append(v[0]).append(' ').append(v[1]).append(' ').append(v[2]).append(' ').append(v[3]).append(' ').append(v[4]).append('\n'); }
        ecrireAtomique(dossier.resolve("positions.json"), sb.toString());
    }
    private synchronized void sauverNoms() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, String> e : noms.entrySet()) sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        ecrireAtomique(dossier.resolve("noms.txt"), sb.toString());
    }
    private void chargerPositions() {
        try {
            Path f = dossier.resolve("positions.json"); if (!Files.exists(f)) return;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] v = l.trim().split(" "); if (v.length != 6) continue;
                try { dernierePos.put(UUID.fromString(v[0]), new double[]{Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]), Double.parseDouble(v[4]), Double.parseDouble(v[5])}); } catch (Exception ignored) { }
            }
        } catch (Exception e) { Lobbik.LOG.warn("lecture des positions : {}", e.getMessage()); }
    }
    private void chargerNoms() {
        try {
            Path f = dossier.resolve("noms.txt"); if (!Files.exists(f)) return;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) { String[] v = l.trim().split(" ", 2); if (v.length == 2) try { noms.put(UUID.fromString(v[0]), v[1]); } catch (Exception ignored) { } }
        } catch (Exception e) { Lobbik.LOG.warn("lecture des noms : {}", e.getMessage()); }
    }
    private void chargerJoueurs() {
        try {
            Path f = dossier.resolve("joueurs.json"); if (!Files.exists(f)) return;
            String s = Files.readString(f, StandardCharsets.UTF_8).trim(); if (s.length() < 3) return;
            for (String part : s.substring(1, s.length() - 1).split("\\],")) {
                String[] kv = part.split("\":\\["); if (kv.length != 2) continue;
                String[] v = kv[1].replace("]", "").split(",");
                joueurs.put(UUID.fromString(kv[0].replace("\"", "").trim()), new int[]{Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()), Integer.parseInt(v[2].trim()), Integer.parseInt(v[3].trim()), v.length > 4 ? Integer.parseInt(v[4].trim()) : 0});
            }
        } catch (Exception e) { Lobbik.LOG.warn("lecture des joueurs : {}", e.getMessage()); }
    }
}
