package hk.krp.lobbik;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Canal lobbik:reseau avec le proxy : une ligne « type\tclé=valeur\t… » en UTF-8, sans préfixe de longueur (comme un message de plugin). */
public record Canal(byte[] data) implements CustomPacketPayload {
    public static final Type<Canal> TYPE = new Type<>(Identifier.fromNamespaceAndPath("lobbik", "reseau"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Canal> CODEC = StreamCodec.of(
        (buf, c) -> buf.writeBytes(c.data),
        buf -> { byte[] b = new byte[buf.readableBytes()]; buf.readBytes(b); return new Canal(b); });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
