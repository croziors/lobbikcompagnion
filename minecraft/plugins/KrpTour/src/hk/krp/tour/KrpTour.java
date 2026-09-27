package hk.krp.tour;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;

public final class KrpTour extends JavaPlugin implements Listener {
    private Carte carte; private World monde; private Hub hub;
    private final Map<UUID, int[]> joueurs = new ConcurrentHashMap<>();     // uuid → {niveau courant, record, checkpoint (id), chutes}
    private final Map<UUID, String> codes = new HashMap<>();
    private final Map<UUID, double[]> dernierePos = new ConcurrentHashMap<>();
    // dernier endroit où le joueur a eu le pied sur un bloc (22/09/2026, Kripy : « quand je déco-reco je ne reviens pas au même endroit ») :
    // au moment de quitter, isOnGround() est souvent faux → on se sert de celui-ci au lieu d'effacer la position
    private final Map<UUID, double[]> dernierSol = new ConcurrentHashMap<>();   // uuid → {x, y, z, yaw, pitch} : où le joueur a quitté (sur un bloc) ; à la reconnexion on le remet là, pas au checkpoint
    private File fichierPositions;
    private final Map<UUID, Integer> alertes = new HashMap<>();
    private final Map<UUID, Long> arrivees = new HashMap<>();      // heure d'entrée sur le serveur (session en cours)
    private File fichierJoueurs;

    @Override public ChunkGenerator getDefaultWorldGenerator(String nom, String id) { return new Vide(); }

