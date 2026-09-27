-- ecran-hub.lua : écrans CC: Tweaked du hub Lobbik (27/09/2026).
-- Posé par le mod serveur « lobbik » (hk.krp.lobbik.Ordinateurs), qui RÉÉCRIT ce fichier à chaque démarrage du hub :
-- toute modification se fait dans /root/lobbik-mc/fabric/src/main/resources/lobbik/ecran-hub.lua.
-- Rôle de l'écran dans le fichier « role » : tour, cube, serveurs, direct.
-- Données : « direct.json », écrit par le mod toutes les 2 s (hub, La Tour, Le Cube, Mer de lave, CS2, Valheim).
-- S'il se tait plus de 30 s, repli sur l'API publique https://lobbik.com (toutes les 30 s).
-- Identité Volt : vert électrique #C6F500 et cyan #00E5FF sur fond carbone, aplats sans dégradé.

local VERSION = "1.0"
local ECHELLE = 1
local API = "https://lobbik.com/auth/steam.php?a="
local API_LAVE = "mc_lave"          -- point public de la Mer de lave (à venir) ; sans réponse, l'écran affiche « bientôt »
local PERIODE = 2

-- palette (codes blit) : les 16 couleurs du moniteur sont redéfinies
local FOND, PAN, TRAIT, GRIS, DISCRET, BLANC = "f", "7", "c", "8", "a", "0"
local VOLT, CYAN, ROUGE, OR, LAVE, ROUGE_S = "5", "9", "e", "4", "1", "6"
local PALETTE = {
  f = 0x0C0D0E, ["7"] = 0x17191B, c = 0x2A2D31, ["8"] = 0x9AA0A6, a = 0x5F656B, ["0"] = 0xF2F4F5,
  ["5"] = 0xC6F500, ["9"] = 0x00E5FF, e = 0xFF5A52, ["4"] = 0xFFC53D, ["1"] = 0xFF7A2E, ["6"] = 0x5A2422,
  ["2"] = 0x34410A, ["3"] = 0x003C44, b = 0x1E2023, d = 0x8FB300,
}

local function lireFichier(nom)
  if not fs.exists(nom) then return nil end
  local f = fs.open(nom, "rb") if not f then return nil end
  local t = f.readAll() f.close() return t
end
local function ecrireFichier(nom, t)
  local f = fs.open(nom, "wb") if f then f.write(t) f.close() end
end
local ROLE = ((lireFichier("role") or os.getComputerLabel() or "serveurs"):gsub("^lobbik%-", ""):gsub("%s", ""))

-- UTF-8 (source, API) → Latin-1 (police de CC) ; le reste devient « ? »
local function L(s)
  s = tostring(s or "")
  s = s:gsub("[\xC2\xC3][\x80-\xBF]", function(c) local a, b = c:byte(1, 2) return string.char((a - 0xC0) * 64 + (b - 0x80)) end)
  return (s:gsub("[\xC4-\xF4][\x80-\xBF]+", "?"))
end
local function num(n) return tonumber(n) or 0 end
local function ent(n) return math.floor(num(n) + 0.5) end
-- 2969 → « 2 969 »
local function nombre(n)
  local s = tostring(ent(n)) local signe = "" if s:sub(1, 1) == "-" then signe, s = "-", s:sub(2) end
  local r = s:reverse():gsub("(%d%d%d)", "%1 "):reverse()
  return signe .. (r:gsub("^ ", ""))
end
local function pluriel(n, un, plusieurs) return nombre(n) .. " " .. (ent(n) > 1 and plusieurs or un) end
local function court(s, n) s = L(s) if n <= 0 then return "" end if #s > n then return s:sub(1, n - 1) .. "." end return s end
local function duree(s) s = ent(s) return string.format("%d:%02d", math.floor(s / 60), s % 60) end

------------------------------------------------------------------------------------------------ image (tampon)
local mon, W, H
local T, F, B = {}, {}, {}
local function nouvelle(fond)
  for y = 1, H do
    local t, f, b = {}, {}, {}
    for x = 1, W do t[x], f[x], b[x] = " ", BLANC, fond end
    T[y], F[y], B[y] = t, f, b
  end
end
-- texte déjà en Latin-1 ; fond nil = on garde celui de la case
local function ecr(x, y, s, fg, bg)
  if y < 1 or y > H then return x + #s end
  for i = 1, #s do
    local xx = x + i - 1
    if xx >= 1 and xx <= W then T[y][xx], F[y][xx] = s:sub(i, i), fg if bg then B[y][xx] = bg end end
  end
  return x + #s
