package hk.krp.lobbik;

import com.mojang.math.Transformation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Brightness;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Les portes du hub, vues par chaque joueur séparément (Kripy, 27/09/2026) : mur de verre vert (on passe), rouge (plein → file
 * d'attente), doré (c'est ton tour, pour toi seul). Le mur est un affichage envoyé à ce joueur seulement (entité factice, jamais
 * dans le monde) ; quand c'est fermé, ce joueur reçoit aussi des blocs barrière invisibles ; le serveur vérifie au franchissement.
 */
public final class Portes {
    enum Etat { LIBRE, PLEIN, ATTENTE, OUVERT, VISITEUR }

    /** Une porte du hub. axeZ = false : sur le pont est-ouest (La Tour, Le Cube, porte à x = pos) ; axeZ = true : pont sud de la
        Mer de lave (porte à z = pos, pont centré sur x = centre). sens : +1 si l'on franchit la porte en allant vers les x (ou z) croissants. */
    final class Porte {
        final String cible, nom; final boolean tour, axeZ; final int x, pos, centre, sens, couleur; final List<int[]> chemin; Display.TextDisplay titre;
        Porte(String cible, String nom, boolean tour) { this.cible = cible; this.nom = nom; this.tour = tour; this.axeZ = false; this.x = tour ? Geo.PORTE_TOUR_X : Geo.PORTE_CUBE_X;
            this.pos = x; this.centre = 0; this.sens = tour ? 1 : -1; this.couleur = tour ? Outils.TOUR : Outils.CUBE; this.chemin = Decor.chemin(tour); }
        Porte(String cible, String nom, int posZ, int centreX, int couleur) { this.cible = cible; this.nom = nom; this.tour = false; this.axeZ = true; this.x = centreX;
            this.pos = posZ; this.centre = centreX; this.sens = 1; this.couleur = couleur; this.chemin = List.of(); }
        int sens() { return sens; }
        /** coordonnée le long du pont (x ou z) */
        double le(double x, double z) { return axeZ ? z : x; }
        boolean surPont(double x, double z) { return axeZ ? Geo.surPontLave(x, z) : Geo.surPont(x, z); }
        double titreX() { return axeZ ? centre + 0.5 : pos + 0.5; }
        double titreZ() { return axeZ ? pos + 0.5 : 0.5; }
    }
    /** mur factice envoyé à un joueur : un numéro d'entité par porte, pris très haut pour ne jamais croiser les vrais */
    private record Mur(int id, UUID uuid) { }

    private final Reseau r;
    final Porte tour, cube, lave;
    private final Map<UUID, Map<String, String>> etats = new ConcurrentHashMap<>();
    private volatile Map<String, String> commun = Map.of();
    private final Map<UUID, ServerBossEvent> barres = new ConcurrentHashMap<>();
    private final Map<UUID, String> fermes = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Mur>> murs = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Etat>> vus = new ConcurrentHashMap<>();
    private static int prochainId = 2_000_000_000;

    Portes(Reseau r) { this.r = r; tour = new Porte("tour", "La Tour", true); cube = new Porte("cube", "Le Cube", false);
        lave = new Porte("lave", "Mer de lave", Geo.PORTE_LAVE_Z, Geo.LAVE_X, 0xFF7A1A); }
    List<Porte> toutes() { return List.of(tour, cube, lave); }
    Porte porte(String c) { return c.equals("tour") ? tour : c.equals("cube") ? cube : c.equals("lave") ? lave : null; }

    void creer() {
        Affichages.nettoyer(r.monde, "lobbik_porte");
        for (Porte p : toutes()) p.titre = Affichages.texte(r.monde, "lobbik_porte", p.titreX(), Geo.PORTE_HAUT + 3.4, p.titreZ(), 1.6f, Display.BillboardConstraints.CENTER, 0f, Component.literal(p.nom));
    }