    @Override public void onEnable() {
        saveDefaultConfig();
        hub = new Hub(getConfig().getString("hub", "https://lobbik.com"), getConfig().getString("cle", ""), getConfig().getBoolean("envoi_site", true), getLogger());
        reseau = getConfig().getBoolean("reseau.actif", false);
        carte = new Carte(getConfig().getLong("graine", 20260919L));
        fichierJoueurs = new File(getDataFolder(), "joueurs.json"); chargerJoueurs();
        fichierPositions = new File(getDataFolder(), "positions.json"); chargerPositions();
        Bukkit.getPluginManager().registerEvents(this, this);
        // load: STARTUP → les mondes n'existent pas encore ici : on finit au premier tic
        Bukkit.getScheduler().runTask(this, this::demarrer);
    }
    private void demarrer() {
        monde = Bukkit.getWorlds().get(0);
        monde.setDifficulty(Difficulty.HARD);
        monde.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); monde.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        monde.setGameRule(GameRule.DO_MOB_SPAWNING, false); monde.setGameRule(GameRule.FALL_DAMAGE, false);
        monde.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false); monde.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        monde.setTime(18000); monde.setStorm(false);
        Carte.Cube depart = carte.cubes.get(0);
        monde.setSpawnLocation(carte.lieu(monde, depart));
        File marque = new File(getDataFolder(), "tour-" + carte.graine + ".ok");
        if (!marque.exists()) {
            getLogger().info("Construction de la Tour : " + carte.cubes.size() + " cubes, " + carte.checkpoints.size() + " points de réapparition…");
            carte.construire(monde);
            try { marque.getParentFile().mkdirs(); marque.createNewFile(); Files.writeString(new File(getDataFolder(), "cubes.json").toPath(), carte.json(), StandardCharsets.UTF_8); } catch (IOException ignored) {}
            getLogger().info("Tour construite.");
        }
        if (reseau) poserReprise();
        // cubes éphémères : cycle 7 s visibles (dernière seconde en rouge) / 3 s disparus, décalés selon l'id pour ne pas clignoter ensemble
        Bukkit.getScheduler().runTaskTimer(this, this::cycleEphemeres, 20, 20);
        // liste des comptes liés depuis le Hub, en tâche de fond
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> hub.relireLies(), 0, 20 * 60);
        Bukkit.getScheduler().runTaskTimer(this, this::tableau, 40, 100);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player q : Bukkit.getOnlinePlayers()) { int[] j = joueurs.computeIfAbsent(q.getUniqueId(), u -> new int[]{0, 0, 0, 0, 0}); if (j.length > 4) j[4] += 5; } }, 100, 100);   // temps de jeu, par tranches de 5 s
        // positions en direct vers le Hub (qui est sur quel cube), toutes les 10 s s'il y a du monde
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Player q : Bukkit.getOnlinePlayers()) { int[] j = joueurs.getOrDefault(q.getUniqueId(), new int[]{0, 0, 0, 0, 0});
                if (!first) b.append(','); first = false;
                org.bukkit.Location l = q.getLocation();
                b.append("{\"uuid\":").append(Hub.j(q.getUniqueId().toString())).append(",\"nom\":").append(Hub.j(q.getName())).append(",\"niveau\":").append(j[0]).append(",\"record\":").append(j[1])
                 .append(",\"temps\":").append(j.length > 4 ? j[4] : 0).append(",\"session\":").append((System.currentTimeMillis() - arrivees.getOrDefault(q.getUniqueId(), System.currentTimeMillis())) / 1000).append(",\"x\":").append((int) l.getX()).append(",\"y\":").append((int) l.getY()).append(",\"z\":").append((int) l.getZ()).append('}'); }
            StringBuilder cp = new StringBuilder("[");
            for (int k = 0; k < carte.checkpoints.size(); k++) { for (Carte.Cube c : carte.cubes) if (c.id == carte.checkpoints.get(k)) { if (k > 0) cp.append(','); cp.append(c.y); break; } }
            hub.envoyer("positions", "{\"joueurs\":" + b.append(']') + ",\"y_depart\":" + Carte.Y_DEPART + ",\"y_sommet\":" + carte.sommet().y + ",\"checkpoints\":" + cp.append(']') + ",\"cubes\":" + carte.niveauMax + "}");
        }, 100, 60);   // toutes les 3 s : on voit chacun avancer
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::sauverJoueurs, 20 * 60, 20 * 60);
        // écrans du hub (réseau Lobbik, 27/09/2026) : l'état en direct dans plugins/KrpTour/direct.json, toutes les 3 s
        Bukkit.getScheduler().runTaskTimer(this, this::ecrireDirect, 60, 60);
        // pont de chat Hub → jeu : le salon #minecraft du site s'affiche ici (jamais de commandes, elles ne passent pas par le chat)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty() && chatDepuis > 0) return;
            String corps = hub.lireChat(chatDepuis); if (corps == null) return;
            java.util.regex.Matcher md = java.util.regex.Pattern.compile("\"dernier\":(\\d+)").matcher(corps); if (md.find()) chatDepuis = Long.parseLong(md.group(1));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\"id\":(\\d+),\"nick\":\"((?:[^\"\\\\]|\\\\.)*)\",\"texte\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}").matcher(corps);
            while (m.find()) {
                String nick = dej(m.group(2)), texte = dej(m.group(3));
                Component c = Component.text("[Hub] ", NamedTextColor.AQUA).append(Component.text(nick, NamedTextColor.WHITE)).append(Component.text(": " + texte, NamedTextColor.GRAY));
                for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(c);
            }
        }, 60, 60);
        getLogger().info("KrpTour prêt — " + carte.cubes.size() + " cubes, " + (carte.niveauMax + 1) + " sur le chemin, hauteur " + (carte.sommet().y - Carte.Y_DEPART));
    }
    @Override public void onDisable() { sauverJoueurs(); sauverPositions(); }
    /** État en direct pour les écrans du hub : grimpeurs en ligne (niveau, record, hauteur), top 10, points de réapparition. */
    private void ecrireDirect() {
        StringBuilder b = new StringBuilder("{\"t\":").append(System.currentTimeMillis()).append(",\"sommet\":").append(carte.niveauMax)
            .append(",\"y_depart\":").append(Carte.Y_DEPART).append(",\"y_sommet\":").append(carte.sommet().y).append(",\"connus\":").append(joueurs.size()).append(",\"checkpoints\":[");
        for (int k = 0; k < carte.checkpoints.size(); k++) { for (Carte.Cube c : carte.cubes) if (c.id == carte.checkpoints.get(k)) { if (k > 0) b.append(','); b.append(c.niveau); break; } }
        b.append("],\"joueurs\":[");
        boolean prem = true;
        for (Player q : Bukkit.getOnlinePlayers()) { int[] j = joueurs.getOrDefault(q.getUniqueId(), new int[]{0, 0, 0, 0, 0});
            if (!prem) b.append(','); prem = false;
            b.append("{\"nom\":").append(Hub.j(q.getName())).append(",\"niveau\":").append(j[0]).append(",\"record\":").append(j[1]).append(",\"y\":").append((int) q.getLocation().getY()).append(",\"chutes\":").append(j[3]).append('}'); }
        b.append("],\"top\":[");
        prem = true;
        for (Map.Entry<UUID, int[]> e : classement(10)) { if (e.getValue()[1] <= 0) continue; OfflinePlayer o = Bukkit.getOfflinePlayer(e.getKey());
            if (!prem) b.append(','); prem = false;
            b.append("{\"nom\":").append(Hub.j(o.getName() == null ? "?" : o.getName())).append(",\"record\":").append(e.getValue()[1]).append('}'); }
        b.append("]}");
        String json = b.toString();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { File tmp = new File(getDataFolder(), "direct.json.tmp"); Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8); Files.move(tmp.toPath(), new File(getDataFolder(), "direct.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (IOException ignored) { }
        });
    }
    private volatile long chatDepuis = 0;
    private static String dej(String s) { return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ").replace("\\\\", "\\"); }

    /* ---------------- pont de chat jeu → Hub : les messages, jamais les commandes (elles ne déclenchent pas cet événement) ---------------- */
    @EventHandler public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        String texte = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (texte.isEmpty() || texte.startsWith("/")) return;
        hub.envoyer("chat", "{\"uuid\":" + Hub.j(e.getPlayer().getUniqueId().toString()) + ",\"nom\":" + Hub.j(e.getPlayer().getName()) + ",\"message\":" + Hub.j(texte) + "}");
    }

    /* ---------------- cubes éphémères ---------------- */
    private int tic = 0;
    private void cycleEphemeres() {
        tic++;
        for (Carte.Cube c : carte.cubes) {
            if (!c.ephemere) continue;
            int phase = (tic + c.id * 3) % 10;      // 0..6 visible, 6 = rouge, 7..9 disparu
            if (phase == 0) carte.poser(monde, c, true);
            else if (phase == 6) carte.avertir(monde, c);
            else if (phase == 7) carte.poser(monde, c, false);
        }
    }

    /* ---------------- accès : membres du Hub seulement ---------------- */
    @EventHandler public void onLogin(AsyncPlayerPreLoginEvent e) {
        if (!hub.listeChargee()) hub.relireLies();
        if (hub.estLie(e.getUniqueId())) return;
        hub.relireLies();                       // pas dans la liste en cache (60 s) : on redemande au Hub tout de suite, pour qu'une liaison faite à l'instant passe du premier coup
        if (hub.estLie(e.getUniqueId())) return;
        String code = codes.computeIfAbsent(e.getUniqueId(), this::codePour);   // stable pour ce compte : l'UUID est unique et vérifié par Mojang (online-mode)
        hub.envoyer("code", "{\"uuid\":" + Hub.j(e.getUniqueId().toString()) + ",\"nom\":" + Hub.j(e.getName()) + ",\"code\":" + Hub.j(code) + "}");
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text(Textes.t(null, "kick_membres"), NamedTextColor.GOLD)
            .append(Component.text(code, NamedTextColor.AQUA)).append(Component.text(Textes.t(null, "kick_suite"), NamedTextColor.GRAY)));
    }
    /** Code de liaison permanent : dérivé de l'UUID et de la clé du serveur (HMAC), donc identique à chaque connexion, jamais périmé. */
    private String codePour(UUID u) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(getConfig().getString("cle", "krp").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(u.toString().getBytes(StandardCharsets.UTF_8));
            String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(a.charAt((h[i] & 0xff) % a.length()));
            return s.toString();
        } catch (Exception ex) { String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; SecureRandom r = new SecureRandom(); StringBuilder s = new StringBuilder(); for (int i = 0; i < 6; i++) s.append(a.charAt(r.nextInt(a.length()))); return s.toString(); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setGameMode(GameMode.ADVENTURE); p.setAllowFlight(false); p.setFoodLevel(20); p.setSaturation(20f); p.setHealth(20);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, PotionEffect.INFINITE_DURATION, 0, false, false));
        int[] j = joueurs.computeIfAbsent(p.getUniqueId(), u -> new int[]{0, 0, 0, 0, 0});
        arrivees.put(p.getUniqueId(), System.currentTimeMillis());
        if (reseau && p.hasMetadata("lobbik_pont")) {
            // arrivé à pied par le pont (réseau Lobbik) : il reste où il est, au pied de la Tour ; son point de réapparition l'attend
            double[] ici = dernierePos.remove(p.getUniqueId());
            if (ici != null && monde != null) {
                // il avait quitté le jeu en pleine ascension (Kripy, 27/09/2026 : « quand je repasse la porte, remets-moi où j'étais ») :
                // on reconnecte au hub, et la porte de la Tour le ramène à cet endroit précis
                org.bukkit.Location l = new org.bukkit.Location(monde, ici[0], ici[1], ici[2], (float) ici[3], (float) ici[4]);
                Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) { p.teleport(l); figer(p, 3); p.sendMessage(Textes.t(p, "retour_pos")); } }, 2L);
                sauverPositions();
                hub.envoyer("connexion", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"niveau\":" + j[0] + "}");
                return;
            }
            j[0] = 0;
            p.sendMessage(Textes.t(p, "aide"));
            if (j[2] > 0) p.sendMessage(Component.text("Votre point de réapparition vous attend : marchez sur la dalle dorée au bout du pont, ou tapez /cp.", NamedTextColor.GOLD));
            hub.envoyer("connexion", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"niveau\":" + j[0] + "}");
            return;
        }
        double[] d = dernierePos.get(p.getUniqueId());
        // téléportation 2 ticks après l'arrivée : faite dans l'événement même, elle était parfois écrasée par la position de connexion
        if (d != null && monde != null) { org.bukkit.Location ici = new org.bukkit.Location(monde, d[0], d[1], d[2], (float)d[3], (float)d[4]); p.teleport(ici);
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.teleport(ici); }, 2L); p.sendMessage(Textes.t(p, "aide")); p.sendMessage(Textes.t(p, "retour_pos")); }
        else { p.teleport(pointDeRetour(j)); p.sendMessage(Textes.t(p, "aide")); if (j[1] > 0) p.sendMessage(Textes.t(p, "record", j[1], nomPoint(p, j))); }
        hub.envoyer("connexion", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"niveau\":" + j[0] + "}");
    }
    /* ---------------- réseau Lobbik (27/09/2026) : dalle dorée « reprendre mon ascension » au bout du pont ---------------- */
    /** Figé quelques secondes au retour à sa dernière position (le temps que le terrain arrive) — seulement en venant du hub par la porte. */
    private final Map<UUID, Long> figes = new ConcurrentHashMap<>();
    private void figer(Player p, int secondes) {
        figes.put(p.getUniqueId(), System.currentTimeMillis() + secondes * 1000L);
        p.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), Component.text("Retour à votre position…", NamedTextColor.GRAY),
            net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofSeconds(secondes), java.time.Duration.ofMillis(400))));
    }
    @EventHandler(priority = EventPriority.LOWEST) public void fige(PlayerMoveEvent e) {
        Long t = figes.get(e.getPlayer().getUniqueId()); if (t == null) return;
        if (t < System.currentTimeMillis()) { figes.remove(e.getPlayer().getUniqueId()); return; }
        org.bukkit.Location a = e.getFrom(), b = e.getTo();
        if (a.getX() != b.getX() || a.getY() != b.getY() || a.getZ() != b.getZ()) { org.bukkit.Location l = a.clone(); l.setYaw(b.getYaw()); l.setPitch(b.getPitch()); e.setTo(l); }
    }
    private boolean reseau;
    private final Map<UUID, Long> reprise = new ConcurrentHashMap<>();
    private static final int REPRISE_X = -25, REPRISE_Z = 3;
    private boolean surReprise(org.bukkit.Location l) { int x = l.getBlockX(), z = l.getBlockZ(); return x >= REPRISE_X && x <= REPRISE_X + 1 && z >= REPRISE_Z && z <= REPRISE_Z + 1 && l.getY() < Carte.Y_DEPART + 1; }
    private void poserReprise() {
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) monde.getBlockAt(REPRISE_X + dx, Carte.Y_DEPART - 1, REPRISE_Z + dz).setType(Material.GOLD_BLOCK, false);
        for (org.bukkit.entity.TextDisplay t : monde.getEntitiesByClass(org.bukkit.entity.TextDisplay.class)) if (t.getScoreboardTags().contains("krptour_reprise")) t.remove();
        monde.spawn(new org.bukkit.Location(monde, REPRISE_X + 1, Carte.Y_DEPART + 1.6, REPRISE_Z + 1), org.bukkit.entity.TextDisplay.class, t -> {
            t.setPersistent(false); t.addScoreboardTag("krptour_reprise"); t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER); t.setShadowed(false);
            t.text(Component.text("Reprendre mon ascension", NamedTextColor.GOLD).append(Component.newline()).append(Component.text("au dernier point de réapparition", NamedTextColor.GRAY)));
        });
    }
    private String nomPoint(Player p, int[] j) { return j[2] == 0 ? Textes.t(p, "depart") : Textes.t(p, "num", carte.checkpoints.indexOf(j[2]) + 1); }
    private org.bukkit.Location pointDeRetour(int[] j) {
        Carte.Cube c = carte.cubes.get(0);
        if (j[2] > 0) for (Carte.Cube k : carte.cubes) if (k.id == j[2]) { c = k; break; }
        return carte.lieu(monde, c);
    }

    /* ---------------- progression, chute, anti-triche ---------------- */
    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer(); if (p.getGameMode() != GameMode.ADVENTURE) return;
        org.bukkit.Location a = e.getFrom(), b = e.getTo();
        int[] j = joueurs.computeIfAbsent(p.getUniqueId(), u -> new int[]{0, 0, 0, 0, 0});
        if (p.isOnGround() && b.getWorld() == monde && b.getY() > Carte.Y_DEPART - 2) dernierSol.put(p.getUniqueId(), new double[]{b.getX(), b.getY(), b.getZ(), b.getYaw(), b.getPitch()});
        // chute sous la Tour : retour au point de réapparition, niveau courant remis à celui du point
        boolean presDeLaTour = Math.hypot(b.getX(), b.getZ()) < Carte.RAYON_MUR + 32;
        if (reseau && !presDeLaTour) return;   // sur le pont ou le décor du hub : c'est KrpReseau qui s'en occupe
        if (reseau && surReprise(b) && p.isOnGround()) { Long t = reprise.get(p.getUniqueId()); if (t == null || System.currentTimeMillis() - t > 4000) { reprise.put(p.getUniqueId(), System.currentTimeMillis()); j[0] = j[2]; p.teleport(pointDeRetour(j)); p.sendMessage(Textes.t(p, "cp_retour", nomPoint(p, j))); p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.4f); } return; }
        if (b.getY() < Carte.Y_DEPART - 12) {
            j[3]++; j[0] = j[2];
            p.teleport(pointDeRetour(j)); p.sendActionBar(Component.text(Textes.t(p, "chute", j[3]), NamedTextColor.RED));
            hub.envoyer("chute", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"niveau\":" + j[0] + "}");
            return;
        }
        // anti-triche simple : vitesse horizontale et verticale impossibles sans vol/speed
        double dh = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()), dv = b.getY() - a.getY();
        Long arrive = arrivees.get(p.getUniqueId());
        if ((dh > 1.05 || dv > 0.95) && (arrive == null || System.currentTimeMillis() - arrive > 3000)) {   // 3 s de grâce à l'arrivée : le passage par le pont peut rattraper quelques blocs
            int n = alertes.merge(p.getUniqueId(), 1, Integer::sum);
            e.setCancelled(true);
            if (n % 8 == 0) p.sendMessage(Textes.t(p, "triche", n));
            if (n >= 40) { p.kick(Component.text(Textes.t(p, "kick_triche"), NamedTextColor.RED)); hub.envoyer("triche", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"alertes\":" + n + "}"); }
            return;
        }
        if (p.isFlying() || p.isGliding()) { p.setFlying(false); e.setCancelled(true); return; }
        // niveau : le cube sous les pieds
        if (!p.isOnGround() && b.getY() - Math.floor(b.getY()) > 0.05) return;
        Carte.Cube c = carte.cubeSous(b);
        if (c == null) {
            // retombé sur la base (ou sur le mur) alors qu'on était en hauteur : chute, retour au point de réapparition
            if (j[0] > 0 && b.getY() <= Carte.Y_DEPART + 1 && p.isOnGround()) {
                j[3]++; j[0] = j[2];
                p.sendActionBar(Component.text(Textes.t(p, "chute", j[3]), NamedTextColor.RED));
                if (j[2] > 0) p.teleport(pointDeRetour(j));
                hub.envoyer("chute", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"niveau\":" + j[0] + "}");
            }
            return;
        }
        if (c.niveau != j[0] || c.checkpoint) {
            boolean progres = c.niveau > j[0];
            j[0] = c.niveau;
            if (c.niveau > j[1]) { j[1] = c.niveau; hub.envoyer("niveau", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"niveau\":" + c.niveau + ",\"chutes\":" + j[3] + "}"); }
            if (c.checkpoint && j[2] != c.id) {
                j[2] = c.id; p.sendMessage(Textes.t(p, "checkpoint", carte.checkpoints.indexOf(c.id) + 1));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.2f);
            }
            p.sendActionBar(Component.text(Textes.t(p, "niveau", c.niveau, carte.niveauMax) + (c.niveau == j[1] && progres ? Textes.t(p, "record_tag") : ""), progres ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            if (c.niveau == carte.niveauMax) { for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(Component.text(Textes.t(q, "sommet", p.getName()), NamedTextColor.GOLD)); hub.envoyer("sommet", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + "}"); }
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId(); alertes.remove(u);
        // on retient l'endroit exact où il quitte (s'il est posé sur un bloc de la Tour) pour l'y remettre à son retour
        Player q = e.getPlayer(); org.bukkit.Location l = q.getLocation();
        double[] sol = dernierSol.remove(u);
        if (q.isOnGround() && l.getWorld() == monde && l.getY() > Carte.Y_DEPART - 2) sol = new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()};
        if (sol != null && reseau && Math.hypot(sol[0], sol[2]) > Carte.RAYON_MUR + 32) sol = null;   // parti par le pont : on ne le remettra pas dans le décor du hub
        if (sol != null) { dernierePos.put(u, sol); sauverPositions(); }
        else dernierePos.remove(u);
        Long arrivee = arrivees.remove(u); int[] j = joueurs.getOrDefault(u, new int[]{0, 0, 0, 0, 0});
        // fin de session vers le Hub (historique de la page Jouer) : durée, niveau à la sortie, record, chutes, temps de jeu total
        hub.envoyer("deconnexion", "{\"uuid\":" + Hub.j(u.toString()) + ",\"nom\":" + Hub.j(e.getPlayer().getName()) + ",\"niveau\":" + j[0] + ",\"record\":" + j[1] + ",\"chutes\":" + j[3]
            + ",\"temps\":" + (j.length > 4 ? j[4] : 0) + ",\"session\":" + (arrivee == null ? 0 : (System.currentTimeMillis() - arrivee) / 1000) + "}");
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { int[] j = joueurs.computeIfAbsent(e.getPlayer().getUniqueId(), u -> new int[]{0, 0, 0, 0, 0}); e.setRespawnLocation(pointDeRetour(j)); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player) e.setCancelled(true); }
    @EventHandler public void onHunger(FoodLevelChangeEvent e) { e.setCancelled(true); }

    /* ---------------- rien ne se casse, rien ne se pose ---------------- */
    @EventHandler public void onBreak(BlockBreakEvent e) { e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { e.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent e) { if (e.getClickedBlock() != null) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { e.setCancelled(true); }
    @EventHandler public void onGm(PlayerGameModeChangeEvent e) { if (e.getNewGameMode() != GameMode.ADVENTURE && !e.getPlayer().hasPermission("krptour.admin")) e.setCancelled(true); }
    @EventHandler public void onFly(PlayerToggleFlightEvent e) { if (!e.getPlayer().hasPermission("krptour.admin")) { e.setCancelled(true); e.getPlayer().setFlying(false); } }

    /* ---------------- commandes publiques ---------------- */
    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String n = cmd.getName();
        if (n.equals("tour")) {
            if (args.length > 0 && args[0].equals("regenerer")) { carte.construire(monde); s.sendMessage("Tour reconstruite."); return true; }
            s.sendMessage("Tour : " + carte.cubes.size() + " cubes · " + joueurs.size() + " grimpeurs connus · graine " + carte.graine); return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("En jeu seulement."); return true; }
        int[] j = joueurs.computeIfAbsent(p.getUniqueId(), u -> new int[]{0, 0, 0, 0, 0});
        switch (n) {
            case "cp" -> { j[0] = j[2]; p.teleport(pointDeRetour(j)); p.sendMessage(Textes.t(p, "cp_retour", nomPoint(p, j))); }
            case "niveau" -> p.sendMessage(Textes.t(p, "mon_niveau", j[0], j[1], carte.niveauMax, j[3]));
            case "top" -> { p.sendMessage(Textes.t(p, "top")); int k = 1; for (Map.Entry<UUID, int[]> e : classement(10)) { OfflinePlayer o = Bukkit.getOfflinePlayer(e.getKey()); p.sendMessage(Textes.t(p, "top_l", k++, o.getName() == null ? "?" : o.getName(), e.getValue()[1])); } }
        }
        return true;
    }
    private List<Map.Entry<UUID, int[]>> classement(int n) {
        List<Map.Entry<UUID, int[]>> l = new ArrayList<>(joueurs.entrySet()); l.sort((a, b) -> b.getValue()[1] - a.getValue()[1]); return l.subList(0, Math.min(n, l.size()));
    }

    /* ---------------- tableau latéral ---------------- */
    private void tableau() {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = sm.getNewScoreboard();
            Objective o = sb.registerNewObjective("tour", Criteria.DUMMY, Component.text(Textes.t(p, "sb_titre"), NamedTextColor.GOLD));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            int[] j = joueurs.getOrDefault(p.getUniqueId(), new int[]{0, 0, 0, 0, 0});
            int ligne = 15;
            o.getScore(Textes.t(p, "sb_moi", j[0], j[1])).setScore(ligne--);
            int rang = 1; for (int[] v : joueurs.values()) if (v[1] > j[1]) rang++;
            int sec = j.length > 4 ? j[4] : 0;
            o.getScore(Textes.t(p, "sb_rang", rang, joueurs.size(), sec / 3600, (sec % 3600) / 60)).setScore(ligne--);
            // Kripy (20/09/2026, deux fois) : tableau le plus petit possible → 3 lignes : moi, mon rang, le meneur. Le reste par /top.
            Map.Entry<UUID, int[]> tete = null; for (Map.Entry<UUID, int[]> e : classement(1)) tete = e;
            if (tete != null && !tete.getKey().equals(p.getUniqueId())) { OfflinePlayer op = Bukkit.getOfflinePlayer(tete.getKey()); String nom = op.getName() == null ? "?" : op.getName(); if (nom.length() > 8) nom = nom.substring(0, 8); o.getScore("§6★ §f" + nom + " §7" + tete.getValue()[1]).setScore(ligne--); }
            p.setScoreboard(sb);
        }
    }

    /* ---------------- persistance ---------------- */
    private synchronized void sauverJoueurs() {
        try {
            getDataFolder().mkdirs(); StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<UUID, int[]> e : joueurs.entrySet()) { int[] v = e.getValue(); if (!first) sb.append(','); first = false; sb.append('"').append(e.getKey()).append("\":[").append(v[0]).append(',').append(v[1]).append(',').append(v[2]).append(',').append(v[3]).append(',').append(v.length > 4 ? v[4] : 0).append(']'); }
            Files.writeString(fichierJoueurs.toPath(), sb.append('}').toString(), StandardCharsets.UTF_8);
        } catch (IOException e) { getLogger().warning("sauvegarde des joueurs : " + e.getMessage()); }
    }
    /** positions.json : une ligne par joueur « uuid x y z yaw pitch », réécrit à chaque sortie et au rechargement du plugin. */
    private synchronized void sauverPositions() {
        try {
            getDataFolder().mkdirs(); StringBuilder sb = new StringBuilder();
            for (Map.Entry<UUID, double[]> e : dernierePos.entrySet()) { double[] v = e.getValue(); sb.append(e.getKey()).append(' ').append(v[0]).append(' ').append(v[1]).append(' ').append(v[2]).append(' ').append(v[3]).append(' ').append(v[4]).append('\n'); }
            Files.writeString(fichierPositions.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) { getLogger().warning("sauvegarde des positions : " + e.getMessage()); }
    }
    private void chargerPositions() {
        try {
            if (!fichierPositions.exists()) return;
            for (String l : Files.readAllLines(fichierPositions.toPath(), StandardCharsets.UTF_8)) {
                String[] v = l.trim().split(" "); if (v.length != 6) continue;
                try { dernierePos.put(UUID.fromString(v[0]), new double[]{Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]), Double.parseDouble(v[4]), Double.parseDouble(v[5])}); } catch (Exception ignored) {}
            }
        } catch (Exception e) { getLogger().warning("lecture des positions : " + e.getMessage()); }
    }
    private void chargerJoueurs() {
        try {
            if (!fichierJoueurs.exists()) return;
            String s = Files.readString(fichierJoueurs.toPath(), StandardCharsets.UTF_8).trim();
            for (String part : s.substring(1, s.length() - 1).split("\\],")) {
                String[] kv = part.split("\":\\["); if (kv.length != 2) continue;
                String[] v = kv[1].replace("]", "").split(",");
                joueurs.put(UUID.fromString(kv[0].replace("\"", "").trim()), new int[]{Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()), Integer.parseInt(v[2].trim()), Integer.parseInt(v[3].trim()), v.length > 4 ? Integer.parseInt(v[4].trim()) : 0});
            }
        } catch (Exception e) { getLogger().warning("lecture des joueurs : " + e.getMessage()); }
    }
}
