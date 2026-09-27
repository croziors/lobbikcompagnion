package hk.krp.lobbik.mixin;

import hk.krp.lobbik.cube.Cube;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Le Cube : on réapparaît toujours dans la salle blanche, pile au pied de l'échelle (le point d'apparition du monde, lui, serait
 *  « ajusté » par le jeu vers un endroit à ciel ouvert). Sans effet quand le Cube n'est pas actif (Cube.I nul). */
@Mixin(ServerPlayer.class)
public abstract class CubeReapparitionMixin {
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private void lobbik$reapparitionCube(boolean consumeSpawnBlock, TeleportTransition.PostTeleportTransition apres, CallbackInfoReturnable<TeleportTransition> cir) {
        TeleportTransition t = Cube.reapparition((ServerPlayer) (Object) this, apres);
        if (t != null) cir.setReturnValue(t);
    }
}
