package hk.krp.lobbik.mixin;

import hk.krp.lobbik.cube.Cube;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Le Cube : « Kripy a respiré le gaz du Cube. » au lieu du message vanilla, dans le tchat ET sur l'écran de mort (comme
 *  PlayerDeathEvent.deathMessage du plugin). Sans effet quand le Cube n'est pas actif. */
@Mixin(CombatTracker.class)
public abstract class CubeMessageMortMixin {
    @Shadow @Final private LivingEntity mob;

    @Inject(method = "getDeathMessage", at = @At("HEAD"), cancellable = true)
    private void lobbik$messageCube(CallbackInfoReturnable<Component> cir) {
        Component c = Cube.messageMort(this.mob);
        if (c != null) cir.setReturnValue(c);
    }
}
