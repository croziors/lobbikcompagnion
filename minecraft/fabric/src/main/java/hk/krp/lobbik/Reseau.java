package hk.krp.lobbik;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.GameType;

/**
 * Le réseau (même code sur les trois serveurs, rôle hub / tour / cube) : franchir une porte → demande de passage au proxy,
 * messages du proxy (état des portes, « c'est ton tour », refus), chutes dans le vide, chat commun, membres et visiteurs.
 * Port Fabric du plugin Paper KrpReseau (27/09/2026).
 */
public final class Reseau {
    public static Reseau I;
    public final MinecraftServer serveur; public final ServerLevel monde; public final Config cfg; public final String role; public final Hub site;
    public Portes portes;
    private final Map<UUID, double[]> derniere = new ConcurrentHashMap<>();
    private final Map<UUID, Long> enPassage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fileDemandee = new ConcurrentHashMap<>();
    private final Set<UUID> visiteursPrevenus = ConcurrentHashMap.newKeySet();
    private long tic;

    Reseau(MinecraftServer s, Config cfg) {
        this.serveur = s; this.monde = s.overworld(); this.cfg = cfg; this.role = cfg.s("role", "hub");
        this.site = new Hub(cfg.s("site", "https://lobbik.com"), cfg.s("cle_site", ""), cfg.b("envoi_site", true), Lobbik.LOG);
        I = this;
    }
    public boolean hub() { return "hub".equals(role); }
    int ouverture() { return cfg.i("ouverture", 45); }

    void commande(String c) { serveur.getCommands().performPrefixedCommand(serveur.createCommandSourceStack().withSuppressedOutput(), c); }

    void demarrer() {
        commande("gamerule advance_time false"); commande("gamerule advance_weather false");
        commande("time set " + cfg.l("heure", 18000)); commande("weather clear");
        if (hub()) {
            for (String g : new String[]{"spawn_mobs false", "spawn_monsters false", "fall_damage false", "show_advancement_messages false", "immediate_respawn true"}) commande("gamerule " + g);
            commande("setworldspawn " + (Geo.HUB_X) + " " + Geo.Y + " " + (Geo.HUB_Z + 18));
            portes = new Portes(this); portes.creer();
            new Thread(site::relireLies, "lobbik-membres").start();
        }
    }

    /* ================================================================== chaque tick */
    void tic() {
        tic++;
        for (ServerPlayer p : serveur.getPlayerList().getPlayers()) {
            double[] a = derniere.get(p.getUUID());
            double x = p.getX(), y = p.getY(), z = p.getZ();
            derniere.put(p.getUUID(), new double[]{x, y, z, p.getYRot(), p.getXRot()});
            if (a == null) continue;
            if (y < 40) { tombe(p); continue; }
            if (Math.abs(a[0] - x) < 1e-4 && Math.abs(a[2] - z) < 1e-4) continue;
            mouvement(p, a, x, y, z);
        }
        if (tic % 10 == 0 && portes != null) portes.rendre();
        if (tic % 20 == 0) { long n = System.currentTimeMillis(); enPassage.entrySet().removeIf(e -> n - e.getValue() > 8000 && ramener(e.getKey())); }
        if (hub() && tic % 200 == 0) visiteurs();
        if (hub() && tic % 1200 == 0) new Thread(site::relireLies, "lobbik-membres").start();
        if (!hub() && tic % 1200 == 40) new Thread(site::relireClans, "lobbik-clans").start();
        if (tic % 60 == 20 && cfg.b("lire_chat_site", false)) chatSite();   // tags de clan (le hub les relit avec les membres)
    }

