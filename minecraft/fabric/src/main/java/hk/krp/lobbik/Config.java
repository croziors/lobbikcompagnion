package hk.krp.lobbik;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Réglages du mod : config/lobbik.properties (créé avec les valeurs par défaut au premier démarrage). */
public final class Config {
    private final Properties p = new Properties();
    private static final String DEFAUT = """
        # Mod « lobbik » — réseau Minecraft de Lobbik en Fabric (27/09/2026)
        # rôle de ce serveur : hub, tour ou cube
        role=hub
        nom=Lobbik
        couleur=#7CFF4F
        hub_nom=hub
        # billets d'arrivée déposés par le proxy (HTTP local) et clé partagée avec lui
        port_controle=25580
        cle_reseau=A_CHANGER
        # site Lobbik : comptes liés, codes, salon #minecraft (envoi_site=false sur un serveur de test)
        site=https://lobbik.com
        cle_site=
        envoi_site=false
        pont_chat_site=false
        lire_chat_site=false
        membres_seulement=true
        heure=18000
        ouverture=45
        """;

    public Config(Path f) {
        try {
            if (!Files.exists(f)) { Files.createDirectories(f.getParent()); Files.writeString(f, DEFAUT, StandardCharsets.UTF_8); }
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
        } catch (IOException e) { Lobbik.LOG.error("config/lobbik.properties illisible", e); }
    }
    public String s(String k, String d) { String v = p.getProperty(k); return v == null ? d : v.trim(); }
    public int i(String k, int d) { try { return Integer.parseInt(s(k, "" + d)); } catch (Exception e) { return d; } }
    public long l(String k, long d) { try { return Long.parseLong(s(k, "" + d)); } catch (Exception e) { return d; } }
    public boolean b(String k, boolean d) { String v = s(k, null); return v == null ? d : v.equalsIgnoreCase("true"); }
}
