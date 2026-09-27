package hk.krp.lobbik;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Textes flottants (TextDisplay) posés par le mod, marqués d'une étiquette pour être retirés au démarrage suivant. */
public final class Affichages {
    private Affichages() { }
    public static Display.TextDisplay texte(ServerLevel w, String etiquette, double x, double y, double z, float echelle, Display.BillboardConstraints bb, float lacet, Component c) {
        Display.TextDisplay t = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, w);
        t.snapTo(x, y, z, lacet, 0f);
        t.setBillboardConstraints(bb); t.setText(c); t.setBackgroundColor(0); t.setLineWidth(300);
        t.setBrightnessOverride(new Brightness(15, 15));
        t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(echelle, echelle, echelle), new Quaternionf()));
        t.addTag(etiquette);
        w.addFreshEntity(t);
        return t;
    }
    public static void nettoyer(ServerLevel w, String etiquette) {
        List<Entity> l = new ArrayList<>();
        for (Entity e : w.getAllEntities()) if (e.entityTags().contains(etiquette)) l.add(e);
        for (Entity e : l) e.discard();
    }
}