    private void mouvement(ServerPlayer p, double[] a, double x, double y, double z) {
        if (hub()) {
            for (Portes.Porte po : portes.toutes()) {
                double g = po.pos + 0.5, c = po.le(x, z), ca = po.le(a[0], a[2]); int s = po.sens();
                boolean franchit = (ca - g) * s < 0 && (c - g) * s >= 0 && po.surPont(x, z) && y < Geo.PORTE_HAUT + 1;
                boolean touche = !franchit && Math.abs(c - g) < 1.4 && (c - g) * s < 0 && po.surPont(x, z);
                boolean dansAllee = !franchit && !touche && dansAllee(po, x, z);
                if (!franchit && !touche && !dansAllee) continue;
                Portes.Etat et = portes.etatDe(p, po);
                if (franchit) {
                    if (et == Portes.Etat.LIBRE || et == Portes.Etat.OUVERT) passage(p, po.cible, x, y, z);
                    else { if (po.axeZ) Outils.teleporter(p, a[0], a[1], a[2] - s * 0.3, p.getYRot(), p.getXRot()); else Outils.teleporter(p, a[0] - s * 0.3, a[1], a[2], p.getYRot(), p.getXRot()); refuser(p, po, et); }
                } else if (et == Portes.Etat.PLEIN) demanderFile(p, po, touche);
                else if (et == Portes.Etat.VISITEUR && touche) refuser(p, po, et);
                return;
            }
        } else if ("lave".equals(role)) {
            // Mer de lave : on rentre au hub en repassant la porte du pont sud vers le nord
            double pz = Geo.PORTE_LAVE_Z + 0.5;
            if (Geo.surPontLave(x, z) && a[2] >= pz && z < pz && y < Geo.PORTE_HAUT + 1) passage(p, cfg.s("hub_nom", "hub"), x, y, z);
        } else if (Geo.surPont(x, z)) {
            int gx = "tour".equals(role) ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X; int s = "tour".equals(role) ? -1 : 1;   // sens de la marche vers le hub
            double px = gx + 0.5;
            if ((a[0] - px) * s < 0 && (x - px) * s >= 0 && y < Geo.PORTE_HAUT + 1) passage(p, cfg.s("hub_nom", "hub"), x, y, z);
        }
    }
    private static boolean dansAllee(Portes.Porte po, double x, double z) {
        if (po.chemin.isEmpty()) return false;   // Mer de lave : pas d'allées (la porte seule met en file)
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int[] ent = Decor.entree(po.tour);
        if (bx == ent[0] && bz == ent[1]) return true;
        for (int[] c : po.chemin) if (c[0] == bx && c[1] == bz) return true;
        return false;
    }
    private void demanderFile(ServerPlayer p, Portes.Porte po, boolean touche) {
        Long t = fileDemandee.get(p.getUUID()); if (t != null && System.currentTimeMillis() - t < 3000) return;
        fileDemandee.put(p.getUUID(), System.currentTimeMillis());
        envoyer(p, "file\tcible=" + po.cible);
        Outils.action(p, Outils.t(po.nom + " est plein : tu es dans la file d'attente, la porte s'ouvrira pour toi.", Outils.OR));
        if (touche) Outils.son(p, SoundEvents.NOTE_BLOCK_BASS, 0.6f, 0.8f);
    }
    private void refuser(ServerPlayer p, Portes.Porte po, Portes.Etat et) {
        Long t = fileDemandee.get(p.getUUID()); if (t != null && System.currentTimeMillis() - t < 1500) return;
        fileDemandee.put(p.getUUID(), System.currentTimeMillis());
        if (et == Portes.Etat.VISITEUR) { Outils.action(p, Outils.t("Liez d'abord votre compte sur lobbik.com (code : " + codePour(p.getUUID()) + ")", Outils.AQUA)); return; }
        if (et == Portes.Etat.PLEIN) envoyer(p, "file\tcible=" + po.cible);
        Outils.action(p, Outils.t(po.nom + " est plein : attends ton tour, le mur deviendra doré pour toi.", Outils.ROUGE));
    }
    /** Demande au proxy de faire passer le joueur, à la position exacte où il franchit la porte. */
    private void passage(ServerPlayer p, String cible, double x, double y, double z) {
        Long t = enPassage.get(p.getUUID()); if (t != null && System.currentTimeMillis() - t < 8000) return;
        enPassage.put(p.getUUID(), System.currentTimeMillis());
        envoyer(p, "passage\tcible=" + cible + "\tx=" + x + "\ty=" + y + "\tz=" + z + "\tyaw=" + p.getYRot() + "\tpitch=" + p.getXRot());
    }
    /** Passage manqué (refus, serveur absent, délai) : on ramène le joueur de ce côté-ci de la porte. */
    private boolean ramener(UUID u) {
        ServerPlayer p = serveur.getPlayerList().getPlayer(u); if (p == null) return true;
        // pont sud de la Mer de lave : on remet le joueur de son côté de la porte
        if (Geo.surPontLave(p.getX(), p.getZ()) && (hub() || "lave".equals(role))) {
            boolean auDela = p.getZ() > Geo.PORTE_LAVE_Z + 0.5;
            if (hub() && auDela) Outils.teleporter(p, p.getX(), Geo.Y, Geo.PORTE_LAVE_Z - 2.5, p.getYRot(), p.getXRot());
            else if (!hub() && !auDela) Outils.teleporter(p, p.getX(), Geo.Y, Geo.PORTE_LAVE_Z + 3.5, p.getYRot(), p.getXRot());
            return true;
        }
        if ("lave".equals(role)) return true;
        int gx, s; double x = p.getX();
        if (hub()) { if (x > Geo.PORTE_TOUR_X) { gx = Geo.PORTE_TOUR_X; s = -1; } else if (x < Geo.PORTE_CUBE_X) { gx = Geo.PORTE_CUBE_X; s = 1; } else return true; }
        else { gx = "tour".equals(role) ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X; s = "tour".equals(role) ? 1 : -1; if ((x - gx - 0.5) * s > 0) return true; }
        double z = Geo.surPont(x, p.getZ()) ? p.getZ() : 0.5;
        Outils.teleporter(p, gx + 0.5 + s * 2.5, Geo.Y, z, p.getYRot(), p.getXRot());
        return true;
    }
    private void tombe(ServerPlayer p) {
        Geo.Lieu l;
        if (hub()) l = Geo.spawn();
        else if ("tour".equals(role)) { if (Math.hypot(p.getX(), p.getZ()) < 60) return; l = Geo.bordTour(); }
        else if ("lave".equals(role)) { if (p.getZ() > Geo.PONT_LAVE_FIN - 6) return; l = Geo.bordLave(); }   // au-dessus de la mer : c'est la Mer de lave qui s'en occupe
        else { if (p.getX() < Geo.CUBE_X0 + Geo.CUBE_COTE + 1 && p.getX() > Geo.CUBE_X0 - 2 && Math.abs(p.getZ()) < 52) return; l = Geo.bordCube(); }
        Outils.teleporter(p, l); p.fallDistance = 0;
        Outils.action(p, Outils.t("Attention au vide !", Outils.GRIS));
    }

