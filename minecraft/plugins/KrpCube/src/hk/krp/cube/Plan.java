package hk.krp.cube;

import java.util.*;

/** Le plan du Cube, sans rien de Bukkit. Depuis le 23/09/2026 au soir (Maxime : « une salle tue parfois et parfois non, c'est
 *  incohérent ») c'est la SALLE qui tue, comme dans le film, quelle que soit la porte par laquelle on y entre.
 *  Le plan est donc un ensemble S de salles sûres (~40-48 % du Cube) qui forme un labyrinthe EN SALLES :
 *   1. un « arbre induit » grandit depuis le départ : une salle n'entre dans S que si elle touche exactement UNE salle de S
 *      (6-voisinage), si bien que deux salles sûres voisines sont toujours reliées par l'arbre, jamais par un raccourci ;
 *      croissance « arbre croissant » (80 % on prolonge la dernière salle, 20 % une salle au hasard) : longs couloirs qui montent
 *      et redescendent, bifurcations, culs-de-sac ;
 *   2. la sortie : une salle sûre lointaine dont le chemin change le plus souvent de sens vertical ;
 *   3. quelques boucles (BOUCLES) : une salle mortelle qui touche 2 salles sûres éloignées d'au moins 6 devient sûre, si le plus
 *      court chemin départ → sortie ne diminue pas (« parfois 3 sorties sûres qui font une boucle »).
 *  Toute porte entre deux salles sûres est sûre ; entrer dans une salle hors de S tue, toujours.
 *  Si la graine demandée ne donne pas un chemin assez varié, on essaie la suivante, jusqu'à satisfaction.
 *  Testable seul : java -cp out hk.krp.cube.Plan [graine] [nx ny nz]  → imprime les chiffres du labyrinthe. */
