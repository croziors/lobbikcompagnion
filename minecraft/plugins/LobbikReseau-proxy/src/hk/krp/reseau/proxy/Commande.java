package hk.krp.reseau.proxy;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** /reseau [capacite <serveur> <n>] — état des files ; places forcées à chaud pour tester la file (0 = revenir au max-players du serveur). */
final class Commande implements SimpleCommand {
    private final LobbikReseau r;
    Commande(LobbikReseau r) { this.r = r; }

    @Override public void execute(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length == 3 && a[0].equals("capacite")) {
            try { r.capacite(a[1], Integer.parseInt(a[2])); } catch (NumberFormatException e) { inv.source().sendMessage(Component.text("Nombre invalide", NamedTextColor.RED)); return; }
        }
        inv.source().sendMessage(Component.text(r.resume(), NamedTextColor.AQUA));
    }

    @Override public boolean hasPermission(Invocation inv) { return inv.source().hasPermission("lobbik.admin"); }
}
