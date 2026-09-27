-- Écran Lobbik (L'Atelier) : scores en direct de La Tour et du Cube, lus sur https://lobbik.com toutes les 10 s.
-- Modifiable par les admins ; les joueurs peuvent écrire les leurs avec http.get sur la même adresse.
local mon = peripheral.find("monitor")
if not mon then print("Aucun écran branché") return end
mon.setTextScale(0.5)
local function lire(a)
  local r = http.get("https://lobbik.com/auth/steam.php?a=" .. a)
  if not r then return nil end
  local t = r.readAll() r.close()
  return textutils.unserializeJSON(t)
end
while true do
  pcall(function()
    local t, c = lire("mc_serveur"), lire("mc_cube")
    mon.setBackgroundColor(colors.black) mon.clear()
    local w, h = mon.getSize()
    local y = 2
    local function ligne(x, texte, couleur) mon.setCursorPos(x, y) mon.setTextColor(couleur or colors.white) mon.write(texte) end
    ligne(2, "LA TOUR", colors.lime) ligne(math.floor(w / 2) + 2, "LE CUBE", colors.lightBlue) y = y + 2
    local depart = y
    if t then
      local en = t.positions or {}
      ligne(2, #en .. " en ligne", colors.lightGray) y = y + 1
      for i, j in ipairs(en) do if i > 4 then break end ligne(2, j.nom .. "  niv " .. (j.niveau or 0), colors.cyan) y = y + 1 end
      y = y + 1 ligne(2, "Records", colors.yellow) y = y + 1
      for i, j in ipairs(t.top or {}) do if i > 8 then break end ligne(2, i .. ". " .. j.nom .. "  " .. (j.record or 0), colors.white) y = y + 1 end
    end
    y = depart
    if c then
      local s = c.stats or {}
      ligne(math.floor(w / 2) + 2, (s.sorties or 0) .. " sorties, " .. (s.morts or 0) .. " morts", colors.lightGray) y = y + 2
      ligne(math.floor(w / 2) + 2, "Le plus loin", colors.yellow) y = y + 1
      for i, j in ipairs(c.braves or {}) do if i > 8 then break end ligne(math.floor(w / 2) + 2, i .. ". " .. j.nom .. "  " .. (j.salles_max or 0) .. " salles", colors.white) y = y + 1 end
    end
    mon.setCursorPos(2, h) mon.setTextColor(colors.gray) mon.write("lobbik.com")
  end)
  sleep(10)
end