    /* ================================================================== arrivée, départ */
    void rejoint(ServerPlayer p) {
        derniere.remove(p.getUUID());
        String depuis = Billets.PAR_LE_PONT.get(p.getUUID());
        if (!hub()) {
            // connexion directe mais posé du mauvais côté de la porte (dans le décor du hub) : on le remet sur son pont
            double x = p.getX();
            if ("lave".equals(role)) { if (depuis == null && p.getZ() < Geo.PORTE_LAVE_Z + 1) serveur.execute(() -> Outils.teleporter(p, Geo.bordLave())); return; }
            if (depuis == null && ("tour".equals(role) ? x < Geo.PORTE_TOUR_X + 1 && x > Geo.PORTE_CUBE_X : x > Geo.PORTE_CUBE_X && x < Geo.PORTE_TOUR_X))
                serveur.execute(() -> Outils.teleporter(p, "tour".equals(role) ? Geo.bordTour() : Geo.bordCube()));
            return;
        }
        p.setGameMode(GameType.ADVENTURE);
        if (portes != null) portes.murAEnvoyer(p);   // murs factices : le proxy les a effacés au passage, on les renvoie
        if (depuis == null) {
            Outils.titre(p, Panneaux.degrade("LOBBIK"), Outils.t("Bienvenue · Welcome · 欢迎", Outils.GRIS), 10, 60, 20);
            for (ServerPlayer q : serveur.getPlayerList().getPlayers()) if (q != p) q.sendSystemMessage(tagClan(p).append(Outils.t(p.getPlainTextName() + " arrive sur Lobbik", Outils.GRIS_FONCE)));
        }
        if (!estMembre(p)) new Thread(() -> { site.relireLies(); serveur.execute(() -> accueilVisiteur(p)); }, "lobbik-visiteur").start();
    }
    void quitte(ServerPlayer p) {
        derniere.remove(p.getUUID()); enPassage.remove(p.getUUID()); fileDemandee.remove(p.getUUID()); Billets.PAR_LE_PONT.remove(p.getUUID());
        if (portes != null) portes.oublier(p);
    }

