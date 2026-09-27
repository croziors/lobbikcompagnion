package hk.krp.lobbik.mixin;

import hk.krp.lobbik.Billets;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Arrivée par le pont : numéro d'entité et position du billet, avant le paquet d'entrée en jeu (qui porte le numéro). */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void lobbik$arrivee(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        Billets.arrivee(player);
    }
}
