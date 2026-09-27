package hk.krp.lobbik;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.DedicatedServerModInitializer;
import hk.krp.lobbik.lave.Lave;
import hk.krp.lobbik.tour.Tour;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod serveur « lobbik » (27/09/2026) : le réseau Minecraft de Lobbik en Fabric (hub, La Tour, Le Cube), pour que CC: Tweaked et
 * les mods Macaw's soient partout. Même principe que les plugins Paper : un rôle par serveur (config/lobbik.properties).
 */
public final class Lobbik implements DedicatedServerModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("lobbik");
    private static final String VERSION_DECOR = "fabric-2";   // 2 : pont sud et porte de la Mer de lave (27/09/2026)
    private static Config cfg;
    private static final Repliques REPLIQUES = new Repliques();   // les autres jeux, vus de loin

    @Override public void onInitializeServer() {
        cfg = new Config(FabricLoader.getInstance().getConfigDir().resolve("lobbik.properties"));
        String role = cfg.s("role", "hub");
        // chaque serveur tire ses numéros d'entité dans sa plage (hub 10 M, tour 30 M, cube 50 M), décalée à chaque démarrage
        int base = switch (role) { case "tour" -> 30_000_000; case "cube" -> 50_000_000; case "lave" -> 70_000_000; default -> 10_000_000; };
        Billets.decalerCompteur(base + (int) ((System.currentTimeMillis() / 1000) % 4_000_000) * 4);
        try { Billets.ouvrir(cfg.i("port_controle", 25580), cfg.s("cle_reseau", "")); }
        catch (Exception e) { LOG.error("Port de contrôle indisponible : {} — les passages seront ordinaires", e.toString()); }

        PayloadTypeRegistry.serverboundPlay().register(Canal.TYPE, Canal.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Canal.TYPE, Canal.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Canal.TYPE, (charge, ctx) -> { if (Reseau.I != null) Reseau.I.recevoir(ctx.player(), charge.data()); });

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            Reseau r = new Reseau(s, cfg);
            Path marque = FabricLoader.getInstance().getConfigDir().resolve("lobbik-decor-" + role + "-" + VERSION_DECOR + ".ok");
            if (!Files.exists(marque)) {
                long t0 = System.currentTimeMillis();
                LOG.info("Construction du décor commun…");
                new Decor(s.overworld()).toutCommun();
                try { Files.writeString(marque, "1"); } catch (Exception ignored) { }
                LOG.info("Décor construit en {} s.", (System.currentTimeMillis() - t0) / 1000);
            }
            r.demarrer();
            if (cfg.b("repliques", true)) REPLIQUES.planifier(s.overworld(), cfg, role);
            if ("tour".equals(role)) new Tour(s, cfg).demarrer();
            if ("lave".equals(role)) new Lave(s, cfg).demarrer();   // Mer de lave (27/09/2026)
            LOG.info("Lobbik prêt — rôle {}", role);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { Billets.fermer(); if (Tour.I != null) Tour.I.arreter(); if (Lave.I != null) Lave.I.arreter(); });
        ServerTickEvents.END_SERVER_TICK.register(s -> { REPLIQUES.tic(); if (Reseau.I != null) Reseau.I.tic(); if (Tour.I != null) Tour.I.tic(); if (Lave.I != null) Lave.I.tic(); });
        ServerPlayConnectionEvents.JOIN.register((h, envoi, s) -> { if (Reseau.I != null) Reseau.I.rejoint(h.getPlayer()); if (Tour.I != null) Tour.I.rejoint(h.getPlayer()); if (Lave.I != null) Lave.I.rejoint(h.getPlayer()); });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> { if (Tour.I != null) Tour.I.quitte(h.getPlayer()); if (Lave.I != null) Lave.I.quitte(h.getPlayer()); if (Reseau.I != null) Reseau.I.quitte(h.getPlayer()); });
        ServerPlayerEvents.AFTER_RESPAWN.register((ancien, nouveau, finDuMonde) -> { if (Tour.I != null) Tour.I.reapparu(nouveau); if (Lave.I != null) Lave.I.reapparu(nouveau); });
        // aucun dégât pour les joueurs sur le hub et la Tour (le Cube gère les siens)
        if (!"cube".equals(role)) ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, n) -> !(e instanceof ServerPlayer));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((msg, joueur, params) -> Reseau.I == null || Reseau.I.chat(joueur, msg.signedContent()));
        CommandRegistrationCallback.EVENT.register((d, contexte, selection) -> {
            for (String nom : new String[]{"lobbik", "hub", "lobby"})
                d.register(Commands.literal(nom).executes(c -> { ServerPlayer p = c.getSource().getPlayerOrException(); return Reseau.I.cmdLobbik(p); }));
            d.register(Commands.literal("file")
                .executes(c -> Reseau.I.cmdFile(c.getSource().getPlayerOrException(), ""))
                .then(Commands.argument("cible", StringArgumentType.word()).executes(c -> Reseau.I.cmdFile(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "cible")))));
            if ("lave".equals(role)) {
                d.register(Commands.literal("cp").executes(c -> Lave.I.cmdCp(c.getSource().getPlayerOrException())));
                d.register(Commands.literal("depart").executes(c -> Lave.I.cmdDepart(c.getSource().getPlayerOrException())));
                d.register(Commands.literal("recommencer").executes(c -> Lave.I.cmdDepart(c.getSource().getPlayerOrException())));
                d.register(Commands.literal("top").executes(c -> Lave.I.cmdTop(c.getSource().getPlayerOrException())));
                d.register(Commands.literal("lave").executes(c -> { c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(Lave.I.infos())); return 1; }));
            }
            if ("tour".equals(role)) {
                d.register(Commands.literal("cp").executes(c -> Tour.I.cmdCp(c.getSource().getPlayerOrException())));
                d.register(Commands.literal("niveau").executes(c -> Tour.I.cmdNiveau(c.getSource().getPlayerOrException())));
                d.register(Commands.literal("top").executes(c -> Tour.I.cmdTop(c.getSource().getPlayerOrException())));
                d.register(Commands.literal("tour").executes(c -> { c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal(Tour.I.infos())); return 1; })
                    .then(Commands.literal("regenerer").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(c -> { Tour.I.regenerer(); c.getSource().sendSystemMessage(net.minecraft.network.chat.Component.literal("Tour reconstruite.")); return 1; })));
            }
        });
        if ("cube".equals(role)) hk.krp.lobbik.cube.Cube.brancher();   // le jeu du Cube : ses propres événements, après ceux du réseau
        if ("hub".equals(role)) Ordinateurs.brancher(cfg);            // écrans CC: Tweaked du hub (après le décor), 27/09/2026
    }
}
