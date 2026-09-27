package hk.krp.cube;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import hk.krp.cube.Labyrinthe.Piege;
import hk.krp.cube.Labyrinthe.Salle;

/** Le Cube (23/09/2026, demande de Maxime) : on se réveille dans une salle cubique ; chaque salle se scelle quand on y entre.
 *  Comme dans le film, c'est la SALLE qui tue (depuis le 23/09 au soir) : les salles sûres forment un labyrinthe 3D (voir Plan),
 *  toutes les autres tuent, toujours, par quelque porte qu'on y entre, et ressortir ne sauve pas. Aucun numéro, aucune indication.
 *  On gagne par salles traversées ; la sortie est quelque part.
 *  Un autre serveur que La Tour, même méthode : la carte est générée par le plugin. */
public final class KrpCube extends JavaPlugin implements Listener {
    private Labyrinthe lab; private World monde; private Hub hub;
    private final Map<UUID, String> codes = new HashMap<>();
    private final Map<UUID, Salle> ou = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> visitees = new HashMap<>();             // salles distinctes traversées dans la tentative en cours (le score)
    private final Map<UUID, Long> prochainFrisson = new HashMap<>();             // ambiance : prochain événement (ms)
    private File marque;
    private final Map<Integer, Integer> scelleeJusquA = new HashMap<>();          // id de salle → tic de déverrouillage (les trappes restent fermées : au joueur de les rouvrir)
    private final Map<UUID, Long> debut = new HashMap<>();                       // début de la tentative en cours
    private final Map<UUID, Integer> morts = new HashMap<>();
    private final Map<UUID, String> causeMort = new HashMap<>();
    private final Set<UUID> livreDonne = new HashSet<>();
    private final Random alea = new Random();
    private File fichierRecords; private YamlConfiguration records;
    private int tic = 0;
    private final Map<Long, Long> _bascules = new HashMap<>();
    private final Map<UUID, Object> condamnes = new ConcurrentHashMap<>();       // joueur entré dans une salle mortelle → jeton de sa mise à mort (une seule à la fois)
    private final Map<UUID, Integer> derniereSure = new HashMap<>();             // dernière salle sûre traversée (avancement envoyé au site)

    @Override public ChunkGenerator getDefaultWorldGenerator(String nom, String id) { return new Vide(); }

