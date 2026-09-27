package hk.krp.reseau;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * KrpReseau (Lobbik, 27/09/2026) — le même plugin sur les trois serveurs du réseau Minecraft, avec un rôle chacun :
 * <ul>
 *   <li>{@code hub} : l'île féerique « Lobbik » (1000 places), les portes colorées par joueur, la file d'attente, les panneaux ;</li>
 *   <li>{@code tour} et {@code cube} : leur bout de pont et le retour au hub.</li>
 * </ul>
 * Partout : billets d'arrivée du proxy (numéro d'entité + position → passage sans coupure), décor commun et répliques
 * des autres serveurs visibles au loin, chat commun (le proxy le relaie aux autres serveurs), /lobbik.
 * Les plugins de jeu savent qu'un joueur arrive par le pont grâce à la métadonnée {@code lobbik_pont}.
 */
public final class KrpReseau extends JavaPlugin implements Listener, PluginMessageListener {
    static final String CANAL = "lobbik:reseau";
    private static final String VERSION_DECOR = "4";   // 2 : Cube habillé ; 3 : hub propre et moderne (Volt) ; 4 : mur d'écrans

    private String role; private World monde; private Hub hub; private Billets billets; private Portes portes; private Panneaux panneaux; private Ecrans ecrans;
    private final Map<UUID, Long> enPassage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fileDemandee = new ConcurrentHashMap<>();
    private final Set<UUID> visiteursPrevenus = ConcurrentHashMap.newKeySet();
    private final List<BlockDisplay> mursRetour = new ArrayList<>();
    private volatile long chatDepuis = 0;

    boolean hub() { return "hub".equals(role); }
    Portes portes() { return portes; }
    int ouverture() { return getConfig().getInt("ouverture", 45); }

    @Override public ChunkGenerator getDefaultWorldGenerator(String nom, String id) { return new Vide(); }

    @Override public void onLoad() {
        saveDefaultConfig();
        role = getConfig().getString("role", "hub");
        billets = new Billets(getLogger());
        // chaque serveur tire ses numéros d'entité dans sa propre plage (hub 10 M, tour 30 M, cube 50 M), décalée à chaque démarrage
        int base = switch (role) { case "tour" -> 30_000_000; case "cube" -> 50_000_000; default -> 10_000_000; };
        billets.decalerCompteur(base + (int) ((System.currentTimeMillis() / 1000) % 4_000_000) * 4);
    }

    @Override public void onEnable() {
        hub = new Hub(getConfig().getString("site", "https://lobbik.com"), getConfig().getString("cle_site", ""), getConfig().getBoolean("envoi_site", true), getLogger());
        try { billets.ouvrir(getConfig().getInt("port_controle", 25580), getConfig().getString("cle_reseau", "")); }
        catch (Exception e) { getLogger().severe("Port de contrôle indisponible : " + e + " — les passages seront ordinaires"); }
        getServer().getMessenger().registerOutgoingPluginChannel(this, CANAL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CANAL, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, this::demarrer);
    }