    /* ================================================================== membres et visiteurs (hub) */
    boolean estMembre(ServerPlayer p) { return !cfg.b("membres_seulement", true) || site.estLie(p.getUUID()); }
    private void accueilVisiteur(ServerPlayer p) {
        if (p.hasDisconnected() || estMembre(p)) return;
        String code = codePour(p.getUUID());
        site.envoyer("code", "{\"uuid\":" + Hub.j(p.getUUID().toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"code\":" + Hub.j(code) + "}");
        p.sendSystemMessage(Outils.t("Bienvenue ! Pour passer les portes et parler, liez votre compte Minecraft à Lobbik :", Outils.OR));
        p.sendSystemMessage(Outils.t("lobbik.com → Minecraft → « Lier mon compte » · code ", Outils.GRIS).append(Outils.tg(code, Outils.AQUA)));
        p.sendSystemMessage(Outils.t("Restez ici : dès que c'est fait, tout s'ouvre, sans vous reconnecter.", Outils.GRIS));
        Outils.titre(p, Outils.tg(code, Outils.AQUA), Outils.t("Votre code de liaison · lobbik.com", Outils.GRIS), 6, 120, 20);
        visiteursPrevenus.add(p.getUUID());
    }
    private void visiteurs() {
        boolean y = false;
        for (ServerPlayer p : serveur.getPlayerList().getPlayers()) if (!site.estLie(p.getUUID())) { y = true; break; }
        if (!y) return;
        new Thread(() -> { site.relireLies(); serveur.execute(() -> {
            for (ServerPlayer p : serveur.getPlayerList().getPlayers()) if (visiteursPrevenus.contains(p.getUUID()) && site.estLie(p.getUUID())) {
                visiteursPrevenus.remove(p.getUUID());
                Outils.titre(p, Outils.tg("Compte lié ✔", Outils.VERT), Outils.t("Les portes vous sont ouvertes", Outils.GRIS), 10, 50, 15);
                Outils.son(p, SoundEvents.PLAYER_LEVELUP, 0.7f, 1.3f);
            }
        }); }, "lobbik-visiteurs").start();
    }
    public String codePour(UUID u) {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(cfg.s("cle_site", "krp").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(u.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(); for (int i = 0; i < 6; i++) s.append(a.charAt((h[i] & 0xff) % a.length())); return s.toString();
        } catch (Exception ex) { SecureRandom r = new SecureRandom(); StringBuilder s = new StringBuilder(); for (int i = 0; i < 6; i++) s.append(a.charAt(r.nextInt(a.length()))); return s.toString(); }
    }

    /* ================================================================== messages avec le proxy */
    void recevoir(ServerPlayer p, byte[] data) {
        Map<String, String> m = lire(new String(data, StandardCharsets.UTF_8));
        switch (m.getOrDefault("", "")) {
            case "etat" -> { if (portes != null) portes.etat(p, m); }
            case "tour" -> { if (portes != null) portes.tonTour(p, m.getOrDefault("cible", "tour"), Integer.parseInt(m.getOrDefault("secondes", "45"))); }
            case "perdu" -> { if (portes != null) portes.perdu(p, m.getOrDefault("cible", "tour")); }
            case "refus" -> {
                enPassage.remove(p.getUUID()); ramener(p.getUUID());
                Outils.action(p, Outils.t("plein".equals(m.get("raison")) ? "C'est plein : tu es dans la file d'attente." : "Ce serveur ne répond pas pour le moment.", Outils.ROUGE));
            }
            default -> { }
        }
    }
    public void envoyer(ServerPlayer p, String ligne) { ServerPlayNetworking.send(p, new Canal(ligne.getBytes(StandardCharsets.UTF_8))); }
    static Map<String, String> lire(String ligne) {
        Map<String, String> m = new HashMap<>(); String[] t = ligne.split("\t"); m.put("", t[0]);
        for (int i = 1; i < t.length; i++) { int k = t[i].indexOf('='); if (k > 0) m.put(t[i].substring(0, k), t[i].substring(k + 1)); }
        return m;
    }

    /* ================================================================== chat commun (toujours en messages système : aucune chaîne de signatures à casser au passage) */
    boolean chat(ServerPlayer p, String texte) {
        texte = texte.trim(); if (texte.isEmpty() || texte.startsWith("/")) return false;
        if (hub() && !estMembre(p)) { p.sendSystemMessage(Outils.t("Liez votre compte sur lobbik.com pour parler (code : " + codePour(p.getUUID()) + ").", Outils.AQUA)); return false; }
        int c; try { c = Integer.parseInt(cfg.s("couleur", "#7CFF4F").replace("#", ""), 16); } catch (Exception e) { c = Outils.VOLT; }
        Component ligne = Outils.t("[" + cfg.s("nom", "Lobbik") + "] ", c).append(tagClan(p)).append(Outils.t(p.getPlainTextName(), Outils.BLANC)).append(Outils.t(" : " + texte, Outils.GRIS));
        for (ServerPlayer q : serveur.getPlayerList().getPlayers()) q.sendSystemMessage(ligne);
        serveur.sendSystemMessage(ligne);
        String[] cl = site.clan(p.getUUID());
        envoyer(p, "chat\ttexte=" + texte.replace('\t', ' ') + (cl != null ? "\ttag=" + cl[0] + "\tcouleur=" + cl[1] : ""));
        if (cfg.b("pont_chat_site", false)) site.envoyer("chat", "{\"uuid\":" + Hub.j(p.getUUID().toString()) + ",\"nom\":" + Hub.j(p.getPlainTextName()) + ",\"message\":" + Hub.j(texte) + "}");
        return false;
    }

    /** Tag de clan « [TAG] » à la couleur du clan (27/09/2026), vide sans clan. */
    net.minecraft.network.chat.MutableComponent tagClan(ServerPlayer p) {
        net.minecraft.network.chat.MutableComponent b = badges(p);
        String[] c = site.clan(p.getUUID()); if (c == null) return b;
        int rgb; try { rgb = Integer.parseInt(c[1].replace("#", ""), 16); } catch (Exception e) { rgb = Outils.VOLT; }
        return b.append(Outils.t("[" + c[0] + "] ", rgb));
    }
    /** Badges de réussite (27/09/2026, Kripy : « aussi sur le chat Minecraft un petit badge ») : ▲ La Tour, ■ Le Cube, ◆ la Mer de lave ;
        les trois = ✦ (Légende du réseau). */
    net.minecraft.network.chat.MutableComponent badges(ServerPlayer p) {
        java.util.Set<String> r = site.reussites(p.getUUID());
        if (r.isEmpty()) return Component.empty();
        if (r.containsAll(java.util.List.of("tour", "cube", "lave"))) return Outils.t("✦ ", Outils.VOLT);
        net.minecraft.network.chat.MutableComponent m = Component.empty();
        if (r.contains("tour")) m.append(Outils.t("▲", Outils.TOUR));
        if (r.contains("cube")) m.append(Outils.t("■", Outils.CUBE));
        if (r.contains("lave")) m.append(Outils.t("◆", 0xFF7A1A));
        return m.append(" ");
    }

    /* ================================================================== salon #minecraft du site → joueurs de ce serveur (jamais de commandes) */
    private volatile long chatDepuis = 0;
    private void chatSite() {
        if (serveur.getPlayerList().getPlayers().isEmpty() && chatDepuis > 0) return;
        new Thread(() -> {
            String corps = site.lireChat(chatDepuis); if (corps == null) return;
            java.util.regex.Matcher md = java.util.regex.Pattern.compile("\"dernier\":(\\d+)").matcher(corps);
            if (md.find()) { long d = Long.parseLong(md.group(1)); if (chatDepuis == 0) { chatDepuis = d; return; } chatDepuis = d; }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\"id\":(\\d+),\"nick\":\"((?:[^\"\\\\]|\\\\.)*)\",\"texte\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}").matcher(corps);
            List<Component> l = new ArrayList<>();
            while (m.find()) l.add(Outils.t("[Site] ", 0x3FE0FF).append(Outils.t(dej(m.group(2)), Outils.BLANC)).append(Outils.t(" : " + dej(m.group(3)), Outils.GRIS)));
            if (!l.isEmpty()) serveur.execute(() -> { for (ServerPlayer q : serveur.getPlayerList().getPlayers()) for (Component c : l) q.sendSystemMessage(c); });
        }, "lobbik-chat-site").start();
    }
    private static String dej(String s) { return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ").replace("\\\\", "\\"); }

    /* ================================================================== commandes */
    int cmdLobbik(ServerPlayer p) {
        if (hub()) { Outils.teleporter(p, Geo.spawn()); Outils.action(p, Outils.t("Retour à l'arrivée", Outils.GRIS)); }
        else { envoyer(p, "aller\tcible=" + cfg.s("hub_nom", "hub")); Outils.action(p, Outils.t("Retour à Lobbik…", Outils.GRIS)); }
        return 1;
    }
    int cmdFile(ServerPlayer p, String arg) {
        if (!hub()) { p.sendSystemMessage(Component.literal("La file d'attente se fait sur Lobbik.")); return 1; }
        if (arg.equals("quitter")) { for (Portes.Porte po : portes.toutes()) envoyer(p, "quitter\tcible=" + po.cible); p.sendSystemMessage(Outils.t("Tu as quitté les files d'attente.", Outils.GRIS)); return 1; }
        Portes.Porte po = portes.porte(arg.isEmpty() ? "tour" : arg);
        if (po == null) { p.sendSystemMessage(Component.literal("/file tour · /file cube · /file quitter")); return 1; }
        if (!estMembre(p)) { p.sendSystemMessage(Outils.t("Liez d'abord votre compte (code : " + codePour(p.getUUID()) + ")", Outils.AQUA)); return 1; }
        envoyer(p, "file\tcible=" + po.cible); p.sendSystemMessage(Outils.t("Te voilà dans la file pour " + po.nom + ".", Outils.OR));
        return 1;
    }
}
