package hk.krp.lobbik;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Panneaux lumineux du hub (port en cours) : pour l'instant, le dégradé Volt du titre LOBBIK. */
public final class Panneaux {
    private Panneaux() { }
    /** Dégradé vert électrique → cyan (identité Volt de Lobbik), lettre par lettre, net (sans ombre). */
    public static Component degrade(String s) {
        MutableComponent c = Component.empty();
        for (int i = 0; i < s.length(); i++) {
            float t = s.length() <= 1 ? 0 : i / (float) (s.length() - 1);
            int r = (int) (0x7C + (0x3F - 0x7C) * t), g = (int) (0xFF + (0xE0 - 0xFF) * t), b = (int) (0x4F + (0xFF - 0x4F) * t);
            c.append(Component.literal(String.valueOf(s.charAt(i))).withStyle(Style.EMPTY.withColor(TextColor.fromRgb((r << 16) | (g << 8) | b)).withBold(true)));
        }
        return c;
    }
}