public final class Plan {
    /** issues : 0 +x, 1 −x, 2 +z, 3 −z, 4 haut, 5 bas */
    public static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 0, 0, 1, -1}, DZ = {0, 0, 1, -1, 0, 0};
    public static final int INVERSIONS_MIN = 6;      // le chemin doit changer au moins 6 fois de sens vertical (monter, redescendre, remonter…)
    public static final int ESSAIS_MAX = 400;
    public static final int BRANCHES_MIN = 12;       // au moins 12 branches mortes partant du chemin de solution (des culs-de-sac à explorer)
    public static final double BRANCHE_MAX = 0.45;   // et aucune branche morte ne pèse plus de 45 % des salles sûres (sinon c'est un tronc, pas un labyrinthe)
    public static double NOUVEAU = 0.8;              // arbre croissant : part des pas qui prolongent la dernière salle (1 = parcours en profondeur pur)
    public static double MURAILLE = 0.35;            // part des salles candidates condamnées d'office (murs pleins) : ~43 % de salles sûres au lieu de ~50 % (0,3 → 45 %, 0,4 → 41 %)
    public static int BOUCLES = 10;                  // salles ajoutées pour faire des boucles (Maxime, 23/09/2026 : « moins de boucles », une dizaine)
    public static final int BOUCLE_MIN = 6;          // une boucle relie deux salles sûres éloignées d'au moins 6 dans le labyrinthe

    public final int nx, ny, nz, n;
    public final long graineDemandee, graine;          // graine retenue (= demandée, ou une suivante si celle-ci ne convenait pas)
    public final boolean[] sure;                       // [salle] : vrai = salle sûre (dans S) ; faux = salle mortelle
    public final boolean[][] passage;                  // [salle][issue] : vrai = la salle ET son voisin sont sûrs
    public final int depart, sortie;
    public final int[] chemin;                         // ids des salles du départ (inclus) à la sortie (incluse) : un plus court chemin dans S
    public final boolean[] surChemin;
    public final int inversions, montees, descentes, virages, niveauMin, niveauMax;
    public final int impasses, impassesVerticales, impassesEnHauteur, profondeurMaxImpasse; public final double profondeurMoyenneImpasse, distanceMoyenneImpasseChemin;
    public final int essais;
    public final int branchesDuChemin, plusGrosseBranche; public final double brancheMoyenne;
    public int boucles, cycles, nbSures;               // salles ajoutées pour les boucles, cycles créés (arêtes en plus d'un arbre), taille de S

    private Plan(Essai e, long demandee, int essais) {
        nx = e.nx; ny = e.ny; nz = e.nz; n = e.n; graineDemandee = demandee; graine = e.graine; sure = e.sure; passage = e.passage; depart = e.depart; sortie = e.sortie;
        chemin = e.chemin; surChemin = new boolean[n]; for (int c : chemin) surChemin[c] = true;
        inversions = e.inversions; montees = e.montees; descentes = e.descentes; virages = e.virages; niveauMin = e.niveauMin; niveauMax = e.niveauMax; this.essais = essais;
        int tailleArbre = 0; for (int s = 0; s < n; s++) if (sure[s]) tailleArbre++;
        // impasses : les salles sûres qui ne touchent qu'une autre salle sûre (hors départ et sortie) — calculé sur l'arbre, avant les boucles
        int[] deg = new int[n]; for (int s = 0; s < n; s++) for (int d = 0; d < 6; d++) if (passage[s][d]) deg[s]++;
        int imp = 0, impV = 0, impH = 0, profMax = 0; long profTot = 0, distTot = 0;
        for (int s = 0; s < n; s++) {
            if (!sure[s] || deg[s] != 1 || s == depart || s == sortie) continue;
            imp++; if (y(s) > 0) impH++;
            int seule = -1; for (int d = 0; d < 6; d++) if (passage[s][d]) seule = d;
            if (seule >= 4) impV++;
            // profondeur de la branche morte : de la feuille jusqu'au premier carrefour (≥ 3 salles sûres voisines) ou jusqu'au chemin de solution
            int prec = -1, cur = s, prof = 0;
            while (deg[cur] <= 2 && !surChemin[cur]) { prof++; int suiv = -1; for (int d = 0; d < 6; d++) if (passage[cur][d]) { int v = voisin(cur, d); if (v != prec) suiv = v; } if (suiv < 0) break; prec = cur; cur = suiv; }
            profTot += prof; profMax = Math.max(profMax, prof);
            int dist = 0; for (int c = s; !surChemin[c]; c = e.parent[c]) dist++;
            distTot += dist;
        }
        impasses = imp; impassesVerticales = impV; impassesEnHauteur = impH; profondeurMaxImpasse = profMax;
        // sous-arbres morts accrochés au chemin : pour chaque salle sûre hors chemin, on remonte jusqu'au chemin et on compte par salle d'attache + salle de départ de branche
        Map<Long, Integer> tailles = new HashMap<>();
        for (int s = 0; s < n; s++) { if (!sure[s] || surChemin[s]) continue; int c = s; while (e.parent[c] >= 0 && !surChemin[e.parent[c]]) c = e.parent[c]; tailles.merge((long) e.parent[c] * n + c, 1, Integer::sum); }
        int gros = 0; long tot = 0; for (int t : tailles.values()) { gros = Math.max(gros, t); tot += t; }
        branchesDuChemin = tailles.size(); plusGrosseBranche = gros; brancheMoyenne = tailles.isEmpty() ? 0 : (double) tot / tailles.size();
        profondeurMoyenneImpasse = imp > 0 ? (double) profTot / imp : 0; distanceMoyenneImpasseChemin = imp > 0 ? (double) distTot / imp : 0;
        nbSures = tailleArbre;
        ajouterBoucles();
        int aretes = 0; for (int s = 0; s < n; s++) for (int d = 0; d < 6; d++) if (passage[s][d]) aretes++;
        cycles = aretes / 2 - (nbSures - 1);
    }
    /** Ajoute jusqu'à BOUCLES salles sûres : chacune touche au moins 2 salles sûres éloignées d'au moins BOUCLE_MIN dans le labyrinthe
     *  (on peut alors tourner en rond, une salle a parfois 3 issues sûres), SANS raccourcir le chemin de sortie : la salle n'est
     *  gardée que si le plus court chemin départ → sortie ne diminue pas. Déterministe (dépend de la graine). */
    private void ajouterBoucles() {
        Random r = new Random(graine * 7919L + 17);
        int L = distances(depart)[sortie];
        List<Integer> cand = new ArrayList<>(); for (int v = 0; v < n; v++) if (!sure[v]) cand.add(v);
        Collections.shuffle(cand, r);
        int ajout = 0;
        for (int v : cand) {
            if (ajout >= BOUCLES) break;
            List<Integer> vs = new ArrayList<>(); for (int d = 0; d < 6; d++) { int w = voisin(v, d); if (w >= 0 && sure[w]) vs.add(w); }
            if (vs.size() < 2) continue;
            boolean loin = true;
            for (int a = 0; a < vs.size() - 1 && loin; a++) { int[] da = distances(vs.get(a)); for (int b = a + 1; b < vs.size(); b++) if (da[vs.get(b)] < BOUCLE_MIN) { loin = false; break; } }
            if (!loin) continue;
            rendreSure(v, true);
            if (distances(depart)[sortie] < L) { rendreSure(v, false); continue; }
            ajout++; nbSures++;
        }
        boucles = ajout;
    }
    private void rendreSure(int v, boolean oui) {
        sure[v] = oui;
        for (int d = 0; d < 6; d++) { int w = voisin(v, d); if (w < 0) continue; boolean p = oui && sure[w]; passage[v][d] = p; passage[w][d ^ 1] = p; }   // DX/DY/DZ : directions opposées par paires (0/1, 2/3, 4/5)
    }
    private int[] distances(int de) {
        int[] dist = new int[n]; Arrays.fill(dist, -1); ArrayDeque<Integer> f = new ArrayDeque<>(); dist[de] = 0; f.add(de);
        while (!f.isEmpty()) { int c = f.poll(); for (int d = 0; d < 6; d++) if (passage[c][d]) { int v = voisin(c, d); if (v >= 0 && dist[v] < 0) { dist[v] = dist[c] + 1; f.add(v); } } }
        return dist;
    }

    public int x(int id) { return id % nx; } public int y(int id) { return (id / nx) / nz; } public int z(int id) { return (id / nx) % nz; }
    public int id(int i, int j, int k) { return (j * nz + k) * nx + i; }
    public int voisin(int id, int d) { int i = x(id) + DX[d], j = y(id) + DY[d], k = z(id) + DZ[d]; return i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz ? -1 : id(i, j, k); }
    /** Empreinte du plan (salles sûres + passages + départ + sortie) : change ⇒ le monde doit être reconstruit. */
    public long empreinte() {
        long h = 1125899906842597L; h = h * 31 + depart; h = h * 31 + sortie; h = h * 31 + nx; h = h * 31 + ny; h = h * 31 + nz; h = h * 31 + 2;   // 2 = génération « salles qui tuent »
        for (int s = 0; s < n; s++) { if (sure[s]) h = h * 37 + s; for (int d = 0; d < 6; d++) if (passage[s][d]) h = h * 31 + (s * 6L + d); }
        return h & 0x7fffffffffffL;
    }

    /** Génère le plan : la graine demandée, sinon les suivantes, jusqu'à un chemin de solution assez long et assez varié. */
    public static Plan generer(long graine, int nx, int ny, int nz) {
        Plan meilleur = null;
        for (int k = 0; k < ESSAIS_MAX; k++) {
            Plan p = new Plan(new Essai(graine + k, nx, ny, nz), graine, k + 1);
            if (p.valide()) return p;
            if (meilleur == null || p.score() > meilleur.score()) meilleur = p;
        }
        return meilleur;
    }
    public boolean valide() { return chemin.length > 1 && inversions >= INVERSIONS_MIN && niveauMax - niveauMin >= 3 && branchesDuChemin >= BRANCHES_MIN && plusGrosseBranche <= nbSures * BRANCHE_MAX; }
    private double score() { return chemin.length <= 1 ? -1 : inversions + Math.min(branchesDuChemin, BRANCHES_MIN) - (plusGrosseBranche > nbSures * BRANCHE_MAX ? 5 : 0); }

    /** Un essai de génération pour une graine donnée. */
    private static final class Essai {
        final int nx, ny, nz, n; final long graine; final boolean[] sure; final boolean[][] passage; final int depart; int sortie = -1; int[] chemin; int[] parent;
        int inversions, montees, descentes, virages, niveauMin, niveauMax;
        Essai(long graine, int nx, int ny, int nz) {
            this.graine = graine; this.nx = nx; this.ny = ny; this.nz = nz; n = nx * ny * nz; sure = new boolean[n]; passage = new boolean[n][6];
            parent = new int[n]; Arrays.fill(parent, -1);
            Random r = new Random(graine);
            // 1. arbre induit par « arbre croissant » : une salle voisine devient sûre seulement si elle ne touche aucune autre salle sûre
            //    que celle d'où l'on vient (sinon deux salles sûres se toucheraient hors de l'arbre = un raccourci).
            depart = id(r.nextInt(nx), 0, r.nextInt(nz));
            List<Integer> actifs = new ArrayList<>(); actifs.add(depart); sure[depart] = true;
            boolean[] condamnee = new boolean[n];   // salles tirées pour rester mortelles (plus de murs, S moins dense)
            int[] dirs = new int[6];
            while (!actifs.isEmpty()) {
                int idx = r.nextDouble() < NOUVEAU ? actifs.size() - 1 : r.nextInt(actifs.size());
                int s = actifs.get(idx), nd = 0;
                for (int d = 0; d < 6; d++) { int v = voisin(s, d); if (v >= 0 && !sure[v] && !condamnee[v] && voisinsSurs(v) == 1) dirs[nd++] = d; }
                if (nd == 0) { actifs.remove(idx); continue; }   // une salle refusée le reste : le nombre de voisines sûres ne fait que croître
                int v = voisin(s, dirs[r.nextInt(nd)]);
                if (r.nextDouble() < MURAILLE) { condamnee[v] = true; continue; }
                sure[v] = true; parent[v] = s; actifs.add(v);
            }
            for (int s = 0; s < n; s++) if (sure[s]) for (int d = 0; d < 6; d++) { int v = voisin(s, d); passage[s][d] = v >= 0 && sure[v]; }
            // 2. distances dans l'arbre depuis le départ
            int[] dist = new int[n]; Arrays.fill(dist, -1);
            int[] file = new int[n]; int tete = 0, queue = 0; file[queue++] = depart; dist[depart] = 0;
            while (tete < queue) { int s = file[tete++]; for (int d = 0; d < 6; d++) if (passage[s][d]) { int v = voisin(s, d); if (dist[v] < 0) { dist[v] = dist[s] + 1; file[queue++] = v; } } }
            // 3. la sortie : parmi les salles sûres à bonne distance (10×10×10 : 55 à 90 salles), celle dont le chemin change le plus
            //    souvent de sens vertical ; à égalité, la plus lointaine
            int lMin = Math.max(12, n / 18), lMax = Math.max(lMin + 8, n / 11);
            int meilleurInv = -1, meilleureDist = -1;
            for (int s = 0; s < n; s++) {
                if (!sure[s] || s == depart || dist[s] < lMin || dist[s] > lMax) continue;
                int inv = inversions(s);
                if (inv > meilleurInv || (inv == meilleurInv && dist[s] > meilleureDist)) { meilleurInv = inv; meilleureDist = dist[s]; sortie = s; }
            }
            if (sortie < 0) { chemin = new int[]{depart}; sortie = depart; return; }
            // 4. le chemin et ses chiffres
            int L = dist[sortie] + 1; chemin = new int[L]; for (int c = sortie, p = L - 1; p >= 0; c = parent[c], p--) chemin[p] = c;
            int dernierV = 0, dernierD = -1; niveauMin = ny; niveauMax = 0;
            for (int p = 0; p < L; p++) { niveauMin = Math.min(niveauMin, y(chemin[p])); niveauMax = Math.max(niveauMax, y(chemin[p])); }
            for (int p = 1; p < L; p++) {
                int dy = y(chemin[p]) - y(chemin[p - 1]), d = direction(chemin[p - 1], chemin[p]);
                if (dernierD >= 0 && d != dernierD) virages++; dernierD = d;
                if (dy > 0) montees++; else if (dy < 0) descentes++;
                if (dy != 0) { if (dernierV != 0 && dy != dernierV) inversions++; dernierV = dy; }
            }
        }
        int voisinsSurs(int v) { int c = 0; for (int d = 0; d < 6; d++) { int w = voisin(v, d); if (w >= 0 && sure[w]) c++; } return c; }
        int inversions(int s) { int inv = 0, dernierV = 0; for (int c = s; parent[c] >= 0; c = parent[c]) { int dy = y(c) - y(parent[c]); if (dy != 0) { if (dernierV != 0 && dy != dernierV) inv++; dernierV = dy; } } return inv; }
        int direction(int a, int b) { for (int d = 0; d < 6; d++) if (voisin(a, d) == b) return d; return -1; }
        int x(int id) { return id % nx; } int y(int id) { return (id / nx) / nz; } int z(int id) { return (id / nx) % nz; }
        int id(int i, int j, int k) { return (j * nz + k) * nx + i; }
        int voisin(int id, int d) { int i = x(id) + DX[d], j = y(id) + DY[d], k = z(id) + DZ[d]; return i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz ? -1 : id(i, j, k); }
    }

    /** Avancement vers la sortie, en % (pour le site) : 0 au départ, 100 dans la sortie, mesuré par la distance dans le labyrinthe
     *  des salles sûres jusqu'à la sortie. Une impasse ne fait pas avancer. Une salle mortelle : 0 (l'appelant passe la dernière salle sûre). */
    private int[] distSortie;
    public synchronized int avance(int id) {
        int ds = distanceSortie(id); if (ds < 0) return 0;
        int total = Math.max(1, distSortie[depart]);
        return Math.max(0, Math.min(100, (int) Math.round(100.0 * (total - ds) / total)));
    }
    /** Nombre de salles à traverser, par les salles sûres, pour atteindre la sortie (0 dans la sortie) ; −1 pour une salle mortelle. */
    public synchronized int distanceSortie(int id) {
        if (distSortie == null) distSortie = distances(sortie);
        return id < 0 || id >= n || !sure[id] ? -1 : distSortie[id];
    }
    public String resume() {
        return "graine " + graine + (graine != graineDemandee ? " (demandée " + graineDemandee + ", " + essais + " essais)" : "") + " · " + nbSures + " salles sûres sur " + n + " (" + Math.round(100.0 * nbSures / n) + " %), "
            + (n - nbSures) + " mortelles · chemin de solution " + chemin.length + " salles, " + montees + " montées, " + descentes
            + " descentes, " + inversions + " inversions verticales, " + virages + " virages, niveaux " + (niveauMin + 1) + "→" + (niveauMax + 1) + " · " + impasses + " culs-de-sac (" + impassesEnHauteur + " en hauteur, "
            + impassesVerticales + " au bout d'une échelle), branche morte moyenne " + String.format(Locale.ROOT, "%.1f", profondeurMoyenneImpasse) + " salles (max " + profondeurMaxImpasse + "), "
            + String.format(Locale.ROOT, "%.1f", distanceMoyenneImpasseChemin) + " salles en moyenne du chemin · " + branchesDuChemin + " branches mortes partent du chemin (la plus grosse " + plusGrosseBranche + " salles, moyenne "
            + String.format(Locale.ROOT, "%.1f", brancheMoyenne) + ") · " + boucles + " salles de boucle (" + cycles + " cycles) · départ " + coord(depart) + ", sortie " + coord(sortie);
    }
    public String coord(int id) { return "[" + x(id) + "," + y(id) + "," + z(id) + "]"; }

    public static void main(String[] a) {
        if (a.length > 0 && a[0].equals("balayage")) {   // comparer les réglages de l'arbre croissant
            for (double pn : new double[]{0.3, 0.35, 0.4}) { MURAILLE = pn;
                for (long g : new long[]{20260923L, 1L, 42L, 777L, 2026L}) { Plan p = generer(g, 10, 10, 10);
                    System.out.println(String.format(Locale.ROOT, "MURAILLE=%.2f graine %d (essais %d) : sûres %d, chemin %d, inversions %d, virages %d, niveaux %d→%d | impasses %d (moy %.1f, max %d) | branches du chemin %d (max %d, moy %.1f) | boucles %d",
                        pn, g, p.essais, p.nbSures, p.chemin.length, p.inversions, p.virages, p.niveauMin + 1, p.niveauMax + 1, p.impasses, p.profondeurMoyenneImpasse, p.profondeurMaxImpasse, p.branchesDuChemin, p.plusGrosseBranche, p.brancheMoyenne, p.boucles)); } }
            return;
        }
        long g = a.length > 0 ? Long.parseLong(a[0]) : 20260923L; int nx = a.length > 3 ? Integer.parseInt(a[1]) : 10, ny = a.length > 3 ? Integer.parseInt(a[2]) : 10, nz = a.length > 3 ? Integer.parseInt(a[3]) : 10;
        Plan p = generer(g, nx, ny, nz);
        System.out.println(p.resume());
        int passages = 0, portesInternes = 0; for (int s = 0; s < p.n; s++) for (int d = 0; d < 6; d++) if (p.voisin(s, d) >= 0) { portesInternes++; if (p.passage[s][d]) passages++; }
        System.out.println("passages sûrs " + passages / 2 + " sur " + portesInternes / 2 + " portes intérieures, " + (6 * p.n - portesInternes) + " portes du bord sur le vide, empreinte " + p.empreinte() + ", valide " + p.valide());
        StringBuilder b = new StringBuilder("chemin :"); int dernierD = -1, run = 0; String[] noms = {"+x", "-x", "+z", "-z", "haut", "bas"};
        for (int i = 1; i < p.chemin.length; i++) { int d = -1; for (int k = 0; k < 6; k++) if (p.voisin(p.chemin[i - 1], k) == p.chemin[i]) d = k;
            if (d == dernierD) run++; else { if (dernierD >= 0) b.append(' ').append(noms[dernierD]).append('×').append(run); dernierD = d; run = 1; } }
        if (dernierD >= 0) b.append(' ').append(noms[dernierD]).append('×').append(run);
        System.out.println(b);
        // vérifications : S connexe, sortie ∈ S, chemin monotone et entièrement sûr, passages cohérents avec S
        int[] dist = p.distances(p.depart); int atteintes = 0; for (int s = 0; s < p.n; s++) if (p.sure[s] && dist[s] >= 0) atteintes++;
        boolean monotone = p.chemin[0] == p.depart && p.chemin[p.chemin.length - 1] == p.sortie && dist[p.sortie] == p.chemin.length - 1, horsS = false;
        for (int i = 0; i < p.chemin.length; i++) { if (!p.sure[p.chemin[i]]) horsS = true; if (dist[p.chemin[i]] != i) monotone = false; }
        boolean coherent = true; for (int s = 0; s < p.n; s++) for (int d = 0; d < 6; d++) { int v = p.voisin(s, d); if (p.passage[s][d] != (v >= 0 && p.sure[s] && p.sure[v])) coherent = false; }
        boolean ok = atteintes == p.nbSures && p.sure[p.sortie] && monotone && !horsS && coherent;
        System.out.println("vérification : S connexe " + atteintes + "/" + p.nbSures + ", sortie sûre " + p.sure[p.sortie] + ", chemin monotone " + monotone + " (longueur " + (p.chemin.length - 1) + " = distance " + dist[p.sortie] + "), salle mortelle sur le chemin " + horsS
            + ", passages = paires de salles sûres " + coherent + (ok ? " → OK" : " → PROBLÈME"));
    }
}