    @Override public void onEnable() {
        saveDefaultConfig();
        // réseau Lobbik (27/09/2026) : le Cube posé à côté du hub, entrée par le vestibule au bout du pont
        reseau = getConfig().getBoolean("reseau.actif", false);
        Labyrinthe.X0 = getConfig().getInt("reseau.origine_x", 0); Labyrinthe.Z0 = getConfig().getInt("reseau.origine_z", 0);
        Labyrinthe.HABILLAGE = reseau;   // tuiles de couleur et contours lumineux dehors, vus depuis le hub
        vestibule = nombres(getConfig().getString("reseau.vestibule", "-196,-190,-2,2,64,68"));
        sortiePont = nombres(getConfig().getString("reseau.sortie_pont", "-186.5,64,0.5,-90"));
        lab = new Labyrinthe(getConfig().getLong("graine", 20260923L), Math.max(2, getConfig().getInt("largeur", 5)), Math.max(2, getConfig().getInt("niveaux", 5)),
                             Math.max(1, getConfig().getInt("profondeur", 2)), getConfig().getInt("pieges", 40));
        fichierRecords = new File(getDataFolder(), "records.yml"); records = YamlConfiguration.loadConfiguration(fichierRecords);
        hub = new Hub(getConfig().getString("hub", "https://lobbik.com"), getConfig().getString("cle", ""), getConfig().getBoolean("envoi_site", true), getLogger());
        if (!hub.configure()) getLogger().warning("Pas de clé Lobbik dans config.yml : le serveur est OUVERT À TOUS (mettre la clé de La Tour pour réserver aux membres).");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, this::demarrer);
    }
    private void demarrer() {
        monde = Bukkit.getWorlds().get(0);
        monde.setDifficulty(Difficulty.NORMAL);
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule immediate_respawn false"));   // écran de mort normal (réapparition instantanée = ciel vide chez le client)
        monde.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); monde.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        monde.setGameRule(GameRule.DO_MOB_SPAWNING, false); monde.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        monde.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true); monde.setGameRule(GameRule.KEEP_INVENTORY, true);
        monde.setGameRule(GameRule.SHOW_DEATH_MESSAGES, true); monde.setGameRule(GameRule.SPAWN_RADIUS, 0);
        monde.setGameRule(GameRule.NATURAL_REGENERATION, false);   // Maxime : « j'arrive pas à mourir » → on ne se soigne que dans la salle blanche, et un peu dans les salles sûres
        monde.setTime(18000); monde.setStorm(false);
        monde.setSpawnLocation(lab.depart.centre(monde));
        cleAffichage = new NamespacedKey(this, "code_salle");
        nettoyerAffichages(true);   // aucun affichage orphelin d'un lancement précédent
        // le marqueur porte l'empreinte du plan (arbre + départ + sortie + dimensions) : nouvelle graine ou nouveau générateur ⇒ reconstruction
        marque = new File(getDataFolder(), "cube-" + lab.plan.empreinte() + (Labyrinthe.X0 != 0 || Labyrinthe.Z0 != 0 ? "@" + Labyrinthe.X0 + "," + Labyrinthe.Z0 : "") + ".ok");   // l'origine compte aussi (réseau Lobbik)
        getLogger().info("Plan du Cube : " + lab.plan.resume());
        if (!marque.exists()) construire();
        if (reseau) {
            poserBoutonSortie();
            File habille = new File(getDataFolder(), "habillage-1-" + lab.plan.empreinte() + ".ok");
            if (!habille.exists()) { lab.habiller(monde); try { habille.createNewFile(); } catch (Exception ignored) { } getLogger().info("Habillage extérieur posé (tuiles de couleur, contours lumineux)."); }
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> hub.relireLies(), 0, 20 * 60);
        // la carte (couleurs des salles, départ, sortie — jamais les salles mortelles ni les passages) pour la vue 3D du site, au démarrage puis toutes les heures
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::envoyerCarte, 60, 20 * 3600);
        Bukkit.getScheduler().runTaskTimer(this, this::frissons, 200, 20);
        Bukkit.getScheduler().runTaskTimer(this, this::clignoter, 100, 3);
        Bukkit.getScheduler().runTaskLater(this, this::poserPenombres, 60);
        Bukkit.getScheduler().runTaskTimer(this, this::musique, 300, 40);
        Bukkit.getScheduler().runTaskTimer(this, this::suivre, 20, 4);
        Bukkit.getScheduler().runTaskTimer(this, this::barre, 40, 20);
        Bukkit.getScheduler().runTaskTimer(this, this::ecrireDirect, 60, 60);   // écrans du hub (réseau Lobbik) : plugins/KrpCube/direct.json
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            StringBuilder b = new StringBuilder("["); boolean prem = true;
            for (Player q : Bukkit.getOnlinePlayers()) { Salle s = ou.get(q.getUniqueId()); if (s == null) continue;
                long t = debut.containsKey(q.getUniqueId()) ? (System.currentTimeMillis() - debut.get(q.getUniqueId())) / 1000 : 0;
                if (!prem) b.append(','); prem = false;
                b.append("{\"uuid\":").append(Hub.j(q.getUniqueId().toString())).append(",\"nom\":").append(Hub.j(q.getName())).append(",\"niveau\":").append(s.j + 1)
                 .append(",\"salles\":").append(salles(q)).append(",\"salles_max\":").append(Math.max(salles(q), records.getInt(q.getUniqueId() + ".salles_max")))
                 .append(",\"avance\":").append(lab.plan.avance(s.sure ? s.id : derniereSure.getOrDefault(q.getUniqueId(), -1))).append(",\"avance_max\":").append(avanceMax(q)).append(",\"code\":").append(Hub.j(s.code)).append(",\"i\":").append(s.i).append(",\"j\":").append(s.j).append(",\"k\":").append(s.k).append(",\"morts\":").append(morts.getOrDefault(q.getUniqueId(), 0)).append(",\"temps\":").append(t).append('}'); }
            hub.envoyer("cube_positions", "{\"joueurs\":" + b.append(']') + ",\"nx\":" + lab.nx + ",\"ny\":" + lab.ny + ",\"nz\":" + lab.nz + ",\"depart\":[" + lab.depart.i + "," + lab.depart.j + "," + lab.depart.k + "],\"sortie\":[" + lab.sortie.i + "," + lab.sortie.j + "," + lab.sortie.k + "]}");
        }, 100, 60);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player p : Bukkit.getOnlinePlayers()) { p.setFoodLevel(20); p.setSaturation(0f); } }, 100, 100);
        getLogger().info("KrpCube prêt — " + lab.salles.size() + " salles, " + (lab.salles.size() - lab.nbMortelles()) + " sûres, " + lab.nbMortelles() + " mortelles, chemin le plus court de " + lab.plan.chemin.length + " salles jusqu'à la sortie.");
    }
    /** État en direct pour les écrans du hub : qui est dans le Cube (niveau, salles, avancée vers la sortie, morts), records. */
    private void ecrireDirect() {
        StringBuilder b = new StringBuilder("{\"t\":").append(System.currentTimeMillis()).append(",\"salles\":").append(lab.salles.size())
            .append(",\"sures\":").append(lab.salles.size() - lab.nbMortelles()).append(",\"chemin\":").append(lab.plan.chemin.length).append(",\"niveaux\":").append(lab.ny).append(",\"joueurs\":[");
        boolean prem = true;
        for (Player q : Bukkit.getOnlinePlayers()) {
            Salle s = ou.get(q.getUniqueId());
            if (!prem) b.append(','); prem = false;
            int av = s == null ? 0 : lab.plan.avance(s.sure ? s.id : derniereSure.getOrDefault(q.getUniqueId(), -1));
            b.append("{\"nom\":").append(Hub.j(q.getName())).append(",\"dedans\":").append(s != null).append(",\"niveau\":").append(s == null ? 0 : s.j + 1)
             .append(",\"salles\":").append(salles(q)).append(",\"avance\":").append(av).append(",\"morts\":").append(morts.getOrDefault(q.getUniqueId(), 0)).append('}');
        }
        b.append("],\"loin\":[");
        List<Map.Entry<String, Integer>> loin = new ArrayList<>();
        List<Map.Entry<String, Long>> vite = new ArrayList<>();
        int sorties = 0;
        for (String k : records.getKeys(false)) {
            int sm = records.getInt(k + ".salles_max", 0); if (sm > 0) loin.add(Map.entry(records.getString(k + ".nom", "?"), sm));
            long m = records.getLong(k + ".meilleur", 0); if (m > 0) vite.add(Map.entry(records.getString(k + ".nom", "?"), m));
            sorties += records.getInt(k + ".sorties", 0);
        }
        loin.sort((x, y) -> y.getValue() - x.getValue()); vite.sort(Map.Entry.comparingByValue());
        for (int i = 0; i < Math.min(8, loin.size()); i++) { if (i > 0) b.append(','); b.append("{\"nom\":").append(Hub.j(loin.get(i).getKey())).append(",\"salles\":").append(loin.get(i).getValue()).append('}'); }
        b.append("],\"vite\":[");
        for (int i = 0; i < Math.min(5, vite.size()); i++) { if (i > 0) b.append(','); b.append("{\"nom\":").append(Hub.j(vite.get(i).getKey())).append(",\"temps\":").append(vite.get(i).getValue()).append('}'); }
        b.append("],\"sorties\":").append(sorties).append('}');
        String json = b.toString();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { java.nio.file.Path tmp = new File(getDataFolder(), "direct.json.tmp").toPath(); java.nio.file.Files.writeString(tmp, json, java.nio.charset.StandardCharsets.UTF_8);
                java.nio.file.Files.move(tmp, new File(getDataFolder(), "direct.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); } catch (Exception ignored) { }
        });
    }
    @Override public void onDisable() { sauverRecords(); if (monde != null) nettoyerAffichages(true); }
    /** (Re)construit tout le Cube (chaque cellule est réécrite entièrement : l'ancien décor disparaît), pose le marqueur, renvoie tout le monde au départ. */
    private void construire() {
        getLogger().info("Construction du Cube : " + lab.salles.size() + " salles (" + lab.nx + " × " + lab.ny + " niveaux × " + lab.nz + ")…");
        long t0 = System.currentTimeMillis();
        lab.construire(monde);
        try { marque.getParentFile().mkdirs(); for (File f : Objects.requireNonNullElse(getDataFolder().listFiles(), new File[0])) if (f.getName().startsWith("cube-") && f.getName().endsWith(".ok")) f.delete(); marque.createNewFile(); } catch (Exception ignored) {}
        scelleeJusquA.clear(); visitees.clear(); debut.clear(); ou.clear(); nettoyerAffichages(true);
        for (Player p : Bukkit.getOnlinePlayers()) { p.teleport(lab.depart.centre(monde)); records.set(p.getUniqueId() + ".plan", lab.plan.empreinte()); }
        sauverRecords();
        getLogger().info("Cube construit en " + (System.currentTimeMillis() - t0) + " ms. Départ " + lab.depart.coord() + ", sortie " + lab.sortie.coord() + " (niveau " + (lab.sortie.j + 1) + ").");
    }
    private void envoyerCarte() {
        StringBuilder b = new StringBuilder("[");
        for (Salle s : lab.salles) { if (b.length() > 1) b.append(','); b.append('[').append(s.i).append(',').append(s.j).append(',').append(s.k).append(',').append(s.couleur).append(']'); }
        hub.envoyer("cube_carte", "{\"nx\":" + lab.nx + ",\"ny\":" + lab.ny + ",\"nz\":" + lab.nz + ",\"depart\":[" + lab.depart.i + "," + lab.depart.j + "," + lab.depart.k + "],\"sortie\":[" + lab.sortie.i + "," + lab.sortie.j + "," + lab.sortie.k + "],\"salles\":" + b.append(']') + "}");
    }
    private int avanceMax(Player p) { Set<Integer> v = visitees.get(p.getUniqueId()); int m = 0; if (v != null) for (int id : v) m = Math.max(m, lab.plan.avance(id)); return m; }
    private int salles(Player p) { Set<Integer> v = visitees.get(p.getUniqueId()); return v == null ? 0 : v.size(); }
    /* ------------------------------------------------------------------ réseau Lobbik : vestibule et pont */
    /** Figé quelques secondes au retour à sa dernière position (le temps que le terrain arrive) — seulement en venant du hub par la porte. */
    private final Map<UUID, Long> figes = new java.util.concurrent.ConcurrentHashMap<>();
    private void figer(Player p, int secondes) {
        figes.put(p.getUniqueId(), System.currentTimeMillis() + secondes * 1000L);
        p.showTitle(Title.title(Component.empty(), Component.text("Retour dans le Cube…", NamedTextColor.GRAY), Title.Times.times(Duration.ZERO, Duration.ofSeconds(secondes), Duration.ofMillis(400))));
    }
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST) public void fige(PlayerMoveEvent e) {
        Long t = figes.get(e.getPlayer().getUniqueId()); if (t == null) return;
        if (t < System.currentTimeMillis()) { figes.remove(e.getPlayer().getUniqueId()); return; }
        Location a = e.getFrom(), b = e.getTo();
        if (a.getX() != b.getX() || a.getY() != b.getY() || a.getZ() != b.getZ()) { Location l = a.clone(); l.setYaw(b.getYaw()); l.setPitch(b.getPitch()); e.setTo(l); }
    }
    private boolean reseau; private double[] vestibule, sortiePont;
    private final Set<UUID> absorbes = new HashSet<>();
    /** Réseau : joueurs dehors (arrivés par le pont, ou sortis) — ni suivis salle par salle, ni tués par le vide. */
    private final Set<UUID> surLePont = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static double[] nombres(String s) { String[] t = s.split(","); double[] d = new double[t.length]; for (int i = 0; i < t.length; i++) d[i] = Double.parseDouble(t[i].trim()); return d; }
    private boolean dansVestibule(Location l) {
        return l.getX() >= vestibule[0] && l.getX() < vestibule[1] + 1 && l.getZ() >= vestibule[2] && l.getZ() < vestibule[3] + 1 && l.getY() >= vestibule[4] && l.getY() <= vestibule[5];
    }
    private Location surLePont() { return new Location(monde, sortiePont[0], sortiePont[1], sortiePont[2], (float) sortiePont[3], 0f); }
    /** Bouton de sortie dans la salle blanche (réseau Lobbik) : sur le mur nord, à gauche de la porte. */
    private Location boutonSortie() { return new Location(monde, lab.depart.ox() + 2, lab.depart.oy() + 2, lab.depart.oz() + 1); }
    private boolean estBoutonSortie(Block b) { Location l = boutonSortie(); return b.getX() == l.getBlockX() && b.getY() == l.getBlockY() && b.getZ() == l.getBlockZ(); }
    private void poserBoutonSortie() {
        org.bukkit.block.data.type.Switch bt = (org.bukkit.block.data.type.Switch) Bukkit.createBlockData(Material.POLISHED_BLACKSTONE_BUTTON);
        bt.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.WALL); bt.setFacing(org.bukkit.block.BlockFace.SOUTH);
        boutonSortie().getBlock().setBlockData(bt, false);
    }
    private void sortirParLeBouton(Player p) {
        finTentative(p); morts.remove(p.getUniqueId()); ou.remove(p.getUniqueId());
        for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false));
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.8f);
        surLePont.add(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> { if (!p.isOnline()) return; p.teleport(surLePont()); p.setHealth(Math.max(p.getHealth(), 1));
            p.sendMessage(Component.text("Vous êtes sorti du Cube. Le pont ramène à Lobbik ; le vestibule blanc, à l'intérieur.", NamedTextColor.GRAY)); }, 20L);
    }

    /** Le vestibule se referme, la lumière s'éteint… et l'on se réveille dans la salle blanche (comme dans le film : personne ne sait comment il est entré). */
    private void absorber(Player p) {
        if (!absorbes.add(p.getUniqueId())) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false));
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.8f, 0.7f);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            absorbes.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            surLePont.remove(p.getUniqueId());
            p.teleport(lab.depart.centre(monde)); finTentative(p); morts.remove(p.getUniqueId());
            ou.put(p.getUniqueId(), lab.depart);
            p.showTitle(Title.title(Component.text("Le Cube", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Une pièce. Six issues. Le Cube ne pardonne pas.", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(800), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            if (livreDonne.add(p.getUniqueId()) && !p.getInventory().contains(Material.WRITTEN_BOOK)) p.getInventory().setItem(8, livre());
        }, 35L);
    }

    /** Fin de tentative (mort, sortie, /recommencer) : le record de salles traversées est gardé ; renvoie le score de la tentative. */
    private int finTentative(Player p) {
        int n = salles(p); String cle = p.getUniqueId().toString();
        if (n > records.getInt(cle + ".salles_max")) records.set(cle + ".salles_max", n);
        records.set(cle + ".nom", p.getName());
        visitees.remove(p.getUniqueId()); debut.remove(p.getUniqueId()); ou.remove(p.getUniqueId()); derniereSure.remove(p.getUniqueId());
        return n;
    }

    /* ------------------------------------------------------------------ suivi des joueurs */
    private void suivre() {
        tic += 4;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() != monde || p.isDead()) continue;   // mort en attente de réapparition : on ne le retue pas à chaque tick (5 morts comptées en 1 s)
            Location l = p.getLocation();
            int bx = l.getBlockX() - Labyrinthe.X0, by = l.getBlockY() - Labyrinthe.Y0, bz = l.getBlockZ() - Labyrinthe.Z0, P = Labyrinthe.P;
            boolean dehors = bx < -1 || bz < -1 || bx >= lab.nx * P || bz >= lab.nz * P || by >= lab.ny * P || by < -1;
            // réseau : sur le pont ou dans le vestibule, on n'est pas encore entré dans le Cube (ou on en est sorti)
            if (reseau && surLePont.contains(p.getUniqueId())) { if (dansVestibule(l)) absorber(p); continue; }
            if (dehors || l.getY() < Labyrinthe.Y0 - 12) { causeMort.put(p.getUniqueId(), "est sorti du Cube, dans le vide"); p.setHealth(0); continue; }
            Salle s = lab.salleDe(l);
            if (s == null) continue;
            Salle avant = ou.put(p.getUniqueId(), s);
            if (avant != s) entrer(p, s);
            // en haut de la colonne centrale, on pousse la trappe du plafond : elle s'ouvre toute seule (hors de portée du clic depuis le sol)
            if (l.getBlockX() == s.ox() + 4 && l.getBlockZ() == s.oz() + 4 && l.getBlockY() >= s.oy() + 5 && s.portes[4] && !lab.ouverte(monde, s, 4)) {
                Integer fin = scelleeJusquA.get(s.id); Salle v = lab.voisin(s, 4); Integer finV = v != null ? scelleeJusquA.get(v.id) : null;
                if ((fin == null || fin <= tic) && (finV == null || finV <= tic)) { lab.basculer(monde, s, 4, true); p.playSound(l, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 0.7f); }
            }
        }
    }

    /* ------------------------------------------------------------------ numéros des salles : internes seulement */
    // 23/09/2026 : Maxime a demandé puis annulé les numéros affichés (« n'affiche rien »). Le code de salle reste interne
    // (commandes admin, cube_positions). La v1.3.0 posait des TextDisplay non persistants marqués « krpcube:code_salle » :
    // par sécurité, tout affichage encore marqué est supprimé au démarrage, à l'arrêt et au chargement d'un chunk.
    private NamespacedKey cleAffichage;
    private void nettoyerAffichages(boolean tout) {
        if (cleAffichage == null) return;
        for (TextDisplay t : monde.getEntitiesByClass(TextDisplay.class)) if (t.getPersistentDataContainer().has(cleAffichage, PersistentDataType.INTEGER)) t.remove();
    }
    @EventHandler public void onEntites(EntitiesLoadEvent e) {
        if (cleAffichage == null) return;
        for (Entity en : e.getEntities()) if (en instanceof TextDisplay t && t.getPersistentDataContainer().has(cleAffichage, PersistentDataType.INTEGER)) t.remove();
    }

    private void entrer(Player p, Salle s) {
        UUID u = p.getUniqueId();
        if (condamnes.containsKey(u)) return;                        // déjà condamné : ressortir ne change rien, rien ne compte plus
        // 23/09/2026 au soir (Maxime) : c'est la SALLE qui tue, quelle que soit la porte ; aucune échappatoire (voir salleFatale)
        if (!s.sure) { if (joueurVulnerable(p)) salleFatale(p, s); return; }
        derniereSure.put(u, s.id);
        if (s.depart) { soigner(p, 20); if (reseau) p.sendActionBar(Component.text("Salle blanche · le bouton noir, à côté de la porte nord, ramène dehors (ou /lobbik)", NamedTextColor.GRAY)); return; }                   // le chronomètre ne part qu'en quittant la salle blanche
        debut.putIfAbsent(u, System.currentTimeMillis());
        if (boiteAMusique(s)) jouerBoite(p);
        if (visitees.computeIfAbsent(u, k -> new HashSet<>()).add(s.id)) {
            int n = salles(p), rec = records.getInt(u + ".salles_max");
            if (n > rec && rec > 0 && n > 1) p.sendActionBar(Component.text("Nouveau record : " + n + " salles.", NamedTextColor.GOLD));
        }
        if (s.sortie) { victoire(p); return; }
        Integer fin = scelleeJusquA.get(s.id);
        if (fin != null && fin > tic) return;                       // déjà scellée : la séquence est en cours
        soigner(p, 4);                                              // une salle sûre : on souffle, deux cœurs
        activer(s);
    }
    private void soigner(Player p, double pv) { double m = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(); p.setHealth(Math.min(m, p.getHealth() + pv)); if (pv >= 20) { p.setFireTicks(0); for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType()); } }
    private List<Player> dedans(Salle s) {
        List<Player> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld() == monde && lab.salleDe(p.getLocation()) == s) l.add(p);
        return l;
    }
    private void son(Salle s, Sound son, float vol, float pitch) { for (Player p : dedans(s)) p.playSound(p.getLocation(), son, vol, pitch); }
    private void particules(Salle s, Particle part, int n, double vitesse) {
        Location c = s.centre(monde).add(0, 3, 0);
        monde.spawnParticle(part, c.getX(), c.getY(), c.getZ(), n, 3.0, 2.5, 3.0, vitesse);
    }

    /** Une salle sûre : elle se scelle, un peu d'ambiance, puis les trappes se déverrouillent. Elle ne tue jamais. */
    private void activer(Salle s) {
        int duree = 70;
        scelleeJusquA.put(s.id, tic + duree);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            lab.ouvrir(monde, s, false);
            son(s, Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f); son(s, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.5f);
            for (Player p : dedans(s)) p.sendActionBar(Component.text("La salle se scelle.", NamedTextColor.RED));
        }, 12);
        Bukkit.getScheduler().runTaskLater(this, () -> ambiance(s), 30);
        Bukkit.getScheduler().runTaskLater(this, () -> { son(s, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.6f, 0.7f); for (Player p : dedans(s)) p.sendActionBar(Component.text("Les trappes se déverrouillent.", NamedTextColor.GRAY)); }, duree);
    }
    private void ambiance(Salle s) {
        List<Player> l = dedans(s); if (l.isEmpty()) return;
        switch (alea.nextInt(7)) {
            case 0 -> { son(s, Sound.AMBIENT_CAVE, 1f, 0.8f); }
            case 1 -> { son(s, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f); for (Player p : l) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, false)); }
            case 2 -> { for (Player p : l) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, false, false)); son(s, Sound.BLOCK_CONDUIT_AMBIENT, 1f, 0.6f); }
            case 3 -> { for (Player p : l) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, false)); particules(s, Particle.CLOUD, 60, 0.02); }
            case 4 -> { for (Player p : l) p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0, false, false)); particules(s, Particle.END_ROD, 40, 0.05); }
            default -> { }   // le plus souvent : rien, juste le silence et le bruit des portes
        }
    }
    /* ------------------------------------------------------------------ mort, victoire */
    @EventHandler public void onMort(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        String cause = causeMort.remove(p.getUniqueId()); condamnes.remove(p.getUniqueId());   // mort d'autre chose entre-temps : la salle ne retue pas
        e.deathMessage(Component.text(p.getName(), NamedTextColor.WHITE).append(Component.text(" " + (cause != null ? cause : "est mort dans le Cube") + ".", NamedTextColor.GRAY)));
        e.getDrops().clear(); e.setDroppedExp(0); e.setKeepInventory(true);
        morts.merge(p.getUniqueId(), 1, Integer::sum);
        Salle s = ou.get(p.getUniqueId());
        int n = salles(p), rec = Math.max(n, records.getInt(p.getUniqueId() + ".salles_max"));
        hub.envoyer("cube_mort", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"niveau\":" + (s != null ? s.j + 1 : 0) + ",\"salles\":" + n + ",\"salles_max\":" + rec + ",\"cause\":" + Hub.j(cause != null ? cause : "est mort dans le Cube") + "}");
        records.set(p.getUniqueId() + ".morts", records.getInt(p.getUniqueId() + ".morts") + 1);
        finTentative(p);
        if (n > 0) p.sendMessage(Component.text(n + " salle" + (n > 1 ? "s" : "") + " traversée" + (n > 1 ? "s" : "") + " cette fois" + (rec > n ? " (votre record : " + rec + ")" : n == rec && rec > 1 ? " — votre record" : "") + ".", NamedTextColor.GRAY));
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        e.setRespawnLocation(lab.depart.centre(monde));
        surLePont.remove(e.getPlayer().getUniqueId());
        // 24/09/2026 (Maxime : « je réapparais pas vraiment, je suis dans le vide mais pas mort ») : le client affiche parfois
        // le ciel vide après la réapparition. Cause : la réapparition instantanée (gamerule immediate_respawn) ; elle est coupée
        // au démarrage (écran de mort normal, bouton Réapparaître). Ici, on coupe seulement tout effet d'obscurité restant.
        Bukkit.getScheduler().runTaskLater(this, () -> { Player p = e.getPlayer(); if (!p.isOnline() || p.isDead()) return;
            p.removePotionEffect(PotionEffectType.DARKNESS); p.removePotionEffect(PotionEffectType.BLINDNESS);
        }, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> { Player p = e.getPlayer(); for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType()); p.setFireTicks(0);
            p.showTitle(Title.title(Component.text("Le Cube", NamedTextColor.WHITE), Component.text("Vous vous réveillez à nouveau dans la salle blanche.", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1)))); }, 2);
    }
    private void victoire(Player p) {
        long t = debut.containsKey(p.getUniqueId()) ? (System.currentTimeMillis() - debut.get(p.getUniqueId())) / 1000 : 0;
        int m = morts.getOrDefault(p.getUniqueId(), 0);
        String cle = p.getUniqueId().toString();
        long meilleur = records.getLong(cle + ".meilleur", 0);
        if (t > 0 && (meilleur == 0 || t < meilleur)) records.set(cle + ".meilleur", t);
        records.set(cle + ".sorties", records.getInt(cle + ".sorties") + 1);
        final int n = salles(p); finTentative(p); ou.put(p.getUniqueId(), lab.sortie); sauverRecords();   // on reste « dans la sortie » jusqu'au retour : pas de seconde victoire
        hub.envoyer("cube_sortie", "{\"uuid\":" + Hub.j(p.getUniqueId().toString()) + ",\"nom\":" + Hub.j(p.getName()) + ",\"temps\":" + t + ",\"morts\":" + m + ",\"salles\":" + n + ",\"salles_max\":" + records.getInt(cle + ".salles_max") + "}");
        p.showTitle(Title.title(Component.text("VOUS ÊTES SORTI", NamedTextColor.GOLD, TextDecoration.BOLD), Component.text("du Cube en " + duree(t) + (m > 0 ? " · " + m + " mort" + (m > 1 ? "s" : "") : " · sans mourir") + " · " + n + " salle" + (n > 1 ? "s" : ""), NamedTextColor.WHITE), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofSeconds(2))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Bukkit.broadcast(Component.text("✦ ", NamedTextColor.GOLD).append(Component.text(p.getName(), NamedTextColor.WHITE)).append(Component.text(" est sorti du Cube en " + duree(t) + (m > 0 ? " après " + m + " mort" + (m > 1 ? "s" : "") : ", sans mourir") + ".", NamedTextColor.GRAY)));
        for (int q = 0; q < 4; q++) Bukkit.getScheduler().runTaskLater(this, () -> {
            Firework f = monde.spawn(p.getLocation().add(alea.nextDouble() * 4 - 2, 1, alea.nextDouble() * 4 - 2), Firework.class);
            FireworkMeta fm = f.getFireworkMeta(); fm.addEffect(FireworkEffect.builder().withColor(Color.WHITE, Color.YELLOW).with(FireworkEffect.Type.BALL_LARGE).withTrail().build()); fm.setPower(0); f.setFireworkMeta(fm);
        }, 10L + q * 12);
        morts.remove(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline() && reseau) { surLePont.add(p.getUniqueId()); p.teleport(surLePont()); ou.remove(p.getUniqueId()); visitees.remove(p.getUniqueId()); p.sendMessage(Component.text("Vous voilà dehors. Le pont ramène à Lobbik ; le vestibule blanc, dans le Cube.", NamedTextColor.GRAY)); return; } if (p.isOnline()) { p.teleport(lab.depart.centre(monde)); ou.remove(p.getUniqueId()); visitees.remove(p.getUniqueId()); p.sendMessage(Component.text("Retour au départ. Le Cube vous attend encore : /recommencer à tout moment.", NamedTextColor.GRAY)); } }, 140);
    }
    private static String duree(long s) { return s >= 3600 ? String.format("%dh%02d", s / 3600, (s % 3600) / 60) : String.format("%d:%02d", s / 60, s % 60); }
    private void sauverRecords() { try { records.save(fichierRecords); } catch (Exception e) { getLogger().warning("records : " + e.getMessage()); } }

    /* ------------------------------------------------------------------ accès : membres de Lobbik seulement (comme La Tour) */
    @EventHandler public void onLogin(AsyncPlayerPreLoginEvent e) {
        if (!hub.configure()) return;
        if (!hub.listeChargee()) hub.relireLies();
        if (hub.estLie(e.getUniqueId())) return;
        hub.relireLies();
        if (hub.estLie(e.getUniqueId())) return;
        String code = codes.computeIfAbsent(e.getUniqueId(), this::codePour);
        hub.envoyer("code", "{\"uuid\":" + Hub.j(e.getUniqueId().toString()) + ",\"nom\":" + Hub.j(e.getName()) + ",\"code\":" + Hub.j(code) + "}");
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text("Serveur réservé aux membres de Lobbik · Members-only server\n\nCode : ", NamedTextColor.GOLD)
            .append(Component.text(code, NamedTextColor.AQUA)).append(Component.text("\n\nhttps://lobbik.com → Minecraft → Jouer → « Lier mon compte Minecraft », puis revenez.\nLink your Minecraft account on the site, then come back.", NamedTextColor.GRAY)));
    }
    private String codePour(UUID u) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(getConfig().getString("cle", "krp").getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(u.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(a.charAt((h[i] & 0xff) % a.length()));
            return b.toString();
        } catch (Exception ex) { String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder b = new StringBuilder(); for (int i = 0; i < 6; i++) b.append(a.charAt(alea.nextInt(a.length()))); return b.toString(); }
    }

    /* ------------------------------------------------------------------ arrivée, interface */
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setGameMode(GameMode.ADVENTURE);
        if (reseau && p.hasMetadata("lobbik_pont")) {   // arrivé à pied par le pont : il entre quand il veut, par le vestibule
            e.joinMessage(Component.text(p.getName(), NamedTextColor.WHITE).append(Component.text(" s'approche du Cube.", NamedTextColor.GRAY)));
            ou.remove(p.getUniqueId()); surLePont.add(p.getUniqueId());
            Location ici = retour.remove(p.getUniqueId());
            if (ici != null && lab.salleDe(ici) != null) {   // il avait quitté le jeu dans une salle : retour à cet endroit
                Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) { surLePont.remove(p.getUniqueId()); p.teleport(ici); figer(p, 3); ou.put(p.getUniqueId(), lab.salleDe(ici)); p.sendActionBar(Component.text("De retour là où vous étiez, dans le Cube.", NamedTextColor.GRAY)); } }, 2L);
                return;
            }
            p.sendActionBar(Component.text("Le Cube. Entrez dans la pièce blanche… si vous l'osez.", NamedTextColor.GRAY));
            return;
        }
        long planVu = records.getLong(p.getUniqueId() + ".plan", 0);
        if (lab.salleDe(p.getLocation()) == null || !p.hasPlayedBefore() || planVu != lab.plan.empreinte()) { p.teleport(lab.depart.centre(monde)); records.set(p.getUniqueId() + ".plan", lab.plan.empreinte()); }   // Cube reconstruit depuis : retour au départ
        e.joinMessage(Component.text(p.getName(), NamedTextColor.WHITE).append(Component.text(" se réveille dans le Cube.", NamedTextColor.GRAY)));
        if (livreDonne.add(p.getUniqueId()) && !p.getInventory().contains(Material.WRITTEN_BOOK)) p.getInventory().setItem(8, livre());
        Bukkit.getScheduler().runTaskLater(this, () -> p.showTitle(Title.title(Component.text("Le Cube", NamedTextColor.WHITE, TextDecoration.BOLD),
            Component.text("Une pièce. Six issues. Le Cube ne pardonne pas.", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(800), Duration.ofSeconds(4), Duration.ofSeconds(1)))), 20);
        int rec = records.getInt(p.getUniqueId() + ".salles_max");
        p.sendMessage(Component.text("Le Cube — " + lab.salles.size() + " salles. Quelque part, une sortie. Certaines salles tuent, par quelque porte qu'on y entre, et rien ne les distingue. On compte les salles traversées" + (rec > 0 ? " (votre record : " + rec + ")" : "") + ". Le livre dans votre barre raconte le reste.", NamedTextColor.GRAY));
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(Component.text(e.getPlayer().getName(), NamedTextColor.WHITE).append(Component.text(" a quitté le Cube.", NamedTextColor.GRAY)));
        // réseau : parti du jeu depuis une salle → la porte du Cube, au hub, l'y ramènera (parti par le pont → rien à retenir)
        if (reseau) { Location l = e.getPlayer().getLocation(); if (!surLePont.contains(e.getPlayer().getUniqueId()) && lab.salleDe(l) != null && !e.getPlayer().isDead()) retour.put(e.getPlayer().getUniqueId(), l.clone()); else retour.remove(e.getPlayer().getUniqueId()); }
        ou.remove(e.getPlayer().getUniqueId()); surLePont.remove(e.getPlayer().getUniqueId());
    }
    private final Map<UUID, Location> retour = new java.util.concurrent.ConcurrentHashMap<>();
    private ItemStack livre() {
        ItemStack it = new ItemStack(Material.WRITTEN_BOOK); BookMeta m = (BookMeta) it.getItemMeta();
        m.title(Component.text("Le Cube")); m.author(Component.text("Inconnu"));
        m.addPages(
            Component.text("Vous vous réveillez dans une pièce cubique. Aucun souvenir d'y être entré.\n\nSix issues : quatre portes, une trappe au plafond, une trappe au sol. Derrière chacune, une autre pièce, identique ou presque."),
            Component.text("Toutes les portes s'ouvrent. Mais certaines pièces tuent, par quelque porte qu'on y entre : lames, gaz, flammes, acide, plafond qui s'abat. Une fois dedans, il est trop tard, même pour ressortir."),
            Component.text("Rien ne les distingue : ni la couleur, ni la lumière. Les pièces sûres se suivent, montent, redescendent, tournent, et finissent souvent en cul-de-sac. Le Cube ne change jamais : ce que vous apprenez en mourant reste vrai à la tentative suivante."),
            Component.text("Cliquez sur les portes et les trappes pour les ouvrir. L'échelle du milieu mène au plafond ; la trappe à son pied mène en bas.\n\nOn compte les pièces traversées. Quelque part, une pièce dorée : la sortie. On ne se soigne que dans la pièce blanche. Mourir vous y renvoie."));
        it.setItemMeta(m); return it;
    }
    private void barre() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Salle s = ou.get(p.getUniqueId()); if (s == null) continue;
            long t = debut.containsKey(p.getUniqueId()) ? (System.currentTimeMillis() - debut.get(p.getUniqueId())) / 1000 : 0;
            int n = salles(p);
            Component c = Component.text(n + " salle" + (n > 1 ? "s" : ""), NamedTextColor.WHITE).append(Component.text("  ·  " + duree(t), NamedTextColor.GRAY));
            int m = morts.getOrDefault(p.getUniqueId(), 0); if (m > 0) c = c.append(Component.text("  ·  " + m + " mort" + (m > 1 ? "s" : ""), NamedTextColor.RED));
            p.sendActionBar(c);
        }
    }

    /* ------------------------------------------------------------------ les trappes s'ouvrent au clic (Maxime : « c'est à moi de cliquer ») */
    @EventHandler public void onClic(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;   // l'événement arrive deux fois (main principale + main secondaire) : sinon la porte s'ouvre puis se referme aussitôt
        Block b = e.getClickedBlock(); Material m = b.getType();
        if (reseau && m == Material.POLISHED_BLACKSTONE_BUTTON && estBoutonSortie(b)) { sortirParLeBouton(e.getPlayer()); return; }
        if (m != Material.IRON_DOOR && m != Material.IRON_TRAPDOOR && m != Material.LADDER) return;
        int[] is = lab.issueDe(b.getX(), b.getY(), b.getZ());
        if (is == null && m == Material.IRON_DOOR) is = lab.issueDe(b.getX(), b.getY() - 1, b.getZ());   // battant du haut
        if (is == null) return;
        e.setCancelled(true);
        Salle s = lab.salles.get(is[0]); int d = is[1]; Player p = e.getPlayer();
        Integer fin = scelleeJusquA.get(s.id); Salle v = lab.voisin(s, d); Integer finV = v != null ? scelleeJusquA.get(v.id) : null;
        if ((fin != null && fin > tic) || (finV != null && finV > tic)) { p.playSound(b.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1f, 0.5f); p.sendActionBar(Component.text("Scellée.", NamedTextColor.RED)); return; }
        long cle = ((long) s.id << 3) | d, maintenant = System.currentTimeMillis();
        Long dernier = _bascules.get(cle); if (dernier != null && maintenant - dernier < 400) return;
        _bascules.put(cle, maintenant);
        boolean ouverte = lab.ouverte(monde, s, d);
        lab.basculer(monde, s, d, !ouverte);
        p.playSound(b.getLocation(), ouverte ? Sound.BLOCK_IRON_DOOR_CLOSE : Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.7f);
    }

    /* ------------------------------------------------------------------ les salles mortelles */
    private boolean joueurVulnerable(Player p) { return p.getWorld() == monde && (p.getGameMode() == GameMode.ADVENTURE || p.getGameMode() == GameMode.SURVIVAL) && !p.isDead(); }
    /** On vient d'entrer dans une salle mortelle, par n'importe quelle porte : portes fermées, scellée, un grondement, ce qui va tuer
     *  se montre, puis la mort 1,5 s plus tard. Systématique : ressortir pendant ce temps ne sauve pas (la porte se referme sur lui).
     *  Une seule mise à mort par joueur (jeton) ; s'il meurt d'autre chose entre-temps, onMort retire le jeton et rien ne se passe. */
    private void salleFatale(Player p, Salle s) {
        UUID u = p.getUniqueId(); Object jeton = new Object(); condamnes.put(u, jeton);
        Piege maniere = s.piege == Piege.AUCUN ? Piege.LAMES : s.piege;
        scelleeJusquA.put(s.id, tic + 80);
        lab.ouvrir(monde, s, false);
        for (Player q : joueursDans(s)) { q.playSound(q.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.AMBIENT, 1f, 0.5f); q.playSound(q.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.AMBIENT, 1f, 0.5f); q.playSound(q.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 1f, 0.8f); }
        Bukkit.getScheduler().runTaskLater(this, () -> { if (condamnes.get(u) == jeton && p.isOnline() && !p.isDead()) presage(p, maniere); }, 12);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!condamnes.remove(u, jeton)) return;                                  // déjà mort d'autre chose : on ne tue pas deux fois
            if (!p.isOnline() || p.isDead() || p.getWorld() != monde) return;          // déconnecté : il revient dans la salle, elle le reprend
            boolean ressorti = lab.salleDe(p.getLocation()) != s;
            causeMort.put(u, ressorti ? "a voulu ressortir : la porte s'est refermée sur lui" : switch (maniere) {
                case GAZ -> "a respiré le gaz du Cube"; case FLAMMES -> "a brûlé dans le Cube"; case ACIDE -> "s'est dissous dans l'acide du Cube";
                case CHUTE -> "a été écrasé par le plafond du Cube"; default -> "a été découpé en tranches par le Cube"; });
            coupFatal(p, ressorti ? null : maniere);
            p.setHealth(0);
        }, 30);
    }
    /** Ce qui va tuer se montre (0,6 s après l'entrée) : autour du joueur seulement. */
    private void presage(Player p, Piege m) {
        Location l = p.getLocation(); double x = l.getX(), y = l.getY() + 1, z = l.getZ();
        switch (m) {
            case GAZ -> { p.playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 0.5f); monde.spawnParticle(Particle.SNEEZE, x, y, z, 200, 2.5, 1.5, 2.5, 0.05); monde.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, x, y, z, 60, 2.5, 1.5, 2.5, 0.02);
                          p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, false, false)); p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false)); }
            case FLAMMES -> { p.playSound(l, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.7f); monde.spawnParticle(Particle.FLAME, x, y, z, 300, 2.5, 1.5, 2.5, 0.1); p.setFireTicks(60); }
            case ACIDE -> { p.playSound(l, Sound.BLOCK_LAVA_EXTINGUISH, 1f, 0.6f); monde.spawnParticle(Particle.SMOKE, x, l.getY() + 0.2, z, 200, 2.5, 0.2, 2.5, 0.03); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 3, false, false)); }
            case CHUTE -> { p.playSound(l, Sound.BLOCK_PISTON_EXTEND, 1f, 0.5f); p.playSound(l, Sound.BLOCK_GRINDSTONE_USE, 1f, 0.5f); monde.spawnParticle(Particle.BLOCK, x, y + 2.5, z, 60, 2.5, 0.3, 2.5, 0.02, Material.GRAY_CONCRETE.createBlockData()); }
            default -> { p.playSound(l, Sound.BLOCK_GRINDSTONE_USE, 1f, 0.6f); monde.spawnParticle(Particle.ELECTRIC_SPARK, x, y, z, 120, 2.5, 1.5, 2.5, 0.2); }
        }
    }
    /** Le coup fatal, entendu par les joueurs proches ; m = null : la porte s'est refermée sur celui qui ressortait. */
    private void coupFatal(Player p, Piege m) {
        Location l = p.getLocation(); double x = l.getX(), y = l.getY() + 1, z = l.getZ();
        Sound s1 = m == null ? Sound.BLOCK_IRON_DOOR_CLOSE : m == Piege.FLAMMES ? Sound.ENTITY_BLAZE_SHOOT : m == Piege.GAZ ? Sound.BLOCK_FIRE_EXTINGUISH : m == Piege.ACIDE ? Sound.BLOCK_LAVA_EXTINGUISH : m == Piege.CHUTE ? Sound.BLOCK_PISTON_CONTRACT : Sound.ENTITY_PLAYER_ATTACK_SWEEP;
        for (Player q : Bukkit.getOnlinePlayers()) if (q.getWorld() == monde && q.getLocation().distanceSquared(l) < 900) { q.playSound(l, s1, 1f, 0.5f); q.playSound(l, Sound.BLOCK_ANVIL_LAND, 1f, 0.5f); q.playSound(l, Sound.ENTITY_PLAYER_HURT, 1f, 0.8f); }
        if (m == null || m == Piege.LAMES) { monde.spawnParticle(Particle.SWEEP_ATTACK, x, y, z, 8, 0.6, 0.6, 0.6, 0); monde.spawnParticle(Particle.CRIT, x, y, z, 80, 0.6, 0.8, 0.6, 0.4); }
        else if (m == Piege.FLAMMES) monde.spawnParticle(Particle.LAVA, x, y, z, 30, 0.6, 0.6, 0.6, 0);
        else if (m == Piege.CHUTE) monde.spawnParticle(Particle.BLOCK, x, y + 1, z, 80, 0.8, 1.0, 0.8, 0.1, Material.GRAY_CONCRETE.createBlockData());
        monde.spawnParticle(Particle.BLOCK, x, y, z, 50, 0.6, 0.7, 0.6, 0.1, Material.REDSTONE_BLOCK.createBlockData());
    }

    /* ------------------------------------------------------------------ ambiance : le Cube respire, gronde, tremble (Maxime : « une ambiance qui fait un peu peur ») */
    /** Toutes les secondes : à chaque joueur dans le Cube son prochain frisson, toutes les 40 à 120 s, jamais dans la salle blanche. Rien ne blesse, rien ne pousse. */
    /* Salles « à moitié éteintes » (Maxime, 23/09/2026) : environ une salle sur sept a un éclairage défaillant. Tant qu'un joueur
       s'y trouve, ses lumières invisibles baissent par à-coups (grésillements courts), et parfois s'éteignent presque 2 à 4 s.
       Choix déterministe (graine + id) : la même salle clignote toujours ; jamais le départ ni la sortie. */
    private final Map<Integer, Integer> penombre = new HashMap<>();   // salle → ticks (×2) restants avant retour à la pleine lumière
    /** Salles sombres (Maxime, 23/09/2026 : « des salles mal éclairées, sombres, d'un seul côté ») : une salle sur six environ,
     *  une seule lumière faible dans un coin, les trois autres éteintes. Posé au démarrage, fixe. */
    private boolean sombre(Salle s) { if (s.depart || s.sortie || defaillante(s)) return false; long h = (s.id * 0xC2B2AE3D27D4EB4FL) ^ (lab.graine * 131); h ^= (h >>> 31); return Math.floorMod(h, 6) == 0; }
    private void poserPenombres() {
        int n = 0; int[][] coins = {{2, 2}, {6, 2}, {2, 6}, {6, 6}};
        for (Salle s : lab.salles) {
            if (!sombre(s)) continue; n++;
            int garde = Math.floorMod(s.id * 7 + (int) lab.graine, 4);
            for (int c = 0; c < 4; c++) {
                org.bukkit.block.Block b = monde.getBlockAt(s.ox() + coins[c][0], s.oy() + 6, s.oz() + coins[c][1]);
                if (b.getType() != Material.LIGHT) continue;
                org.bukkit.block.data.type.Light l = (org.bukkit.block.data.type.Light) b.getBlockData(); l.setLevel(c == garde ? 8 : 0); b.setBlockData(l, false);
            }
        }
        getLogger().info("Salles sombres : " + n);
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
    private void jouerBoite(Player p) {
        long t = System.currentTimeMillis(); Long d = derniereBoite.get(p.getUniqueId());
        if (d != null && t - d < 120_000) return;                                   // jamais deux mélodies à moins de 2 min
        if (alea.nextInt(3) != 0) return;                                           // une fois sur trois seulement
        derniereBoite.put(p.getUniqueId(), t);
        int[] m = MELODIES[alea.nextInt(MELODIES.length)]; Sound inst = alea.nextBoolean() ? Sound.BLOCK_NOTE_BLOCK_CHIME : Sound.BLOCK_NOTE_BLOCK_BELL;
        float desaccord = 0.97f + alea.nextFloat() * 0.02f;
        for (int n = 0; n < m.length; n++) { if (m[n] < 0) continue; int k = n; float h = (float) (Math.pow(2, (m[k] - 12) / 12.0) * desaccord);
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline() && !p.isDead()) p.playSound(p.getLocation(), inst, SoundCategory.AMBIENT, 0.35f, Math.max(0.5f, Math.min(2f, h))); }, 16L * k); }
    }
    private boolean defaillante(Salle s) { if (s.depart || s.sortie) return false; long h = (s.id * 0x9E3779B97F4A7C15L) ^ (lab.graine * 31); h ^= (h >>> 29); return Math.floorMod(h, 7) == 0; }
    private void lumieres(Salle s, int niveau) {
        for (int[] c : new int[][]{{2, 2}, {6, 6}}) {   // deux lumières sur quatre : l'effet reste, deux fois moins de recalcul
            org.bukkit.block.Block b = monde.getBlockAt(s.ox() + c[0], s.oy() + 6, s.oz() + c[1]);
            if (b.getType() != Material.LIGHT) continue;
            org.bukkit.block.data.type.Light l = (org.bukkit.block.data.type.Light) b.getBlockData(); l.setLevel(niveau); b.setBlockData(l, false);
        }
    }
    private void clignoter() {
        Set<Integer> occupees = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) { if (p.getWorld() != monde) continue; Salle s = ou.get(p.getUniqueId()); if (s != null && defaillante(s)) occupees.add(s.id); }
        // salles quittées : on rallume
        for (Integer id : new ArrayList<>(penombre.keySet())) if (!occupees.contains(id)) { lumieres(lab.salles.get(id), 15); penombre.remove(id); }
        for (int id : occupees) {
            Salle s = lab.salles.get(id); Integer r = penombre.get(id);
            if (r != null && r > 0) { if (r == 1) { lumieres(s, 15); penombre.put(id, 0); } else penombre.put(id, r - 1); continue; }
            double t = alea.nextDouble();
            if (t < 0.012) {                                  // panne : presque noir 2 à 4 s
                lumieres(s, 1); penombre.put(id, 20 + alea.nextInt(20));
                for (Player p : joueursDans(s)) p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.AMBIENT, 0.35f, 0.6f);
            } else if (t < 0.035) {                           // grésillement : baisse brève (rare : chaque changement de lumière recalcule l'éclairage chez le client)
                lumieres(s, 2 + alea.nextInt(6)); penombre.put(id, 1 + alea.nextInt(3));
                if (alea.nextInt(3) == 0) for (Player p : joueursDans(s)) p.playSound(p.getLocation(), Sound.BLOCK_REDSTONE_TORCH_BURNOUT, SoundCategory.AMBIENT, 0.12f, 1.6f + alea.nextFloat() * 0.3f);
            }
        }
    }
    private List<Player> joueursDans(Salle s) { List<Player> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) if (ou.get(p.getUniqueId()) == s) l.add(p); return l; }
    /* Musique étrange (Maxime, 23/09/2026 : « des musiques plus étranges, dans les caves et autre ») : par joueur, un morceau
       sombre tiré au hasard, joué bas et légèrement ralenti, puis un silence de 40 à 90 s avant le suivant. Jamais dans la salle blanche. */
    // 24/09/2026 (Maxime : « pas les sons du jeu normal, que des bruits de vent, du silence ») : plus aucune musique de Minecraft.
    // Des nappes d'ambiance seulement — vent (le souffle de l'élytre, grave), bouches d'aération (deltas de basalte), plainte sourde
    // (vallée des âmes), son étouffé (sous l'eau), cavernes — jouées bas, et de longs silences entre elles.
    private static final Sound[] MUSIQUES = { Sound.ITEM_ELYTRA_FLYING, Sound.ITEM_ELYTRA_FLYING, Sound.AMBIENT_BASALT_DELTAS_LOOP,
        Sound.AMBIENT_SOUL_SAND_VALLEY_LOOP, Sound.AMBIENT_UNDERWATER_LOOP, Sound.AMBIENT_CAVE, Sound.AMBIENT_WARPED_FOREST_LOOP };
    private final Map<UUID, Long> prochaineMusique = new HashMap<>();
    private final Map<UUID, Sound> musiqueEnCours = new HashMap<>();
    private void musique() {
        long t = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() == monde) p.stopSound(SoundCategory.MUSIC);   // jamais la musique habituelle de Minecraft dans le Cube
            UUID u = p.getUniqueId(); Salle s = ou.get(u);
            if (p.getWorld() != monde || s == null || s.depart) { Sound m = musiqueEnCours.remove(u); if (m != null) p.stopSound(m, SoundCategory.AMBIENT); prochaineMusique.remove(u); continue; }
            Long pro = prochaineMusique.get(u);
            if (pro == null) { prochaineMusique.put(u, t + 8_000 + alea.nextInt(12_000)); continue; }
            if (t < pro) continue;
            Sound ancien = musiqueEnCours.remove(u); if (ancien != null) p.stopSound(ancien, SoundCategory.AMBIENT);
            Sound m = MUSIQUES[alea.nextInt(MUSIQUES.length)];
            float hauteur = 0.7f + alea.nextFloat() * 0.25f;          // ralenti : plus grave, plus inquiétant
            p.playSound(p.getLocation(), m, SoundCategory.AMBIENT, m == Sound.ITEM_ELYTRA_FLYING ? 0.18f : 0.35f, m == Sound.ITEM_ELYTRA_FLYING ? 0.5f : hauteur);
            musiqueEnCours.put(u, m);
            int duree = 40 + alea.nextInt(50);                        // on coupe au bout de 70 à 130 s, puis silence
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline() && musiqueEnCours.get(u) == m) { p.stopSound(m, SoundCategory.AMBIENT); musiqueEnCours.remove(u); } }, 20L * duree);
            prochaineMusique.put(u, t + (duree + 30 + alea.nextInt(60)) * 1000L);   // puis du silence
        }
    }
    private void frissons() {
        long maintenant = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() != monde) continue;
            Salle s = ou.get(p.getUniqueId());
            if (s == null || s.depart) { prochainFrisson.remove(p.getUniqueId()); continue; }
            Long prochain = prochainFrisson.get(p.getUniqueId());
            if (prochain == null) { prochainFrisson.put(p.getUniqueId(), maintenant + 30_000 + alea.nextInt(60_000)); continue; }
            if (maintenant < prochain) continue;
            prochainFrisson.put(p.getUniqueId(), maintenant + 30_000 + alea.nextInt(60_000));
            frisson(p, s);
        }
    }
    private void frisson(Player p, Salle s) {
        Location l = p.getLocation(); int tirage = alea.nextInt(100);
        // 24/09/2026 (Maxime : « parfois des bruits, des tremblements, des choses étranges ») : un tiers des frissons tirés parmi
        // de nouveaux bruits, joués DERRIÈRE le joueur (à 3 blocs dans son dos) pour qu'il se retourne.
        if (alea.nextInt(3) == 0) { etrange(p, s); return; }
        if (tirage < 28) p.playSound(l, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 0.7f, 0.6f + alea.nextFloat() * 0.5f);                       // le souffle des cavernes
        else if (tirage < 42) p.playSound(l, Sound.BLOCK_CONDUIT_AMBIENT, SoundCategory.AMBIENT, 0.6f, 0.5f);                                       // bourdonnement sourd
        else if (tirage < 56) { for (int n = 0; n < 4; n++) { int k = n; Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 0.9f, 0.8f + k * 0.05f); }, n * 18L); } }   // un cœur qui bat, quelque part
        else if (tirage < 66) p.playSound(l, Sound.ENTITY_WARDEN_NEARBY_CLOSER, SoundCategory.AMBIENT, 0.5f, 0.7f);                                 // quelque chose approche
        else if (tirage < 73) p.playSound(l, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.AMBIENT, 0.2f, 0.6f);                                 // un cri, très loin
        else if (tirage < 78) p.playSound(l, Sound.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 0.25f, 0.4f);                                        // une rafale de vent, très grave
        else tremblement(p, s);
    }
    private void etrange(Player p, Salle s) {
        Location l = p.getLocation(), dos = l.clone().subtract(l.getDirection().setY(0).normalize().multiply(3));
        switch (alea.nextInt(7)) {
            case 0 -> { for (int n = 0; n < 4; n++) { int k = n; Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.playSound(dos, Sound.BLOCK_STONE_STEP, SoundCategory.AMBIENT, 0.55f, 0.7f); }, 6L * k); } }   // des pas, derrière
            case 1 -> p.playSound(dos, Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.AMBIENT, 0.45f, 0.5f);                    // une porte claque, quelque part
            case 2 -> p.playSound(dos, Sound.BLOCK_CHAIN_PLACE, SoundCategory.AMBIENT, 0.6f, 0.5f);                          // des chaînes
            case 3 -> p.playSound(l, Sound.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS, SoundCategory.AMBIENT, 0.7f, 0.6f);          // un murmure
            case 4 -> p.playSound(dos, Sound.ENTITY_GHAST_SCREAM, SoundCategory.AMBIENT, 0.12f, 0.5f);                     // un cri très lointain
            case 5 -> { p.playSound(l, Sound.BLOCK_GRINDSTONE_USE, SoundCategory.AMBIENT, 0.5f, 0.5f);                        // un mécanisme se met en marche dans les murs
                        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_PISTON_EXTEND, SoundCategory.AMBIENT, 0.6f, 0.5f); }, 25L); }
            default -> { p.playSound(l, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.AMBIENT, 1f, 0.5f); p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false)); }   // la lumière faiblit un instant
        }
    }
    /** Le Cube tremble : grondement, secousses de caméra (animation de coup, sans dégât ni déplacement), poussière qui tombe du plafond, craquements. */
    private void tremblement(Player p, Salle s) {
        Location l = p.getLocation();
        p.playSound(l, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT, 0.35f, 0.5f);
        p.playSound(l, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.AMBIENT, 0.8f, 0.5f);
        int secousses = 5 + alea.nextInt(5);
        for (int n = 0; n < secousses; n++) { int k = n; Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || p.isDead()) return;
            p.playHurtAnimation(alea.nextFloat() * 360f - 180f);
            Location c = s.centre(monde);
            monde.spawnParticle(Particle.BLOCK, c.getX(), c.getY() + 6.5, c.getZ(), 25, 3.0, 0.3, 3.0, 0.02, Labyrinthe.LAINE[s.couleur].createBlockData());
            monde.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c.getX(), c.getY() + 6.5, c.getZ(), 6, 3.0, 0.2, 3.0, 0.005);
            if (k % 3 == 0) p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, SoundCategory.AMBIENT, 0.6f, 0.4f + alea.nextFloat() * 0.3f);
            if (k == secousses - 1) p.playSound(p.getLocation(), Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.AMBIENT, 0.5f, 0.4f);
        }, 4L + n * 4); }
    }

    /* ------------------------------------------------------------------ rien ne se casse */
    @EventHandler public void onCasse(BlockBreakEvent e) { if (!e.getPlayer().isOp()) e.setCancelled(true); }
    @EventHandler public void onPose(BlockPlaceEvent e) { if (!e.getPlayer().isOp()) e.setCancelled(true); }
    @EventHandler public void onFaim(FoodLevelChangeEvent e) { e.setCancelled(true); }
    @EventHandler public void onDegatsSol(EntityDamageEvent e) {
        // le sol d'acide (magma) ne blesse pas qui s'accroupit : c'est le jeu ; les dégâts de chute restent (une trappe, ça se descend à l'échelle)
        if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR && p.isSneaking()) e.setCancelled(true);
    }

    /* ------------------------------------------------------------------ commandes */
    /** Maxime (23/09/2026) : « seul moi, Kripy, peut le faire ». Les sous-commandes d'admin ne dépendent PAS du statut op :
     *  seulement la console / RCON et les UUID listés sous « admins » dans config.yml (par défaut : croziors). */
    private boolean estAdmin(CommandSender c) {
        if (!(c instanceof Player p)) return c instanceof ConsoleCommandSender || c instanceof RemoteConsoleCommandSender;
        for (String u : getConfig().getStringList("admins")) if (u.trim().equalsIgnoreCase(p.getUniqueId().toString())) return true;
        return false;
    }
    private static final Set<String> SOUS_ADMIN = Set.of("porte", "ou", "ouvrir", "solution", "regenerer");
    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equals("recommencer")) {
            if (!(sender instanceof Player p)) return true;
            p.teleport(lab.depart.centre(monde)); finTentative(p); morts.remove(p.getUniqueId());
            for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType()); p.setFireTicks(0); p.setHealth(20);
            p.sendMessage(Component.text("Nouvelle tentative. Le chronomètre repart quand vous quittez la salle blanche.", NamedTextColor.GRAY));
            return true;
        }
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "info";
        boolean admin = estAdmin(sender);
        if (!admin && !a.equals("info") && !a.equals("top") && !a.equals("loin")) { sender.sendMessage(Component.translatable("command.unknown.command", NamedTextColor.RED)); return true; }   // pour les autres, ces sous-commandes n'existent pas
        if (a.equals("loin")) {
            List<Map.Entry<String, Integer>> l = new ArrayList<>();
            for (String k : records.getKeys(false)) { int b = records.getInt(k + ".salles_max", 0); if (b > 0) l.add(Map.entry(records.getString(k + ".nom", "?"), b)); }
            l.sort((u, v) -> v.getValue() - u.getValue());
            sender.sendMessage(Component.text("Le plus loin dans le Cube (salles traversées en une tentative) :", NamedTextColor.GOLD));
            int n = 0; for (Map.Entry<String, Integer> e : l) { if (++n > 10) break; sender.sendMessage(Component.text(n + ". " + e.getKey() + " — " + e.getValue() + " salles", NamedTextColor.WHITE)); }
            if (l.isEmpty()) sender.sendMessage(Component.text("Personne n'a encore quitté la salle blanche.", NamedTextColor.GRAY));
            return true;
        }
        if (a.equals("top")) {
            List<Map.Entry<String, Long>> l = new ArrayList<>();
            for (String k : records.getKeys(false)) { long b = records.getLong(k + ".meilleur", 0); if (b > 0) l.add(Map.entry(records.getString(k + ".nom", "?"), b)); }
            l.sort(Map.Entry.comparingByValue());
            sender.sendMessage(Component.text("Sortis du Cube, les plus rapides :", NamedTextColor.GOLD));
            int n = 0; for (Map.Entry<String, Long> e : l) { if (++n > 10) break; sender.sendMessage(Component.text(n + ". " + e.getKey() + " — " + duree(e.getValue()), NamedTextColor.WHITE)); }
            if (l.isEmpty()) sender.sendMessage(Component.text("Personne n'est encore sorti.", NamedTextColor.GRAY));
            return true;
        }
        if (a.equals("regenerer") && admin) {
            construire(); Bukkit.getScheduler().runTaskAsynchronously(this, this::envoyerCarte);
            sender.sendMessage(Component.text("Cube reconstruit (même plan : pour un autre labyrinthe, changer « graine » dans config.yml et redémarrer).", NamedTextColor.GREEN)); return true;
        }
        if (a.equals("porte")) { cmdPorte(sender, args); return true; }
        if (a.equals("ou")) { cmdOu(sender, args); return true; }
        if (a.equals("ouvrir")) { cmdOuvrir(sender, args); return true; }
        sender.sendMessage(Component.text("Le Cube : " + lab.salles.size() + " salles (" + lab.nx + " × " + lab.ny + " niveaux × " + lab.nz + "), " + lab.nbMortelles() + " mortelles, graine " + lab.graine + ". /cube top · /cube loin", NamedTextColor.GRAY));
        if (admin) sender.sendMessage(Component.text("Admin : /cube porte [montrer] · /cube ou [joueur] · /cube ouvrir [joueur] [chemin|tout] · /cube solution · /cube regenerer", NamedTextColor.DARK_AQUA));
        if (admin && (a.equals("info") || a.equals("solution"))) sender.sendMessage(Component.text(lab.plan.resume(), NamedTextColor.DARK_GRAY));
        if (admin && a.equals("solution")) {
            StringBuilder b = new StringBuilder("Chemin : ");
            for (int c : lab.plan.chemin) { Salle s = lab.salles.get(c); b.append(s.coord()).append(' '); }
            sender.sendMessage(Component.text(b.toString(), NamedTextColor.DARK_GRAY));
        }
        return true;
    }

    /* ------------------------------------------------------------------ commandes op (Maxime, 23/09/2026) */
    private Salle salleDuJoueur(Player p) { Salle s = ou.get(p.getUniqueId()); return s != null ? s : lab.salleDe(p.getLocation()); }
    /** /cube porte [montrer] : dans la salle de l'op, quelle porte mène à la sortie par le plus court chemin, lesquelles tuent. */
    private void cmdPorte(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("À utiliser en jeu.", NamedTextColor.RED)); return; }
        Salle s = salleDuJoueur(p);
        if (s == null) { p.sendMessage(Component.text("Vous n'êtes dans aucune salle (mur, porte ou hors du Cube).", NamedTextColor.RED)); return; }
        int ici = lab.plan.distanceSortie(s.id);
        p.sendMessage(Component.text("Salle " + s.code + " " + s.coord() + (s.sure ? (s.sortie ? " — c'est la sortie." : " — sûre, sortie à " + ici + " salle" + (ici > 1 ? "s" : "") + ".") : " — MORTELLE."), s.sure ? NamedTextColor.GREEN : NamedTextColor.RED));
        List<Integer> bonnes = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            Salle v = lab.voisin(s, d); String dir = Labyrinthe.DIRECTIONS[d];
            if (v == null) { p.sendMessage(Component.text("  " + dir + " : le vide (bord du Cube)", NamedTextColor.DARK_GRAY)); continue; }
            int dv = lab.plan.distanceSortie(v.id);
            boolean bonne = v.sure && dv >= 0 && (ici < 0 ? false : dv == ici - 1);
            if (bonne) bonnes.add(d);
            p.sendMessage(Component.text("  " + dir + " → " + v.code + " : " + (!v.sure ? "MORTELLE" : bonne ? "vers la SORTIE (" + dv + " salle" + (dv > 1 ? "s" : "") + " restante" + (dv > 1 ? "s" : "") + ")" : v.sortie ? "la SORTIE" : "sûre (sortie à " + dv + ")"),
                !v.sure ? NamedTextColor.RED : bonne ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("montrer")) {
            if (bonnes.isEmpty()) { p.sendMessage(Component.text("Aucune porte à montrer.", NamedTextColor.GRAY)); return; }
            final int[] n = {0};
            Bukkit.getScheduler().runTaskTimer(this, t -> {   // particules envoyées à ce joueur seul, 10 s
                if (++n[0] > 20 || !p.isOnline()) { t.cancel(); return; }
                for (int d : bonnes) { double[] c = centreIssue(s, d); p.spawnParticle(Particle.HAPPY_VILLAGER, c[0], c[1], c[2], 12, 0.35, 0.5, 0.35, 0); }
            }, 0, 10);
            p.sendMessage(Component.text("La bonne porte est surlignée 10 s, pour vous seul.", NamedTextColor.GRAY));
        }
    }
    private double[] centreIssue(Salle s, int d) {
        return switch (d) {
            case 0 -> new double[]{s.ox() + 8.5, s.oy() + 4, s.oz() + 4.5}; case 1 -> new double[]{s.ox() + 0.5, s.oy() + 4, s.oz() + 4.5};
            case 2 -> new double[]{s.ox() + 4.5, s.oy() + 4, s.oz() + 8.5}; case 3 -> new double[]{s.ox() + 4.5, s.oy() + 4, s.oz() + 0.5};
            case 4 -> new double[]{s.ox() + 4.5, s.oy() + 7.5, s.oz() + 4.5}; default -> new double[]{s.ox() + 4.5, s.oy() + 1.2, s.oz() + 4.5};
        };
    }
    /** /cube ou [joueur] : où en est chaque joueur du Cube. */
    private void cmdOu(CommandSender sender, String[] args) {
        List<Player> l = new ArrayList<>();
        if (args.length > 1) { Player q = Bukkit.getPlayerExact(args[1]); if (q == null) { sender.sendMessage(Component.text("Joueur introuvable : " + args[1], NamedTextColor.RED)); return; } l.add(q); }
        else for (Player q : Bukkit.getOnlinePlayers()) if (q.getWorld() == monde) l.add(q);
        if (l.isEmpty()) { sender.sendMessage(Component.text("Personne dans le Cube.", NamedTextColor.GRAY)); return; }
        for (Player q : l) {
            Salle s = salleDuJoueur(q);
            if (s == null) { sender.sendMessage(Component.text(q.getName() + " : entre deux salles (porte, mur) ou hors du Cube" + (q.isDead() ? ", mort" : "") + ".", NamedTextColor.GRAY)); continue; }
            Integer ds = derniereSure.get(q.getUniqueId()); int ref = s.sure ? s.id : ds != null ? ds : -1;
            int reste = lab.plan.distanceSortie(ref), av = lab.plan.avance(ref);
            sender.sendMessage(Component.text(q.getName() + " : niveau " + (s.j + 1) + ", salle " + s.coord() + " " + s.code + (s.sure ? "" : " (MORTELLE)") + (q.isDead() ? ", mort" : "") + " · " + salles(q) + " salle" + (salles(q) > 1 ? "s" : "") + " traversée" + (salles(q) > 1 ? "s" : "")
                + " · avancement " + av + " % · " + (reste >= 0 ? reste + " salle" + (reste > 1 ? "s" : "") + " jusqu'à la sortie" : "distance à la sortie inconnue"), s.sure ? NamedTextColor.WHITE : NamedTextColor.RED));
        }
    }
    /** /cube ouvrir [joueur] [chemin|tout] : ouvre des issues de la salle de ce joueur (ou de l'admin), même scellée.
     *  Sans option : les portes vers des salles sûres ; « chemin » : seulement celle du plus court chemin vers la sortie (depuis une
     *  salle mortelle : la salle sûre voisine la plus proche de la sortie) ; « tout » : toutes, bord compris. */
    private void cmdOuvrir(CommandSender sender, String[] args) {
        Player q = null; String mode = "sures";
        for (int i = 1; i < args.length; i++) {
            String x = args[i].toLowerCase(Locale.ROOT);
            if (x.equals("chemin") || x.equals("tout")) mode = x;
            else if (x.equals("sure") || x.equals("sûre") || x.equals("sures")) mode = "sures";
            else { q = Bukkit.getPlayerExact(args[i]); if (q == null) { sender.sendMessage(Component.text("Joueur introuvable : " + args[i], NamedTextColor.RED)); return; } }
        }
        if (q == null) { if (sender instanceof Player p) q = p; else { sender.sendMessage(Component.text("Préciser un joueur : /cube ouvrir <joueur> [chemin|tout]", NamedTextColor.RED)); return; } }
        Salle s = salleDuJoueur(q);
        if (s == null) { sender.sendMessage(Component.text(q.getName() + " n'est dans aucune salle.", NamedTextColor.RED)); return; }
        boolean[] ouvrir = new boolean[6];
        if (mode.equals("chemin")) {
            int meilleure = -1, meilleureDist = Integer.MAX_VALUE;
            for (int d = 0; d < 6; d++) { Salle v = lab.voisin(s, d); if (v == null || !v.sure) continue; int dv = lab.plan.distanceSortie(v.id); if (dv >= 0 && dv < meilleureDist) { meilleureDist = dv; meilleure = d; } }
            int ici = lab.plan.distanceSortie(s.id);
            if (s.sortie) { sender.sendMessage(Component.text(q.getName() + " est déjà dans la sortie.", NamedTextColor.GRAY)); return; }
            if (meilleure < 0 || (ici >= 0 && meilleureDist >= ici)) { sender.sendMessage(Component.text("Aucune porte ne rapproche " + q.getName() + " de la sortie depuis " + s.code + " " + s.coord() + ".", NamedTextColor.RED)); return; }
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
        for (Player r : dedans(s)) { r.playSound(r.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.7f); r.playSound(r.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 0.7f); }
        String quoi = mode.equals("chemin") ? "porte du plus court chemin" : mode.equals("tout") ? "toutes les issues" : "issues vers des salles sûres";
        sender.sendMessage(Component.text("Salle " + s.code + " " + s.coord() + " de " + q.getName() + (s.sure ? "" : " (MORTELLE)") + " — " + quoi + " : " + n + " ouverte" + (n > 1 ? "s" : "") + (n > 0 ? " (" + dirs + ")" : "") + ", scellement levé.", NamedTextColor.GREEN));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equals("cube")) return List.of();
        List<String> choix = new ArrayList<>();
        boolean admin = estAdmin(sender);
        if (args.length == 1) { choix.addAll(List.of("info", "top", "loin")); if (admin) choix.addAll(SOUS_ADMIN); }
        else if (admin && args.length == 2 && args[0].equalsIgnoreCase("porte")) choix.add("montrer");
        else if (admin && args.length == 2 && (args[0].equalsIgnoreCase("ou") || args[0].equalsIgnoreCase("ouvrir"))) { for (Player q : Bukkit.getOnlinePlayers()) choix.add(q.getName()); if (args[0].equalsIgnoreCase("ouvrir")) choix.addAll(List.of("chemin", "tout")); }
        else if (admin && args.length == 3 && args[0].equalsIgnoreCase("ouvrir")) choix.addAll(List.of("chemin", "tout"));
        String debutMot = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
        choix.removeIf(c -> !c.toLowerCase(Locale.ROOT).startsWith(debutMot));
        return choix;
    }
}