end
local function txt(x, y, s, fg, bg) return ecr(x, y, L(s), fg, bg) end
local function adroite(x, y, s, fg, bg) s = L(s) ecr(x - #s + 1, y, s, fg, bg) return x - #s end
local function rect(x, y, w, h, bg)
  for yy = y, y + h - 1 do
    if yy >= 1 and yy <= H then for xx = x, x + w - 1 do if xx >= 1 and xx <= W then T[yy][xx], B[yy][xx] = " ", bg end end end
  end
end
-- une case en caractères « télétexte » (2 × 3 sous-pixels) : bits 1 2 / 4 8 / 16 32
local function sub(x, y, bits, fg)
  if x < 1 or x > W or y < 1 or y > H or bits == 0 then return end
  if bits >= 32 then T[y][x], F[y][x], B[y][x] = string.char(128 + 63 - bits), B[y][x], fg
  else T[y][x], F[y][x] = string.char(128 + bits), fg end
end
local BARRE_G, TRAIT_MILIEU, DEMI_MILIEU = 1 + 4 + 16, 4 + 8, 4
-- barre fine (tiers du milieu de la case), au demi-caractère près ; piste = reste de la barre
local function barre(x, y, w, frac, fg, piste)
  if w < 1 then return end
  frac = math.max(0, math.min(1, num(frac)))
  local demi = math.floor(frac * w * 2 + 0.5)
  if frac > 0 and demi == 0 then demi = 1 end
  for i = 0, w - 1 do
    local d = demi - 2 * i
    if d >= 2 then sub(x + i, y, TRAIT_MILIEU, fg)
    elseif d == 1 then sub(x + i, y, DEMI_MILIEU, fg)
    elseif piste then sub(x + i, y, TRAIT_MILIEU, piste) end
  end
end
-- texte tronqué à la place disponible jusqu'à la colonne xmax
local function txtMax(x, y, s, xmax, fg, bg) return ecr(x, y, court(s, math.max(0, xmax - x + 1)), fg, bg) end

-- police 5 × 7 pour les titres et les grands nombres (un point = k × k sous-pixels)
local FONTE = {
  A = {14,17,17,31,17,17,17}, B = {30,17,17,30,17,17,30}, C = {14,17,16,16,16,17,14}, D = {28,18,17,17,17,18,28},
  E = {31,16,16,30,16,16,31}, F = {31,16,16,30,16,16,16}, G = {14,17,16,23,17,17,15}, H = {17,17,17,31,17,17,17},
  I = {14,4,4,4,4,4,14}, J = {7,2,2,2,2,18,12}, K = {17,18,20,24,20,18,17}, L = {16,16,16,16,16,16,31},
  M = {17,27,21,21,17,17,17}, N = {17,17,25,21,19,17,17}, O = {14,17,17,17,17,17,14}, P = {30,17,17,30,16,16,16},
  Q = {14,17,17,17,21,18,13}, R = {30,17,17,30,20,18,17}, S = {15,16,16,14,1,1,30}, T = {31,4,4,4,4,4,4},
  U = {17,17,17,17,17,17,14}, V = {17,17,17,17,17,10,4}, W = {17,17,17,21,21,21,10}, X = {17,17,10,4,10,17,17},
  Y = {17,17,17,10,4,4,4}, Z = {31,1,2,4,8,16,31},
  ["0"] = {14,17,17,17,17,17,14}, ["1"] = {4,12,4,4,4,4,14}, ["2"] = {14,17,1,2,4,8,31}, ["3"] = {31,2,4,2,1,17,14},
  ["4"] = {2,6,10,18,31,2,2}, ["5"] = {31,16,30,1,1,17,14}, ["6"] = {6,8,16,30,17,17,14}, ["7"] = {31,1,2,4,8,8,8},
  ["8"] = {14,17,17,14,17,17,14}, ["9"] = {14,17,17,15,1,2,12},
  [" "] = {0,0,0,0,0,0,0}, ["."] = {0,0,0,0,0,12,12}, [":"] = {0,12,12,0,12,12,0}, ["-"] = {0,0,0,14,0,0,0},
  ["/"] = {0,1,2,4,8,16,0}, ["%"] = {24,25,2,4,8,19,3}, ["+"] = {0,4,4,31,4,4,0}, ["!"] = {4,4,4,4,4,0,4},
}
local function largeurGrand(s, k) return math.ceil(((#s * 6) - 1) * k / 2) end
local function grand(x, y, s, k, fg, dy)
  s = tostring(s):upper() dy = dy or 0
  local lp, hp = (#s * 6 - 1) * k, 7 * k
  local function allume(px, py)
    py = py - dy
    if px < 0 or py < 0 or px >= lp or py >= hp then return false end
    local g = FONTE[s:sub(math.floor(px / (6 * k)) + 1, math.floor(px / (6 * k)) + 1)]
    local gx = math.floor((px % (6 * k)) / k)
    if not g or gx > 4 then return false end
    return math.floor(g[math.floor(py / k) + 1] / 2 ^ (4 - gx)) % 2 == 1
  end
  for cy = 0, math.ceil((hp + dy) / 3) - 1 do
    for cx = 0, math.ceil(lp / 2) - 1 do
      local px, py, n = cx * 2, cy * 3, 0
      if allume(px, py) then n = n + 1 end
      if allume(px + 1, py) then n = n + 2 end
      if allume(px, py + 1) then n = n + 4 end
      if allume(px + 1, py + 1) then n = n + 8 end
      if allume(px, py + 2) then n = n + 16 end
      if allume(px + 1, py + 2) then n = n + 32 end
      sub(x + cx, y + cy, n, fg)
    end
  end
  return x + math.ceil(lp / 2)
end

------------------------------------------------------------------------------------------------ données
local D, source, ageDonnees, siteT, laveSiteT, laveSite = nil, "aucune", nil, -1e12, -1e12, nil
local etatHttp = "pas encore utilisé"
local function maintenant() return os.epoch("utc") end
local function lireJson(t) if not t then return nil end local ok, d = pcall(textutils.unserializeJSON, t) if ok and type(d) == "table" then return d end return nil end
local function get(a)
  if not http then etatHttp = "API http désactivée" return nil end
  local r, err = http.get(API .. a, { ["User-Agent"] = "lobbik-ecran/" .. VERSION })
  if not r then etatHttp = a .. " : " .. tostring(err) return nil end
  local code = r.getResponseCode and r.getResponseCode() or 200
  local t = r.readAll() r.close()
  etatHttp = a .. " : HTTP " .. tostring(code)
  return lireJson(t)
end
-- l'API du site, ramenée au format du mod (secours si direct.json se tait)
local function depuisSite()
  local s, c, cs, vh = get("mc_serveur"), get("mc_cube"), get("cs_serveur"), get("cs_serveur&jeu=892970")
  if not (s or c or cs or vh) then return nil end
  local t = maintenant()
  local d = { t = t, site = true, serveurs = {} }
  local function etat(x) return type(x) == "table" and type(x.etat) == "table" and x.etat or nil end
  if etat(s) then d.serveurs.hub = { en_ligne = etat(s).en_ligne, joueurs = etat(s).joueurs, places = etat(s).places, reseau = true } end
  if s then
    local tr = s.tour or {}
    d.tour = { t = t, sommet = tr.cubes or 0, checkpoints = {}, joueurs = {}, top = {} }
    for _, j in ipairs(s.positions or {}) do table.insert(d.tour.joueurs, { nom = j.nom, niveau = j.niveau or 0, record = j.record or 0 }) end
    for _, j in ipairs(s.top or {}) do table.insert(d.tour.top, { nom = j.nom, record = j.record or 0 }) end
    d.serveurs.tour = { en_ligne = etat(s) and etat(s).en_ligne, joueurs = #d.tour.joueurs, places = s.places or 100 }
  end
  if c then
    local st = c.stats or {}
    d.cube = { t = t, salles = 1000, joueurs = {}, loin = {}, vite = {}, sorties = st.sorties or 0 }
    for _, j in ipairs(c.positions or {}) do table.insert(d.cube.joueurs, { nom = j.nom, dedans = true, niveau = num(j.j) + 1, salles = j.salles or 0, avance = j.avance or 0, morts = j.morts or 0 }) end
    for _, j in ipairs(c.braves or {}) do table.insert(d.cube.loin, { nom = j.nom, salles = j.salles_max or 0 }) end
    for _, j in ipairs(c.top or {}) do table.insert(d.cube.vite, { nom = j.nom, temps = j.meilleur or 0 }) end
    d.serveurs.cube = { en_ligne = etat(c) and etat(c).en_ligne, joueurs = #d.cube.joueurs, places = c.places or 30 }
  end
  if etat(cs) then d.serveurs.cs2 = { en_ligne = etat(cs).en_ligne, joueurs = etat(cs).joueurs, places = cs.places, carte = etat(cs).carte } end
  if etat(vh) then d.serveurs.valheim = { en_ligne = etat(vh).en_ligne, joueurs = etat(vh).joueurs, places = vh.places } end
  return d
end
-- Mer de lave par le site, tant que le mod ne la voit pas (serveur ailleurs ou pas encore ouvert)
local function laveDepuisSite()
  if maintenant() - laveSiteT < 30000 then return laveSite end
  laveSiteT = maintenant()
  local l = get(API_LAVE)
  if type(l) ~= "table" or l.error or not (l.joueurs or l.positions) then laveSite = nil return nil end
  laveSite = { t = maintenant(), longueur = l.longueur or 800, joueurs = {}, top = {} }
  for _, j in ipairs(l.joueurs or l.positions or {}) do table.insert(laveSite.joueurs, { nom = j.nom, progres = j.progres or 0, reap = j.reap or 0 }) end
  for _, j in ipairs(l.top or {}) do table.insert(laveSite.top, { nom = j.nom, record = j.record or 0 }) end
  return laveSite
end
local siteD, perimeDepuis = nil, nil
local function charger()
  local d = lireJson(lireFichier("direct.json"))
  if d and d.t and maintenant() - num(d.t) < 30000 then
    D, source, perimeDepuis = d, "local", nil
  else
    -- le serveur sort de pause ou le mod se tait : 10 s de grâce avant de demander au site
    perimeDepuis = perimeDepuis or os.clock()
    if os.clock() - perimeDepuis > 10 then
      if maintenant() - siteT > 30000 then siteT = maintenant() local s = depuisSite() if s then siteD = s end end
      if siteD and maintenant() - num(siteD.t) < 90000 then D, source = siteD, "site"
      else D, source = d, d and "ancien" or "aucune" if D then D.serveurs = {} end end
    end
  end
  ageDonnees = D and D.t and (maintenant() - num(D.t)) or nil
  if (ROLE == "direct" or ROLE == "serveurs") and D and not D.lave and not (D.serveurs and D.serveurs.lave) then
    local l = laveDepuisSite()
    if l then D.lave = l D.serveurs = D.serveurs or {} D.serveurs.lave = { en_ligne = true, joueurs = #l.joueurs, places = 0 } end
  end
end
local function frais(x) return type(x) == "table" and x.t and maintenant() - num(x.t) < 30000 end
local function srv(k) return D and D.serveurs and D.serveurs[k] or nil end

------------------------------------------------------------------------------------------------ gabarit commun
local battement = false
local function entete(titre, couleur, sous, droite)
  for y = 2, 6 do sub(3, y, BARRE_G, couleur) end
  grand(6, 2, titre, 2, couleur, 1)
  -- pastille d'état (le point bat à chaque image : l'écran vit)
  local libelle, point = "EN DIRECT", battement and ROUGE or ROUGE_S
  if source == "site" then libelle, point = "VIA LOBBIK.COM", OR elseif source ~= "local" then libelle, point = "HORS LIGNE", DISCRET end
  local l = #L(libelle) + 5
  rect(W - l - 1, 3, l, 3, PAN)
  ecr(W - l + 1, 4, "\7", point, PAN) txt(W - l + 3, 4, libelle, BLANC, PAN)
  if droite then adroite(W - 2, 7, droite, DISCRET) end
  txt(6, 8, sous, GRIS)
end
local function panneau(x, y, w, h, titre, droite)
  rect(x, y, w, h, PAN)
  if titre then txt(x + 2, y + 1, titre, GRIS, PAN) end
  if droite then adroite(x + w - 3, y + 1, droite, DISCRET, PAN) end
end
local function pied(texte)
  txt(3, H, "lobbik.com", DISCRET)
  if texte then adroite(W - 2, H, texte, DISCRET) end
end
local function rang(x, y, i, bg) adroite(x, y, tostring(i), i == 1 and OR or DISCRET, bg) end

------------------------------------------------------------------------------------------------ La Tour
local function ecranTour()
  local s, d = srv("tour"), D and D.tour or {}
  local sommet = math.max(1, num(d.sommet))
  local joueurs = (s and s.en_ligne and frais(d)) and (d.joueurs or {}) or {}
  table.sort(joueurs, function(a, b) return num(a.niveau) > num(b.niveau) end)
  local sous
  if not s then sous = "État du serveur inconnu" elseif not s.en_ligne then sous = "Serveur hors ligne pour le moment"
  else sous = (#joueurs == 0 and "Personne ne grimpe en ce moment" or pluriel(#joueurs, "grimpeur", "grimpeurs") .. " en ce moment") .. " · " .. nombre(sommet) .. " niveaux" end
  entete("LA TOUR", VOLT, sous)
  -- la tour de profil : chaque grimpeur à sa hauteur, points de réapparition en or
  local y0, y1, tx = 11, H - 3, 5
  local function ligneDe(n) return y1 - math.floor(math.min(num(n), sommet) / sommet * (y1 - y0) + 0.5) end
  rect(tx, y0, 3, y1 - y0 + 1, PAN)
  for k = 1, 9 do local y = ligneDe(sommet * k / 10) for x = tx, tx + 2 do sub(x, y, TRAIT_MILIEU, TRAIT) end end
  for _, cp in ipairs(d.checkpoints or {}) do local y = ligneDe(cp) for x = tx - 1, tx + 3 do sub(x, y, TRAIT_MILIEU, OR) end end
  for x = tx, tx + 2 do sub(x, y0 - 1, 16 + 32 + 4 + 8, VOLT) end
  txt(tx + 5, y0, sommet > 1 and ("sommet · " .. nombre(sommet)) or "sommet", DISCRET) txt(tx + 5, y1, "départ", DISCRET)
  local pris = { [y0] = true, [y1] = true }
  for i, j in ipairs(joueurs) do
    if i > 12 then break end
    local c = (i % 2 == 1) and CYAN or VOLT
    local y = ligneDe(j.niveau)
    rect(tx, y, 3, 1, c)
    local yl, pas = y, 0
    while pris[yl] and pas < 40 do pas = pas + 1 yl = y + (pas % 2 == 1 and -1 or 1) * math.ceil(pas / 2) end
    if yl >= y0 and yl <= y1 then
      pris[yl] = true
      local x = ecr(tx + 5, yl, "\17 ", c) x = ecr(x, yl, court(j.nom, 14), BLANC) txt(x + 1, yl, nombre(j.niveau), c)
    end
  end
  -- en ligne
  local px, pw = 33, W - 34
  panneau(px, 11, pw, 10, "EN LIGNE")
  adroite(px + pw - 13, 12, "NIVEAU", DISCRET, PAN) adroite(px + pw - 3, 12, "RECORD", DISCRET, PAN)
  local y = 14
  if #joueurs == 0 then txt(px + 2, y, "Personne ne grimpe en ce moment.", GRIS, PAN) end
  for i, j in ipairs(joueurs) do
    if i > 6 then adroite(px + pw - 3, y - 1, "+ " .. (#joueurs - 6) .. " autres", DISCRET, PAN) break end
    ecr(px + 2, y, court(j.nom, 16), BLANC, PAN)
    adroite(px + pw - 13, y, nombre(j.niveau), VOLT, PAN)
    adroite(px + pw - 3, y, nombre(j.record), GRIS, PAN)
    y = y + 1
  end
  -- records
  local top = d.top or {}
  panneau(px, 23, pw, H - 25, "TOP 10 · RECORDS")
  local max = 1 for _, t in ipairs(top) do max = math.max(max, num(t.record)) end
  y = 26
  if #top == 0 then txt(px + 2, y, "Aucun record pour l'instant.", GRIS, PAN) end
  for i, t in ipairs(top) do
    if i > 10 or y > H - 4 then break end
    rang(px + 3, y, i, PAN)
    ecr(px + 5, y, court(t.nom, 14), BLANC, PAN)
    barre(px + 20, y, pw - 30, num(t.record) / max, i == 1 and OR or VOLT, TRAIT)
    adroite(px + pw - 3, y, nombre(t.record), i == 1 and OR or BLANC, PAN)
    y = y + 1
  end
  pied("à droite du hub, par le pont")
end

------------------------------------------------------------------------------------------------ Le Cube
local function ecranCube()
  local s, d = srv("cube"), D and D.cube or {}
  local joueurs = (s and s.en_ligne and frais(d)) and (d.joueurs or {}) or {}
  local dedans, vestibule = {}, 0
  for _, j in ipairs(joueurs) do if j.dedans then table.insert(dedans, j) else vestibule = vestibule + 1 end end
  table.sort(dedans, function(a, b) return num(a.avance) > num(b.avance) end)
  local sorties = num(d.sorties)
  local sous
  if not s then sous = "État du serveur inconnu" elseif not s.en_ligne then sous = "Serveur hors ligne pour le moment"
  else sous = (#dedans == 0 and "Personne dans le Cube" or (nombre(#dedans) .. " dans le Cube")) .. " · " .. nombre(d.salles or 1000) .. " salles · "
    .. (sorties == 0 and "aucune sortie encore" or pluriel(sorties, "sortie réussie", "sorties réussies")) end
  entete("LE CUBE", CYAN, sous)
  -- dans le Cube : avancée vers la sortie
  local px, pw = 3, W - 4
  panneau(px, 11, pw, 11, "DANS LE CUBE · VERS LA SORTIE")
  local cP, cN, cS, cM = px + 39, px + 46, px + 55, px + pw - 3
  if #dedans > 0 then for _, c in ipairs({ { cP, "%" }, { cN, "NIV." }, { cS, "SALLES" }, { cM, "MORTS" } }) do adroite(c[1], 12, c[2], DISCRET, PAN) end end
  local y = 14
  if #dedans == 0 then txt(px + 2, y, vestibule > 0 and "Personne dans les salles : " .. pluriel(vestibule, "joueur attend", "joueurs attendent") .. " au vestibule." or "Personne dans le Cube. Le vestibule blanc attend...", GRIS, PAN) end
  for i, j in ipairs(dedans) do
    if i > 7 then adroite(cM, 21, "+ " .. (#dedans - 7) .. " autres", DISCRET, PAN) break end
    ecr(px + 2, y, court(j.nom, 14), BLANC, PAN)
    barre(px + 18, y, cP - px - 23, num(j.avance) / 100, CYAN, TRAIT)
    adroite(cP, y, ent(j.avance) .. " %", BLANC, PAN)
    adroite(cN, y, ent(j.niveau) .. "/" .. ent(num(d.niveaux) > 0 and d.niveaux or 10), GRIS, PAN)
    adroite(cS, y, nombre(j.salles), CYAN, PAN)
    adroite(cM, y, nombre(j.morts), GRIS, PAN)
    y = y + 1
  end
  -- records
  local lg = math.floor((pw - 2) / 2)
  panneau(px, 24, lg, H - 26, "LE PLUS LOIN · SALLES")
  y = 27
  local loin = d.loin or {}
  if #loin == 0 then txt(px + 2, y, "Personne n'a encore quitté", GRIS, PAN) txt(px + 2, y + 1, "la salle blanche.", GRIS, PAN) end
  for i, t in ipairs(loin) do
    if i > 8 or y > H - 4 then break end
    rang(px + 3, y, i, PAN) ecr(px + 5, y, court(t.nom, 16), BLANC, PAN) adroite(px + lg - 3, y, nombre(t.salles), CYAN, PAN)
    y = y + 1
  end
  local qx, qw = px + lg + 2, pw - lg - 2
  panneau(qx, 24, qw, H - 26, "LES PLUS RAPIDES")
  y = 27
  local vite = d.vite or {}
  if #vite == 0 then txt(qx + 2, y, "Aucune sortie encore.", GRIS, PAN) txt(qx + 2, y + 1, "La première attend son nom.", DISCRET, PAN) end
  for i, t in ipairs(vite) do
    if i > 8 or y > H - 4 then break end
    rang(qx + 3, y, i, PAN) ecr(qx + 5, y, court(t.nom, 16), BLANC, PAN) adroite(qx + qw - 3, y, duree(t.temps), CYAN, PAN)
    y = y + 1
  end
  pied("à gauche du hub, par le pont")
end

------------------------------------------------------------------------------------------------ Serveurs
local TUILES = {
  { "hub", "LOBBIK", "Le hub · minecraft.lobbik.com" },
  { "tour", "LA TOUR", "Parkour vers le ciel" },
  { "cube", "LE CUBE", "Labyrinthe de 1 000 salles" },
  { "lave", "MER DE LAVE", "Sauts au-dessus de la lave" },
  { "cs2", "COUNTER-STRIKE 2", "Serveur Lobbik" },
  { "valheim", "VALHEIM", "Monde Lobbik" },
}
local function ecranServeurs()
  local total, connus = 0, 0
  for _, t in ipairs(TUILES) do local s = srv(t[1]) if s and s.en_ligne then total = total + num(s.joueurs) connus = connus + 1 end end
  entete("SERVEURS", VOLT, D and (pluriel(total, "joueur", "joueurs") .. " en ce moment sur Lobbik") or "Chargement...")
  local tw, th = math.floor((W - 6) / 2), 9
  for i, t in ipairs(TUILES) do
    local x, y = 3 + ((i - 1) % 2) * (tw + 2), 10 + math.floor((i - 1) / 2) * (th + 1)
    local s = srv(t[1])
    local couleur, etat = DISCRET, "..."
    if t[1] == "lave" and not s then couleur, etat = OR, "Bientôt"
    elseif s and s.en_ligne then couleur, etat = VOLT, "En ligne"
    elseif s then couleur, etat = ROUGE, "Hors ligne" end
    rect(x, y, tw, th, PAN)
    for yy = y, y + th - 1 do sub(x, yy, BARRE_G, couleur) end
    txt(x + 3, y + 1, t[2], BLANC, PAN)
    local ex = adroite(x + tw - 3, y + 1, etat, couleur == DISCRET and DISCRET or couleur, PAN)
    ecr(ex - 1, y + 1, "\7", couleur, PAN)
    local detail = t[3]
    if t[1] == "tour" and D and D.tour and num(D.tour.sommet) > 0 then detail = "Parkour · " .. nombre(D.tour.sommet) .. " niveaux" end
    if t[1] == "cs2" and s and s.carte and s.carte ~= "" then detail = "Carte " .. s.carte end
    if t[1] == "lave" and D and D.lave and num(D.lave.longueur) > 0 then detail = "Parcours de " .. nombre(D.lave.longueur) .. " blocs" end
    txt(x + 3, y + 2, detail, GRIS, PAN)
    if s and s.en_ligne then
      local n = nombre(s.joueurs):gsub(" ", "")
      local fin = grand(x + 3, y + 3, n, 2, BLANC, 2)
      local rx = fin + 2
      txt(rx, y + 4, ent(s.joueurs) > 1 and "joueurs" or "joueur", GRIS, PAN)
      if num(s.places) > 0 then
        txt(rx, y + 5, "sur " .. nombre(s.places) .. " places", DISCRET, PAN)
        barre(rx, y + 6, x + tw - 3 - rx, num(s.joueurs) / num(s.places), num(s.joueurs) >= num(s.places) and ROUGE or VOLT, TRAIT)
      end
      if num(s.attente) > 0 then adroite(x + tw - 3, y + 4, "file : " .. nombre(s.attente), OR, PAN) end
    elseif t[1] == "lave" and not s then
      txt(x + 3, y + 4, "Le 4e jeu du réseau arrive :", GRIS, PAN)
      txt(x + 3, y + 5, "un pont au sud du hub y mènera.", DISCRET, PAN)
    else
      txt(x + 3, y + 4, s and "Le serveur ne répond pas." or "En attente des nouvelles...", GRIS, PAN)
    end
  end
  pied("liez votre compte sur lobbik.com pour passer les portes")
end

------------------------------------------------------------------------------------------------ En direct (progression de chaque joueur, jeu par jeu)
local function ecranDirect()
  local total = 0
  for _, k in ipairs({ "tour", "cube", "lave" }) do local s = srv(k) if s and s.en_ligne then total = total + num(s.joueurs) end end
  entete("EN JEU", VOLT, D and ("La progression de chaque joueur, jeu par jeu · " .. pluriel(total, "joueur", "joueurs") .. " en jeu") or "Chargement...")
  local sh = math.floor((H - 11) / 3)
  local function section(i, nom, couleur, cle, lignes, vide, bientot)
    local x, y, w = 3, 10 + (i - 1) * (sh + 1), W - 4
    local s = srv(cle)
    rect(x, y, w, sh, PAN)
    for yy = y, y + sh - 1 do sub(x, yy, BARRE_G, couleur) end
    txt(x + 3, y + 1, nom, couleur, PAN)
    if bientot then adroite(x + w - 3, y + 1, "bientôt", OR, PAN)
    elseif s and s.en_ligne then adroite(x + w - 3, y + 1, nombre(#lignes) .. " en jeu", GRIS, PAN)
    elseif s then adroite(x + w - 3, y + 1, "hors ligne", ROUGE, PAN) end
    local yy, max = y + 3, sh - 4
    if #lignes == 0 then txtMax(x + 3, yy, vide, x + w - 3, GRIS, PAN) return end
    for n, l in ipairs(lignes) do
      if n > max or (n == max and #lignes > max) then adroite(x + w - 3, yy, "+ " .. (#lignes - n + 1) .. " autres", DISCRET, PAN) break end
      ecr(x + 3, yy, court(l.nom, 16), BLANC, PAN)
      barre(x + 21, yy, 26, l.frac, couleur, TRAIT)
      local fx = txt(x + 50, yy, l.valeur, BLANC, PAN)
      if l.detail then txtMax(fx + 1, yy, l.detail, x + w - 3 - (l.pips and 6 or 0), GRIS, PAN) end
      if l.pips then for p = 1, l.pips[2] do ecr(x + w - 3 - l.pips[2] + p, yy, "\4", p <= l.pips[1] and OR or TRAIT, PAN) end end
      yy = yy + 1
    end
  end
  -- La Tour : niveau sur le sommet
  local d, s = D and D.tour or {}, srv("tour")
  local sommet, l = math.max(1, num(d.sommet)), {}
  for _, j in ipairs((s and s.en_ligne and frais(d)) and d.joueurs or {}) do
    table.insert(l, { nom = j.nom, n = num(j.niveau), frac = num(j.niveau) / sommet, valeur = nombre(j.niveau) .. " / " .. nombre(sommet), detail = "rec. " .. nombre(j.record) })
  end
  table.sort(l, function(a, b) return a.n > b.n end)
  local top = d.top and d.top[1]
  section(1, "LA TOUR", VOLT, "tour", l, top and ("Personne ne grimpe. Record : " .. nombre(top.record) .. " niveaux, par " .. top.nom .. ".") or "Personne ne grimpe en ce moment.")
  -- Le Cube : salle, niveau, avancée
  d, s, l = D and D.cube or {}, srv("cube"), {}
  local niveaux = num(d.niveaux) > 0 and num(d.niveaux) or 10
  for _, j in ipairs((s and s.en_ligne and frais(d)) and d.joueurs or {}) do
    if j.dedans then table.insert(l, { nom = j.nom, n = num(j.avance), frac = num(j.avance) / 100, valeur = ent(j.avance) .. " %",
      detail = "niv. " .. ent(j.niveau) .. "/" .. ent(niveaux) .. " · " .. pluriel(j.salles, "salle", "salles") })
    else table.insert(l, { nom = j.nom, n = -1, frac = 0, valeur = "au vestibule" }) end
  end
  table.sort(l, function(a, b) return a.n > b.n end)
  local loin = d.loin and d.loin[1]
  section(2, "LE CUBE", CYAN, "cube", l, loin and ("Personne dans le Cube. Le plus loin : " .. nombre(loin.salles) .. " salles, par " .. loin.nom .. ".") or "Personne dans le Cube en ce moment.")
  -- Mer de lave : progression en blocs, points de réapparition franchis (0 à 4)
  d, s, l = D and D.lave or nil, srv("lave"), {}
  local bientot = not d and not s
  d = d or {}
  local longueur = num(d.longueur) > 0 and num(d.longueur) or 800
  for _, j in ipairs((s and s.en_ligne and frais(d)) and d.joueurs or {}) do
    table.insert(l, { nom = j.nom, n = num(j.progres), frac = num(j.progres) / longueur, valeur = nombre(j.progres) .. " / " .. nombre(longueur), detail = "blocs", pips = { math.min(4, ent(j.reap)), 4 } })
  end
  table.sort(l, function(a, b) return a.n > b.n end)
  section(3, "MER DE LAVE", LAVE, "lave", l, bientot and "Le 4e jeu du réseau : 800 blocs de sauts sur la lave, au sud du hub." or "Personne sur la lave en ce moment.", bientot)
  pied("mis à jour toutes les 2 s")
end

------------------------------------------------------------------------------------------------ boucle
local ECRANS = { tour = ecranTour, cube = ecranCube, serveurs = ecranServeurs, direct = ecranDirect }
local precedent, dernierEtat, erreur = {}, -1e12, nil

local function vers8(s)   -- Latin-1 → UTF-8 pour etat.txt ; télétexte → « # »
  return (s:gsub("[\128-\255]", function(c) local b = c:byte() if b < 160 then return "#" end return string.char(0xC0 + math.floor(b / 64), 0x80 + b % 64) end))
end
local function publier()
  local change = false
  for y = 1, H do
    local t, f, b = table.concat(T[y]), table.concat(F[y]), table.concat(B[y])
    local l = t .. "\0" .. f .. "\0" .. b
    if precedent[y] ~= l then mon.setCursorPos(1, y) mon.blit(t, f, b) precedent[y] = l change = true end
  end
  -- résumé lisible (et image brute pour les captures) toutes les 10 s au plus
  if change and maintenant() - dernierEtat > 10000 then
    dernierEtat = maintenant()
    local lignes = { "ecran-hub.lua " .. VERSION .. " · role " .. ROLE .. " · moniteur " .. W .. "x" .. H .. " (echelle " .. ECHELLE .. ")",
      "donnees : " .. source .. (ageDonnees and string.format(" (age %.1f s)", ageDonnees / 1000) or "") .. " · http : " .. etatHttp,
      "maj : " .. tostring(maintenant()) .. (erreur and (" · erreur : " .. erreur) or ""), string.rep("-", W) }
    local brut = { W .. " " .. H }
    local pal = {} for k, v in pairs(PALETTE) do table.insert(pal, k .. "=" .. string.format("%06x", v)) end
    table.insert(brut, table.concat(pal, " "))
    for y = 1, H do table.insert(lignes, vers8(table.concat(T[y]))) table.insert(brut, table.concat(T[y])) table.insert(brut, table.concat(F[y])) table.insert(brut, table.concat(B[y])) end
    ecrireFichier("etat.txt", table.concat(lignes, "\n") .. "\n")
    ecrireFichier("image.blit", table.concat(brut, "\n") .. "\n")
  end
end
local function preparer()
  mon.setTextScale(ECHELLE)
  for k, v in pairs(PALETTE) do mon.setPaletteColour(math.floor(2 ^ tonumber(k, 16) + 0.5), v) end
  W, H = mon.getSize()
  precedent = {}
  mon.setBackgroundColour(colours.black) mon.clear()
end
local function attendre(s)   -- insensible à « terminate » : l'écran ne s'arrête jamais
  local id = os.startTimer(s)
  repeat local e, p = os.pullEventRaw() until e == "timer" and p == id
end

local function image()
  local w, h = mon.getSize()
  if w ~= W or h ~= H then preparer() end
  charger()
  battement = not battement
  nouvelle(FOND)
  local f = ECRANS[ROLE] or ecranServeurs
  local ok, err = pcall(f)
  erreur = not ok and tostring(err) or nil
  if not ok then nouvelle(FOND) txt(3, 2, "Écran Lobbik · " .. ROLE, VOLT) txt(3, 4, "Erreur : " .. tostring(err), ROUGE) txt(3, 6, "Nouvel essai dans " .. PERIODE .. " s.", GRIS) end
  publier()
end

-- au démarrage : la règle HTTP de CC laisse-t-elle passer lobbik.com ? (l'écran des serveurs fait aussi un vrai appel)
if http and http.checkURL then
  local ok, raison = http.checkURL(API .. "mc_serveur")
  local regle = ok and "lobbik.com autorisé" or ("lobbik.com refusé : " .. tostring(raison))
  etatHttp = regle
  if ok and ROLE == "serveurs" then get("cs_serveur") etatHttp = regle .. " · essai " .. etatHttp end
end

while true do
  mon = peripheral.find("monitor")
  if mon then
    preparer()
    while true do
      local ok, err = pcall(image)
      if not ok then erreur = tostring(err) print("Erreur : " .. erreur) if not peripheral.find("monitor") then break end end
      attendre(PERIODE)
    end
  else
    print("Écran Lobbik (" .. ROLE .. ") : aucun moniteur branché, nouvel essai dans 5 s.")
    attendre(5)
  end
end
