# Zene Remote

Kis Android app: a telefonon egy webszervert futtat (port **1821**), amin keresztül
más eszközről (pl. iPhone Safari) vezérelhető a YouTube Music.

Tud: keresés a YouTube Musicban (dalok, borítóval), koppintásra lejátszás,
előző / lejátszás-szünet / következő, hangerő, "most szól" kijelzés.
A keresőbe YouTube Music linket is be lehet illeszteni.

Nincs külső függősége, csak az Android SDK. Android 8.0+ (minSdk 26).

## APK fordítása Android Studio nélkül (GitHub Actions)

1. Hozz létre egy új (akár privát) repót a GitHubon, és told fel ezt a mappát:
   ```
   git init
   git add .
   git commit -m "Zene Remote"
   git branch -M main
   git remote add origin https://github.com/<felhasznalo>/zene-remote.git
   git push -u origin main
   ```
2. GitHub → repó → **Actions** fül → "Build APK" → a legutóbbi futás → lent az
   **Artifacts** alatt töltsd le a `ZeneRemote-apk` zipet. Benne van az `app-debug.apk`.

(Ha inkább Android Studióval: File → Open → ez a mappa → Run.)

## Telepítés a Honor 90-re

1. Másold át az `app-debug.apk`-t a telefonra, nyisd meg, és engedélyezd az
   "ismeretlen forrásból" való telepítést a fájlkezelőnek.
2. Nyisd meg a **Zene Remote** appot, és nyomd meg a gombokat, amíg mindegyik ✅ nem lesz:
   - **Értesítés-hozzáférés**: enélkül nem tudja vezérelni a YouTube Musicot
   - **Más appok feletti megjelenítés**: enélkül a "link indítás" a háttérből nem megy
   - **Akkumulátor-optimalizálás kikapcsolása**
3. MagicOS: Beállítások → Akkumulátor → Alkalmazásindítás → Zene Remote →
   **Kézi kezelés**, és mindhárom kapcsoló legyen bekapcsolva.
4. **Szerver indítása**. Az app kiírja a címeket:
   - `wlan0` → otthoni Wi-Fi cím (pl. `http://192.168.1.23:1821`)
   - `tun0` → Tailscale cím (`http://100.x.y.z:1821`), ha fut a Tailscale
   - Helyi név: `http://zene.local:1821` – otthoni Wi-Fi-n és a saját hotspotodon
     is működik, IP nélkül. A nevet az appban át lehet írni.

## Linda iPhone-ján

Safari → a fenti cím → Megosztás → **Főképernyőhöz adás**. Ettől kezdve appként indul.

## Megjegyzések

- A routerben adj fix IP-t a telefonnak (DHCP foglalás), hogy a cím ne változzon.
- Nincs jelszó: aki ugyanazon a Wi-Fi-n van, az tudja léptetni a zenét.
  Otthon ez rendben van, nyilvános Wi-Fi-n ne futtasd.
- A "link indítás" megnyitja a YouTube Music appot a telefonodon; hogy rögtön
  el is kezdi-e játszani, az a YouTube Music viselkedésén múlik.