    private void demarrer() {
        monde = Bukkit.getWorlds().get(0);
        long temps = getConfig().getLong("heure", 18000);
        monde.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); monde.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        monde.setTime(temps); monde.setStorm(false); monde.setThundering(false);
        if (hub()) {
            monde.setGameRule(GameRule.DO_MOB_SPAWNING, false); monde.setGameRule(GameRule.FALL_DAMAGE, false);
            monde.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false); monde.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
            monde.setGameRule(GameRule.SPAWN_RADIUS, 0); monde.setDifficulty(Difficulty.PEACEFUL);
            monde.setSpawnLocation(Geo.spawn(monde));
        }
        File marque = new File(getDataFolder(), "decor-" + role + "-" + VERSION_DECOR + ".ok");
        if (!marque.exists()) {
            long t0 = System.currentTimeMillis();
            getLogger().info("Construction du décor commun (hub, ponts, portes, allées)…");
            new Decor(monde).toutCommun();
            if (!"tour".equals(role) && getConfig().getBoolean("replique_tour", true)) { getLogger().info("Réplique de La Tour…"); Repliques.tour(monde, getConfig().getLong("graine_tour", 20260919L)); }
            if (!"cube".equals(role) && getConfig().getBoolean("replique_cube", true)) { getLogger().info("Réplique du Cube…"); Repliques.cube(monde, getConfig()); }
            try { marque.getParentFile().mkdirs(); marque.createNewFile(); } catch (Exception ignored) { }
            getLogger().info("Décor construit en " + (System.currentTimeMillis() - t0) / 1000 + " s.");
        }
        if (hub()) {
            portes = new Portes(this, monde); portes.creer();
            panneaux = new Panneaux(this, monde, getConfig().getString("site", "https://lobbik.com")); panneaux.creer();
            ecrans = new Ecrans(this, monde, getConfig().getString("ecrans_tour", "/srv/minecraft/serveur/plugins/KrpTour/direct.json"), getConfig().getString("ecrans_cube", "/srv/minecraft/cube/plugins/KrpCube/direct.json"));
            ecrans.creer();
            Bukkit.getScheduler().runTaskTimer(this, portes::rendre, 20, 10);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> hub.relireLies(), 0, 20 * 60);
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::visiteurs, 200, 200);
        } else {
            // côté Tour et Cube : le mur vert du retour, visible de tous (on peut toujours revenir au hub)
            int x = "tour".equals(role) ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X;
            mursRetour.add(monde.spawn(new Location(monde, x + 0.42, Geo.PORTE_BAS, -Geo.PONT_DEMI), BlockDisplay.class, d -> {
                d.setPersistent(false); d.setBlock(Material.LIME_STAINED_GLASS.createBlockData()); d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.16f, Geo.PORTE_HAUT - Geo.PORTE_BAS + 1, 2 * Geo.PONT_DEMI + 1), new AxisAngle4f()));
            }));
        }
        if (getConfig().getBoolean("lire_chat_site", false)) Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::chatSite, 60, 60);
        Bukkit.getScheduler().runTaskTimer(this, () -> { long now = System.currentTimeMillis(); enPassage.entrySet().removeIf(e -> now - e.getValue() > 8000 && ramener(e.getKey())); }, 20, 20);
        getLogger().info("KrpReseau prêt — rôle " + role);
    }

    @Override public void onDisable() {
        billets.fermer();
        if (portes != null) portes.retirer();
        if (panneaux != null) panneaux.retirer();
        if (ecrans != null) ecrans.retirer();
        for (BlockDisplay d : mursRetour) d.remove();
    }

    /* ================================================================== arrivée par le pont */
    @SuppressWarnings({"deprecation", "removal"})
    @EventHandler(priority = EventPriority.LOWEST)
    public void connexion(PlayerLoginEvent e) {
        Billets.Billet b = billets.voir(e.getPlayer().getUniqueId());
        if (b == null) return;
        e.getPlayer().setMetadata("lobbik_pont", new FixedMetadataValue(this, b.depuis()));
        boolean ok = billets.appliquerNumero(e.getPlayer(), b.id(), monde != null ? monde : Bukkit.getWorlds().get(0));
        getLogger().info("Arrivée de " + e.getPlayer().getName() + " depuis " + b.depuis() + " : numéro d'entité " + (ok ? b.id() + " repris" : "nouveau"));
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void apparition(io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent e) {
        UUID u = e.getConnection().getProfile().getId();
        World w = monde != null ? monde : Bukkit.getWorlds().get(0);
        Billets.Billet b = u == null ? null : billets.voir(u);
        if (b != null) e.setSpawnLocation(b.lieu(w));
        else if (hub()) e.setSpawnLocation(Geo.spawn(w));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void arrivee(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Billets.Billet b = billets.prendre(p.getUniqueId());
        if (!hub()) {
            // connexion directe (sans billet) mais posé du mauvais côté de la porte, dans le décor du hub : on le remet sur son pont
            Location l = p.getLocation();
            if (b == null && ("tour".equals(role) ? l.getX() < Geo.PORTE_TOUR_X + 1 && l.getX() > Geo.PORTE_CUBE_X : l.getX() > Geo.PORTE_CUBE_X && l.getX() < Geo.PORTE_TOUR_X))
                Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.teleport("tour".equals(role) ? Geo.bordTour(monde) : Geo.bordCube(monde)); }, 3);
            return;
        }
        e.joinMessage(null);
        p.setGameMode(GameMode.ADVENTURE); p.setFoodLevel(20); p.setSaturation(20f);
        p.getInventory().clear();
        if (b == null) {
            p.showTitle(Title.title(Panneaux.degrade("LOBBIK"), Component.text("Bienvenue · Welcome · 欢迎", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            for (Player q : Bukkit.getOnlinePlayers()) if (q != p) q.sendMessage(Component.text(p.getName() + " arrive sur Lobbik", NamedTextColor.DARK_GRAY));
        }
        if (!hub.listeChargee() || !estMembre(p)) Bukkit.getScheduler().runTaskAsynchronously(this, () -> { hub.relireLies(); Bukkit.getScheduler().runTask(this, () -> accueilVisiteur(p)); });
    }
    @EventHandler public void depart(PlayerQuitEvent e) {
        if (hub()) { e.quitMessage(null); if (portes != null) portes.oublier(e.getPlayer()); }
        enPassage.remove(e.getPlayer().getUniqueId()); fileDemandee.remove(e.getPlayer().getUniqueId());
    }

    /* ================================================================== membres et visiteurs (hub) */
    boolean estMembre(Player p) { return !getConfig().getBoolean("membres_seulement", true) || hub.estLie(p.getUniqueId()); }
    private void accueilVisiteur(Player p) {
        if (!p.isOnline() || estMembre(p)) return;
        String code = codePour(p.getUniqueId());
        hub.envoyer("code", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"code\":" + Hub.j(code) + "}");
        p.sendMessage(Component.text("Bienvenue ! Pour passer les portes et parler, liez votre compte Minecraft à Lobbik :", NamedTextColor.GOLD));
        p.sendMessage(Component.text("lobbik.com → Minecraft → « Lier mon compte » · code ", NamedTextColor.GRAY).append(Component.text(code, NamedTextColor.AQUA, TextDecoration.BOLD)));
        p.sendMessage(Component.text("Restez ici : dès que c'est fait, tout s'ouvre, sans vous reconnecter.", NamedTextColor.GRAY));
        p.showTitle(Title.title(Component.text(code, NamedTextColor.AQUA, TextDecoration.BOLD), Component.text("Votre code de liaison · lobbik.com", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofSeconds(1))));
        visiteursPrevenus.add(p.getUniqueId());
    }
    /** Toutes les 10 s s'il y a des visiteurs : la liaison vient peut-être d'être faite sur le site. */
    private void visiteurs() {
        boolean y = false;
        for (Player p : Bukkit.getOnlinePlayers()) if (!hub.estLie(p.getUniqueId())) { y = true; break; }
        if (!y) return;
        hub.relireLies();
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (visiteursPrevenus.contains(p.getUniqueId()) && hub.estLie(p.getUniqueId())) {
                visiteursPrevenus.remove(p.getUniqueId());
                p.showTitle(Title.title(Component.text("Compte lié ✔", NamedTextColor.GREEN, TextDecoration.BOLD), Component.text("Les portes vous sont ouvertes", NamedTextColor.GRAY)));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
            }
        });
    }
    private String codePour(UUID u) {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(getConfig().getString("cle_site", "krp").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(u.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(); for (int i = 0; i < 6; i++) s.append(a.charAt((h[i] & 0xff) % a.length())); return s.toString();
        } catch (Exception ex) { SecureRandom r = new SecureRandom(); StringBuilder s = new StringBuilder(); for (int i = 0; i < 6; i++) s.append(a.charAt(r.nextInt(a.length()))); return s.toString(); }
    }

    /* ================================================================== franchir une porte */
    @EventHandler(ignoreCancelled = true)
    public void mouvement(PlayerMoveEvent e) {
        Location a = e.getFrom(), b = e.getTo();
        Player p = e.getPlayer();
        if (b.getY() < 40) { tombe(p); return; }
        if (a.getBlockX() == b.getBlockX() && a.getBlockZ() == b.getBlockZ() && Math.floor(a.getX() * 4) == Math.floor(b.getX() * 4)) return;
        if (!Geo.surPont(b.getX(), b.getZ()) && !hub()) return;
        if (hub()) {
            for (Portes.Porte po : portes.toutes()) {
                double gx = po.x + 0.5; int s = po.sens();
                boolean franchit = (a.getX() - gx) * s < 0 && (b.getX() - gx) * s >= 0 && Geo.surPont(b.getX(), b.getZ()) && b.getY() < Geo.PORTE_HAUT + 1;
                boolean touche = !franchit && Math.abs(b.getX() - gx) < 1.4 && (b.getX() - gx) * s < 0 && Geo.surPont(b.getX(), b.getZ());
                boolean dansAllee = !franchit && !touche && dansAllee(po, b);
                if (!franchit && !touche && !dansAllee) continue;
                Portes.Etat et = portes.etatDe(p, po);
                if (franchit) {
                    if (et == Portes.Etat.LIBRE || et == Portes.Etat.OUVERT) { passage(p, po.cible, b); }
                    else { e.setTo(a.clone().add(-s * 0.3, 0, 0)); refuser(p, po, et); }
                } else if (et == Portes.Etat.PLEIN) demanderFile(p, po, touche);
                else if (et == Portes.Etat.VISITEUR && touche) refuser(p, po, et);
                return;
            }
        } else {
            // vers le hub : la porte du côté de ce serveur
            int gx = "tour".equals(role) ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X; int s = "tour".equals(role) ? -1 : 1;   // sens de la marche vers le hub
            double px = gx + 0.5;
            if ((a.getX() - px) * s < 0 && (b.getX() - px) * s >= 0 && b.getY() < Geo.PORTE_HAUT + 1) passage(p, getConfig().getString("hub_nom", "hub"), b);
        }
    }
    private boolean dansAllee(Portes.Porte po, Location l) {
        int x = l.getBlockX(), z = l.getBlockZ();
        int[] ent = Decor.entree(po.tour);
        if (x == ent[0] && z == ent[1]) return true;
        for (int[] c : po.chemin) if (c[0] == x && c[1] == z) return true;
        return false;
    }
    private void demanderFile(Player p, Portes.Porte po, boolean touche) {
        Long t = fileDemandee.get(p.getUniqueId());
        if (t != null && System.currentTimeMillis() - t < 3000) return;
        fileDemandee.put(p.getUniqueId(), System.currentTimeMillis());
        envoyer(p, "file\tcible=" + po.cible);
        p.sendActionBar(Component.text(po.nom + " est plein : tu es dans la file d'attente, la porte s'ouvrira pour toi.", NamedTextColor.GOLD));
        if (touche) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
    }
    private void refuser(Player p, Portes.Porte po, Portes.Etat et) {
        Long t = fileDemandee.get(p.getUniqueId());
        if (t != null && System.currentTimeMillis() - t < 1500) return;
        fileDemandee.put(p.getUniqueId(), System.currentTimeMillis());
        if (et == Portes.Etat.VISITEUR) { p.sendActionBar(Component.text("Liez d'abord votre compte sur lobbik.com (code : " + codePour(p.getUniqueId()) + ")", NamedTextColor.AQUA)); return; }
        if (et == Portes.Etat.PLEIN) envoyer(p, "file\tcible=" + po.cible);
        p.sendActionBar(Component.text(po.nom + " est plein : attends ton tour, le mur deviendra doré pour toi.", NamedTextColor.RED));
    }
    /** Demande au proxy de faire passer le joueur, à la position exacte où il franchit la porte. */
    private void passage(Player p, String cible, Location l) {
        Long t = enPassage.get(p.getUniqueId());
        if (t != null && System.currentTimeMillis() - t < 8000) return;
        enPassage.put(p.getUniqueId(), System.currentTimeMillis());
        envoyer(p, "passage\tcible=" + cible + "\tx=" + l.getX() + "\ty=" + l.getY() + "\tz=" + l.getZ() + "\tyaw=" + l.getYaw() + "\tpitch=" + l.getPitch());
    }
    /** Passage manqué (refus, serveur absent, délai) : on ramène le joueur de ce côté-ci de la porte. */
    private boolean ramener(UUID u) {
        Player p = Bukkit.getPlayer(u); if (p == null) return true;
        int gx; int s;
        if (hub()) {
            Location l = p.getLocation();
            if (l.getX() > Geo.PORTE_TOUR_X) { gx = Geo.PORTE_TOUR_X; s = -1; } else if (l.getX() < Geo.PORTE_CUBE_X) { gx = Geo.PORTE_CUBE_X; s = 1; } else return true;
        } else { gx = "tour".equals(role) ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X; s = "tour".equals(role) ? 1 : -1;
            Location l = p.getLocation(); if ((l.getX() - gx - 0.5) * s > 0) return true; }
        Location l = p.getLocation().clone(); l.setX(gx + 0.5 + s * 2.5); l.setY(Geo.Y); if (!Geo.surPont(l.getX(), l.getZ())) l.setZ(0.5);
        p.teleport(l);
        return true;
    }
    private void tombe(Player p) {
        Location l;
        if (hub()) l = Geo.spawn(monde);
        else if ("tour".equals(role)) { if (Math.hypot(p.getX(), p.getZ()) < 60) return; l = Geo.bordTour(monde); }   // près de la Tour, c'est KrpTour qui s'en occupe
        else { if (p.getX() < Geo.CUBE_X0 + Geo.CUBE_COTE + 1 && p.getX() > Geo.CUBE_X0 - 2 && Math.abs(p.getZ()) < 52) return; l = Geo.bordCube(monde); }
        p.teleport(l); p.setFallDistance(0);
        p.sendActionBar(Component.text("Attention au vide !", NamedTextColor.GRAY));
    }

    /* ================================================================== messages du proxy */
    @Override public void onPluginMessageReceived(String canal, Player p, byte[] data) {
        if (!CANAL.equals(canal)) return;
        Map<String, String> m = lire(new String(data, StandardCharsets.UTF_8));
        switch (m.getOrDefault("", "")) {
            case "etat" -> { if (portes != null) portes.etat(p, m); }
            case "tour" -> { if (portes != null) portes.tonTour(p, m.getOrDefault("cible", "tour"), Integer.parseInt(m.getOrDefault("secondes", "45"))); }
            case "perdu" -> { if (portes != null) portes.perdu(p, m.getOrDefault("cible", "tour")); }
            case "refus" -> {
                enPassage.remove(p.getUniqueId()); ramenerPlusTard(p);
                p.sendActionBar(Component.text("plein".equals(m.get("raison")) ? "C'est plein : tu es dans la file d'attente." : "Ce serveur ne répond pas pour le moment.", NamedTextColor.RED));
            }
            default -> { }
        }
    }
    private void ramenerPlusTard(Player p) { Bukkit.getScheduler().runTask(this, () -> ramener(p.getUniqueId())); }
    void envoyer(Player p, String ligne) { p.sendPluginMessage(this, CANAL, ligne.getBytes(StandardCharsets.UTF_8)); }
    static Map<String, String> lire(String ligne) {
        Map<String, String> m = new HashMap<>(); String[] t = ligne.split("\t"); m.put("", t[0]);
        for (int i = 1; i < t.length; i++) { int k = t[i].indexOf('='); if (k > 0) m.put(t[i].substring(0, k), t[i].substring(k + 1)); }
        return m;
    }

    /* ================================================================== chat commun */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void chat(io.papermc.paper.event.player.AsyncChatEvent e) {
        e.setCancelled(true);   // jamais de message signé : un client qui change de serveur sans se reconnecter garderait sa chaîne de signatures
        Player p = e.getPlayer();
        String texte = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (texte.isEmpty()) return;
        if (hub() && !estMembre(p)) { p.sendMessage(Component.text("Liez votre compte sur lobbik.com pour parler (code : " + codePour(p.getUniqueId()) + ").", NamedTextColor.AQUA)); return; }
        TextColor c = TextColor.fromHexString(getConfig().getString("couleur", "#7CFF4F"));
        Component ligne = Component.text("[" + getConfig().getString("nom", "Lobbik") + "] ", c).append(Component.text(p.getName(), NamedTextColor.WHITE)).append(Component.text(" : " + texte, NamedTextColor.GRAY));
        for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(ligne);
        Bukkit.getConsoleSender().sendMessage(ligne);
        envoyer(p, "chat\ttexte=" + texte.replace('\t', ' '));
        if (getConfig().getBoolean("pont_chat_site", false)) hub.envoyer("chat", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"message\":" + Hub.j(texte) + "}");
    }
    private void chatSite() {
        if (Bukkit.getOnlinePlayers().isEmpty() && chatDepuis > 0) return;
        String corps = hub.lireChat(chatDepuis); if (corps == null) return;
        java.util.regex.Matcher md = java.util.regex.Pattern.compile("\"dernier\":(\\d+)").matcher(corps); if (md.find()) { long d = Long.parseLong(md.group(1)); if (chatDepuis == 0) { chatDepuis = d; return; } chatDepuis = d; }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\"id\":(\\d+),\"nick\":\"((?:[^\"\\\\]|\\\\.)*)\",\"texte\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}").matcher(corps);
        while (m.find()) {
            Component c = Component.text("[Site] ", TextColor.color(0x3FE0FF)).append(Component.text(dej(m.group(2)), NamedTextColor.WHITE)).append(Component.text(" : " + dej(m.group(3)), NamedTextColor.GRAY));
            for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(c);
        }
    }
    private static String dej(String s) { return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ").replace("\\\\", "\\"); }

    /* ================================================================== le hub : paisible */
    @EventHandler public void degats(EntityDamageEvent e) { if (hub() && e.getEntity() instanceof Player) e.setCancelled(true); }
    @EventHandler public void faim(FoodLevelChangeEvent e) { if (hub()) e.setCancelled(true); }
    @EventHandler public void casse(BlockBreakEvent e) { if (hub() && !e.getPlayer().hasPermission("krpreseau.admin")) e.setCancelled(true); }
    @EventHandler public void pose(BlockPlaceEvent e) { if (hub() && !e.getPlayer().hasPermission("krpreseau.admin")) e.setCancelled(true); }
    @EventHandler public void lacher(PlayerDropItemEvent e) { if (hub()) e.setCancelled(true); }
    @EventHandler public void interagir(PlayerInteractEvent e) {
        if (!hub() || e.getClickedBlock() == null || e.getPlayer().hasPermission("krpreseau.admin")) return;
        Material m = e.getClickedBlock().getType();
        if (m.name().endsWith("_STAIRS") && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) { s_asseoir(e.getPlayer(), e.getClickedBlock().getLocation()); e.setCancelled(true); return; }
        e.setCancelled(true);
    }
    /** S'asseoir sur les bancs du Cercle : un siège invisible qui disparaît quand on se lève. */
    private void s_asseoir(Player p, Location banc) {
        if (p.isInsideVehicle()) return;
        Location l = banc.clone().add(0.5, 0.35, 0.5);
        org.bukkit.entity.ArmorStand siege = monde.spawn(l, org.bukkit.entity.ArmorStand.class, a -> { a.setInvisible(true); a.setMarker(true); a.setGravity(false); a.setSmall(true); a.setPersistent(false); a.setInvulnerable(true); });
        siege.addPassenger(p);
        Bukkit.getScheduler().runTaskTimer(this, t -> { if (!p.isOnline() || !siege.getPassengers().contains(p)) { siege.remove(); t.cancel(); } }, 20, 20);
    }
    /* ================================================================== commandes */
    @Override public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("En jeu seulement."); return true; }
        switch (cmd.getName()) {
            case "lobbik" -> {
                if (hub()) { p.teleport(Geo.spawn(monde)); p.sendActionBar(Component.text("Retour à l'arrivée", NamedTextColor.GRAY)); }
                else { envoyer(p, "aller\tcible=" + getConfig().getString("hub_nom", "hub")); p.sendActionBar(Component.text("Retour à Lobbik…", NamedTextColor.GRAY)); }
            }
            case "file" -> {
                if (!hub()) { p.sendMessage("La file d'attente se fait sur Lobbik."); return true; }
                if (args.length > 0 && args[0].equalsIgnoreCase("quitter")) { for (Portes.Porte po : portes.toutes()) envoyer(p, "quitter\tcible=" + po.cible); p.sendMessage(Component.text("Tu as quitté les files d'attente.", NamedTextColor.GRAY)); return true; }
                Portes.Porte po = portes.porte(args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "tour");
                if (po == null) { p.sendMessage("/file tour · /file cube · /file quitter"); return true; }
                if (!estMembre(p)) { p.sendMessage(Component.text("Liez d'abord votre compte (code : " + codePour(p.getUniqueId()) + ")", NamedTextColor.AQUA)); return true; }
                envoyer(p, "file\tcible=" + po.cible); p.sendMessage(Component.text("Te voilà dans la file pour " + po.nom + ".", NamedTextColor.GOLD));
            }
            default -> { }
        }
        return true;
    }
}