    /* ------------------------------------------------------------------ état reçu du proxy */
    void etat(ServerPlayer p, Map<String, String> m) {
        Map<String, String> e = new HashMap<>();
        for (Porte po : toutes()) if (m.containsKey(po.cible)) e.put(po.cible, m.get(po.cible));
        etats.put(p.getUUID(), e);
        Map<String, String> c = new HashMap<>();
        for (String k : m.keySet()) if (k.equals("hub") || k.startsWith("nb_")) c.put(k, m.get(k));
        commun = c;
    }
    Map<String, String> commun() { return commun; }
    void oublier(ServerPlayer p) {
        etats.remove(p.getUUID()); fermes.remove(p.getUUID()); murs.remove(p.getUUID()); vus.remove(p.getUUID());
        ServerBossEvent b = barres.remove(p.getUUID()); if (b != null) b.removePlayer(p);
    }
    Etat etatDe(ServerPlayer p, Porte po) {
        if (!r.estMembre(p)) return Etat.VISITEUR;
        Map<String, String> e = etats.get(p.getUUID());
        String v = e == null ? null : e.get(po.cible);
        if (v == null) return Etat.PLEIN;
        return switch (v.charAt(0)) { case 'L' -> Etat.LIBRE; case 'O' -> Etat.OUVERT; case 'A' -> Etat.ATTENTE; default -> Etat.PLEIN; };
    }
    int[] details(ServerPlayer p, Porte po) {
        Map<String, String> e = etats.get(p.getUUID()); String v = e == null ? null : e.get(po.cible);
        if (v == null) return new int[]{0, 0, 0};
        String[] t = v.split(":");
        try { return new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])}; } catch (Exception ex) { return new int[]{0, 0, 0}; }
    }

    /* ------------------------------------------------------------------ rendu, deux fois par seconde */
    void rendre() {
        for (ServerPlayer p : r.serveur.getPlayerList().getPlayers()) {
            StringBuilder ferme = new StringBuilder();
            BossEvent.BossBarColor couleur = null; Component texte = null; float progres = 1f;
            Map<String, Etat> dejaVu = vus.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
            for (Porte po : toutes()) {
                Etat e = etatDe(p, po);
                if (dejaVu.get(po.cible) != e) { dejaVu.put(po.cible, e); montrerMur(p, po, e); }
                if (e != Etat.LIBRE && e != Etat.OUVERT) ferme.append(po.cible).append(',');
                int[] dt = details(p, po);
                if (e == Etat.OUVERT) {
                    couleur = BossEvent.BossBarColor.YELLOW; progres = Math.min(1f, dt[2] / (float) r.ouverture());
                    texte = Outils.tg("C'est ton tour ! La porte de " + po.nom + " est ouverte pour toi · " + dt[2] + " s", Outils.OR);
                } else if (e == Etat.ATTENTE && couleur == null) {
                    couleur = BossEvent.BossBarColor.RED; progres = dt[1] <= 0 ? 0f : Math.max(0.02f, 1f - (dt[0] - 1) / (float) dt[1]);
                    texte = Outils.t("File d'attente · " + po.nom + " : tu es " + (dt[0] == 1 ? "le prochain" : dt[0] + "e") + " sur " + dt[1], Outils.BLANC);
                    if (!po.chemin.isEmpty()) { int[] c = po.chemin.get(Math.min(Math.max(dt[0] - 1, 0), po.chemin.size() - 1));
                        r.monde.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, false, true, c[0] + 0.5, Geo.Y + 0.3, c[1] + 0.5, 4, 0.25, 0.15, 0.25, 0); }
                }
            }
            ServerBossEvent b = barres.get(p.getUUID());
            if (texte != null) {
                if (b == null) { b = new ServerBossEvent(UUID.randomUUID(), texte, couleur, BossEvent.BossBarOverlay.PROGRESS); b.addPlayer(p); barres.put(p.getUUID(), b); }
                b.setName(texte); b.setProgress(progres); b.setColor(couleur);
            } else if (b != null) { b.removePlayer(p); barres.remove(p.getUUID()); }
            String f = ferme.toString();
            if (!f.equals(fermes.get(p.getUUID())) || (System.currentTimeMillis() / 500) % 4 == 0) {
                fermes.put(p.getUUID(), f);
                for (Porte po : toutes()) {
                    BlockState etatBloc = (f.contains(po.cible + ",") ? Blocks.BARRIER : Blocks.AIR).defaultBlockState();
                    for (int y = Geo.PORTE_BAS; y <= Geo.PORTE_HAUT; y++) for (int k = -Geo.PONT_DEMI; k <= Geo.PONT_DEMI; k++)
                        p.connection.send(new ClientboundBlockUpdatePacket(po.axeZ ? new BlockPos(po.centre + k, y, po.pos) : new BlockPos(po.pos, y, k), etatBloc));
                }
            }
        }
        Map<String, String> c = commun;
        for (Porte po : toutes()) {
            if (po.titre == null) continue;
            String v = c.get("nb_" + po.cible);
            Component t = Outils.tg(po.nom.toUpperCase(Locale.ROOT), po.couleur);
            if (v != null) {
                String[] n = v.split("/");
                boolean plein = n.length >= 2 && Integer.parseInt(n[0]) >= Integer.parseInt(n[1]);
                t = t.copy().append("\n").append(Outils.t(n[0] + " / " + n[1] + " joueurs", plein ? Outils.ROUGE : Outils.VERT));
                if (n.length >= 3 && !n[2].equals("0")) t = t.copy().append("\n").append(Outils.t("File d'attente : " + n[2], Outils.OR));
            }
            po.titre.setText(t);
        }
    }

    /** Le mur coloré de cette porte, pour ce joueur seulement : créé la première fois, recoloré ensuite. */
    private void montrerMur(ServerPlayer p, Porte po, Etat e) {
        Map<String, Mur> m = murs.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        Mur mur = m.get(po.cible);
        boolean neuf = mur == null;
        if (neuf) { synchronized (Portes.class) { mur = new Mur(prochainId++, UUID.randomUUID()); } m.put(po.cible, mur); }
        BlockState verre = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(switch (e) { case LIBRE -> "lime_stained_glass"; case OUVERT -> "yellow_stained_glass"; default -> "red_stained_glass"; })).defaultBlockState();
        Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, r.monde);
        d.setId(mur.id()); d.setBlockState(verre); d.setBrightnessOverride(new Brightness(15, 15));
        float h = Geo.PORTE_HAUT - Geo.PORTE_BAS + 1, l = 2 * Geo.PONT_DEMI + 1;
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), po.axeZ ? new Vector3f(l, h, 0.16f) : new Vector3f(0.16f, h, l), new Quaternionf()));
        if (neuf) p.connection.send(po.axeZ ? new ClientboundAddEntityPacket(mur.id(), mur.uuid(), po.centre - Geo.PONT_DEMI, Geo.PORTE_BAS, po.pos + 0.42, 0f, 0f, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0)
                                            : new ClientboundAddEntityPacket(mur.id(), mur.uuid(), po.x + 0.42, Geo.PORTE_BAS, -Geo.PONT_DEMI, 0f, 0f, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
        List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> donnees = d.getEntityData().getNonDefaultValues();
        if (donnees != null) p.connection.send(new ClientboundSetEntityDataPacket(mur.id(), donnees));
    }
    /** Après un passage sans coupure, le proxy a effacé les murs factices chez le client : on les renverra. */
    void murAEnvoyer(ServerPlayer p) { murs.remove(p.getUUID()); vus.remove(p.getUUID()); }

    void tonTour(ServerPlayer p, String cible, int secondes) {
        Porte po = porte(cible); if (po == null) return;
        Outils.titre(p, Outils.tg("C'est ton tour !", Outils.OR), Outils.t("La porte de " + po.nom + " s'ouvre pour toi · " + secondes + " s", Outils.JAUNE), 6, 60, 12);
        Outils.son(p, SoundEvents.NOTE_BLOCK_CHIME, 1f, 1.2f);
    }
    void perdu(ServerPlayer p, String cible) {
        Porte po = porte(cible); if (po == null) return;
        p.sendSystemMessage(Outils.t("Ton tour pour " + po.nom + " est passé : la porte s'est refermée. Touche le mur pour reprendre une place.", Outils.GRIS));
    }
}
