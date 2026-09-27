package hk.krp.lobbik.tour;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Les textes du plugin dans les cinq langues du Hub, choisis selon la langue du client du joueur. */
public final class Textes {
    private static final Map<String, Map<String, String>> T = new HashMap<>();
    private static void d(String cle, String fr, String en, String es, String de, String zh) { T.put(cle, Map.of("fr", fr, "en", en, "es", es, "de", de, "zh", zh)); }
    static {
        d("retour_pos", "§7Vous reprenez exactement là où vous aviez quitté.", "§7You're back exactly where you left off.", "§7Vuelves exactamente donde lo dejaste.", "§7Du machst genau dort weiter, wo du aufgehört hast.", "§7你回到了上次离开的位置。");
        d("aide", "§6La Tour du Lobbik§r — grimpez de cube en cube. §e/cp§r : retour au dernier point de réapparition · §e/niveau§r · §e/top§r",
                  "§6The Lobbik Tower§r — climb from cube to cube. §e/cp§r: back to your last respawn point · §e/niveau§r · §e/top§r",
                  "§6La Torre del Lobbik§r — escala de cubo en cubo. §e/cp§r: volver al último punto de reaparición · §e/niveau§r · §e/top§r",
                  "§6Der Turm des Lobbik§r — klettere von Würfel zu Würfel. §e/cp§r: zurück zum letzten Respawn-Punkt · §e/niveau§r · §e/top§r",
                  "§6Lobbik 高塔§r — 从一个方块跳到另一个方块。§e/cp§r：返回上一个重生点 · §e/niveau§r · §e/top§r");
        d("record", "§7Votre record : niveau §e%d§7 · vous repartez du point de réapparition %s.", "§7Your best: level §e%d§7 · you restart from respawn point %s.", "§7Tu récord: nivel §e%d§7 · vuelves a empezar desde el punto de reaparición %s.", "§7Dein Rekord: Level §e%d§7 · du startest am Respawn-Punkt %s.", "§7你的纪录：等级 §e%d§7 · 从重生点 %s 重新开始。");
        d("depart", "de départ", "start", "de salida", "Start", "起点");
        d("num", "n°%d", "#%d", "n.º %d", "Nr. %d", "第 %d 个");
        d("chute", "Chute ! Retour au point de réapparition (%d)", "Fall! Back to the respawn point (%d)", "¡Caída! Vuelta al punto de reaparición (%d)", "Sturz! Zurück zum Respawn-Punkt (%d)", "掉落！返回重生点（%d）");
        d("triche", "§cMouvement impossible détecté (%d). Le vol et la vitesse sont interdits sur la Tour.", "§cImpossible movement detected (%d). Flying and speed are forbidden on the Tower.", "§cMovimiento imposible detectado (%d). Volar y la velocidad están prohibidos en la Torre.", "§cUnmögliche Bewegung erkannt (%d). Fliegen und Speed sind auf dem Turm verboten.", "§c检测到不可能的移动（%d）。高塔禁止飞行与加速。");
        d("kick_triche", "Anti-triche : déplacements impossibles répétés.", "Anti-cheat: repeated impossible movements.", "Anti-trampas: movimientos imposibles repetidos.", "Anti-Cheat: wiederholt unmögliche Bewegungen.", "反作弊：多次出现不可能的移动。");
        d("checkpoint", "§6✦ Point de réapparition atteint ! §7(%d/2) — §e/cp§7 pour y revenir.", "§6✦ Respawn point reached! §7(%d/2) — §e/cp§7 to come back here.", "§6✦ ¡Punto de reaparición alcanzado! §7(%d/2) — §e/cp§7 para volver.", "§6✦ Respawn-Punkt erreicht! §7(%d/2) — §e/cp§7 um zurückzukehren.", "§6✦ 到达重生点！§7（%d/2）— §e/cp§7 可返回此处。");
        d("niveau", "Niveau %d / %d", "Level %d / %d", "Nivel %d / %d", "Level %d / %d", "等级 %d / %d");
        d("record_tag", "  ★ record", "  ★ best", "  ★ récord", "  ★ Rekord", "  ★ 纪录");
        d("sommet", "★ %s a atteint le sommet de la Tour !", "★ %s reached the top of the Tower!", "★ ¡%s ha llegado a la cima de la Torre!", "★ %s hat die Spitze des Turms erreicht!", "★ %s 登上了高塔之顶！");
        d("cp_retour", "§7Retour au point de réapparition %s.", "§7Back to respawn point %s.", "§7Vuelta al punto de reaparición %s.", "§7Zurück zum Respawn-Punkt %s.", "§7返回重生点 %s。");
        d("mon_niveau", "§7Niveau actuel §e%d§7 · record §e%d§7 / %d · chutes §e%d", "§7Current level §e%d§7 · best §e%d§7 / %d · falls §e%d", "§7Nivel actual §e%d§7 · récord §e%d§7 / %d · caídas §e%d", "§7Aktuelles Level §e%d§7 · Rekord §e%d§7 / %d · Stürze §e%d", "§7当前等级 §e%d§7 · 纪录 §e%d§7 / %d · 掉落 §e%d");
        d("top", "§6Meilleurs grimpeurs :", "§6Top climbers:", "§6Mejores escaladores:", "§6Beste Kletterer:", "§6最佳攀登者：");
        d("top_l", "§e%d. §f%s §7— niveau §e%d", "§e%d. §f%s §7— level §e%d", "§e%d. §f%s §7— nivel §e%d", "§e%d. §f%s §7— Level §e%d", "§e%d. §f%s §7— 等级 §e%d");
        d("sb_titre", "La Tour", "The Tower", "La Torre", "Der Turm", "高塔");
        d("sb_moi", "§fNiv. §e%d §7· rec. %d", "§fLvl §e%d §7· best %d", "§fNiv. §e%d §7· réc. %d", "§fLvl §e%d §7· Rek. %d", "§f等级 §e%d §7· 纪录 %d");
        d("sb_rang", "§7#%d/%d · §e%dh%02d", "§7#%d/%d · §e%dh%02d", "§7#%d/%d · §e%dh%02d", "§7#%d/%d · §e%dh%02d", "§7#%d/%d · §e%d时%02d");
        d("reprise", "Reprendre mon ascension", "Resume my climb", "Retomar mi ascenso", "Aufstieg fortsetzen", "继续攀登");
        d("reprise2", "au dernier point de réapparition", "at the last respawn point", "en el último punto de reaparición", "am letzten Respawn-Punkt", "在上一个重生点");
        d("attend", "§6Votre point de réapparition vous attend : marchez sur la dalle dorée au bout du pont, ou tapez §e/cp§6.", "§6Your respawn point is waiting: step on the golden pad at the end of the bridge, or type §e/cp§6.", "§6Tu punto de reaparición te espera: pisa la losa dorada al final del puente, o escribe §e/cp§6.", "§6Dein Respawn-Punkt wartet: tritt auf die goldene Platte am Ende der Brücke oder tippe §e/cp§6.", "§6你的重生点在等你：踩上桥尽头的金色石板，或输入 §e/cp§6。");
        d("retour_titre", "Retour à votre position…", "Back to your position…", "Volviendo a tu posición…", "Zurück zu deiner Position…", "正在返回你的位置…");
        d("kick_membres", "Serveur réservé aux membres du Lobbik · Members-only server (Lobbik)\n\nCode · Código · Code · 代码 : ", "", "", "", "");
        d("kick_suite", "\n\nhttps://lobbik.com → Jouer / Play (Minecraft) → « Lier mon compte Minecraft » / “Link my Minecraft account”, puis revenez / then come back.", "", "", "", "");
    }
    public static String langue(ServerPlayer p) {
        String l = p == null ? "fr" : p.clientInformation().language();
        int i = l.indexOf('_'); if (i > 0) l = l.substring(0, i);
        return T.get("aide").containsKey(l) ? l : "en";
    }
    public static String t(ServerPlayer p, String cle, Object... args) {
        Map<String, String> m = T.get(cle); if (m == null) return cle;
        String s = m.getOrDefault(langue(p), m.get("en")); if (s.isEmpty()) s = m.get("fr");
        return args.length == 0 ? s : String.format(s, args);
    }
}
