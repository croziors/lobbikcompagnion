package hk.krp.lobbik;

import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;

/** Petits outils communs : textes colorés, titres, barre d'action, sons, téléportation. */
public final class Outils {
    private Outils() { }
    public static MutableComponent t(String s, int rgb) { return Component.literal(s).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))); }
    public static MutableComponent tg(String s, int rgb) { return Component.literal(s).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true)); }
    public static final int BLANC = 0xFFFFFF, GRIS = 0xAAAAAA, GRIS_FONCE = 0x555555, OR = 0xFFAA00, JAUNE = 0xFFFF55, ROUGE = 0xFF5555, VERT = 0x55FF55, AQUA = 0x55FFFF,
        VOLT = 0xC6F500, CYAN = 0x00E5FF, TOUR = 0xFFB347, CUBE = 0x5AB4FF;

    public static void titre(ServerPlayer p, Component titre, Component sous, int entreeT, int resteT, int sortieT) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(entreeT, resteT, sortieT));
        p.connection.send(new ClientboundSetSubtitleTextPacket(sous));
        p.connection.send(new ClientboundSetTitleTextPacket(titre));
    }
    public static void action(ServerPlayer p, Component c) { p.sendSystemMessage(c, true); }
    public static void son(ServerPlayer p, Holder<SoundEvent> s, float vol, float hauteur) {
        p.connection.send(new ClientboundSoundPacket(s, SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), vol, hauteur, p.getRandom().nextLong()));
    }
    public static void son(ServerPlayer p, SoundEvent s, float vol, float hauteur) { son(p, net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(s), vol, hauteur); }
    public static void teleporter(ServerPlayer p, double x, double y, double z, float yaw, float pitch) {
        p.teleportTo(p.level(), x, y, z, Set.<Relative>of(), yaw, pitch, true);
    }
    public static void teleporter(ServerPlayer p, Geo.Lieu l) { teleporter(p, l.x(), l.y(), l.z(), l.yaw(), l.pitch()); }
}
