# Installing (players)

## Install

1. Close Age of Clicks.
2. Run **AgeOfClicks-ModAPI-Setup-0.8.0.exe** (or unzip `AgeOfClicks-ModAPI-0.8.0.zip` and
   double-click `Setup.bat`). Windows SmartScreen may warn because the file is not signed:
   *More info > Run anyway*.
3. The setup window finds the game through Steam. If it says *No Age of Clicks here*, click
   **Browse...** and pick the game folder (Steam: right-click Age of Clicks > *Manage* >
   *Browse local files*).
4. Pick what you want:
   - **AI Diplomacy mod**: talk to country leaders in plain language (needs an API key, see below).
   - **Online multiplayer**: adds **MULTIPLAYER** to the main menu. The *Central server* box is
     already filled in; leave it unless someone gave you a different address.
5. Click **Install / Update**, then start the game through Steam as usual.

The game's own **MODS** screen (main menu > MODS) now has a **CODE MODS** button next to Back:
every code mod with its settings. **Browse mods** there opens the **Mod Browser**: the list of
available code mods, with Install, Update and Remove (changes load the next time the game
starts; it needs an internet connection to download). The main menu gets **REPLAYS** and, with multiplayer on,
**MULTIPLAYER**.

### Without the window

From PowerShell in the unzipped folder:

```powershell
powershell -ExecutionPolicy Bypass -File installer\install.ps1 -GameDir "D:\Games\Age of Clicks"
```

Options: `-NoDiplomacy`, `-NoMultiplayer`, `-CentralServer host:port`, `-Quiet` (never asks,
fails instead). `Install.bat` runs the same script with questions.

## Installing on a friend's PC

Everybody who plays together needs the mod loader on their own copy of the game (bought on
Steam; the installer never copies the game itself).

- **They do it:** send them the Setup .exe (or the zip). They run it, keep *Online multiplayer*
  ticked, click Install. Nothing else to set up.
- **You do it on their PC:** same steps. If the game is not in Steam's default folder, use
  **Browse...**.
- **Different game version:** the installer compares the game's `app/desktop-*.jar` with the one
  the loader was built for and warns if they differ. It usually still works; if the game
  misbehaves, uninstall and ask for an updated build.
- **No Steam at all** (a copy that starts without Steam): run Setup and use **Browse...** to point
  at the folder that has `Age of Clicks.exe`.

## Playing online

Main menu > **MULTIPLAYER**:

- **Online lobbies**: games waiting on the central server. Pick one and **Join**.
- **Create & join**: name, scenario and how many players. The match starts when that many have
  joined, or when the creator clicks **Start the match now**.
- **LAN**: the game's own lobby for players on the same network.
- **Join by address**: a server someone hosts themselves (`host:port`).

Code mods are switched off while a multiplayer game or lobby is active, for everybody, so nobody
gets an advantage from them. Nothing goes through Steam's networking.

The central server address is a setting: main menu > MODS > CODE MODS > *Age of Clicks Mod Loader* >
*Central server*. It is either `host:port` for a server that is reached directly, or a web address
such as `https://play.example.org` for a server behind a web tunnel; matches then go through that
address too, so the server needs no open ports.

## AI Diplomacy key

1. Get a free key at <https://console.groq.com/keys> (or use any OpenAI-compatible service).
2. Main menu > MODS > CODE MODS > *AI Diplomacy*: paste the key, Save.
3. In a game press **F8**, or click *Diplomacy* at the left edge.

## Replays

The bundled *Replays* mod films every single-player game while you play, with the game's own
graphics, from a cinematic camera: it stays on your country, follows your wars where land changes
hands (army flags with troop counts, besieged cities' garrison bars), cuts away to big wars elsewhere,
and adds the date, your flag and country stats and news captions. During your wars the camera holds one
steady zoom on the fighting and drifts with it. Peace goes by quicker than wars; how slowly a war can
play depends on your game speed (fully slow at x1 and x2, about 10 game days per film second at x4,
faster at x8 and x16, since the film can only show frames the game draws). That is why, while your
own country is at war, the game itself is held at x2 and gets your speed back when the war ends
(setting *Game speed during your own wars*: off, x1, x2, x4, x8). You never see the film camera;
your own view and map mode are untouched.
Films go to `Videos\Age of Clicks Replays` as MP4. Main menu > **REPLAYS** plays them
in the game (Space pause, Left/Right 10 s, Esc close).

It needs **ffmpeg** (ffmpeg.org, or `scoop install ffmpeg`). Films are encoded on the graphics card
when it can (NVIDIA, AMD or Intel), otherwise on the processor. Filming takes at most about a third of
the game's frame time (40% during your wars); play fullscreen for the sharpest film. If the game stutters while filming,
lower *Film frames per real second* in MODS > CODE MODS > Replays.
Like every mod, it is off in multiplayer.

## Bulk Cities

The bundled *Bulk Cities* mod founds many cities quickly. Click the game's own *Found City* button and
then the map: found-city mode stays on, so every click is a new city; right-click or Esc
stops. You are not asked for names: each city takes the real town that lies at that spot (from the
city lists of all the game's maps, about 17,000 towns), or, where none is known, a made-up name in the
style of the region's towns. The game's own rules and costs apply to every city.

In the game's **Economy** menu, the Cities section has a *Bulk Cities* row right under *Found city*.
It opens a window that founds the real towns of your land at their real places: 5, 20, or as many as
you can afford. Settings (MODS > CODE MODS > *Bulk Cities*): name automatically or
just fill in the dialog, keep founding after each city, prefer real names.

## After a Steam update

A game update can replace `app/Age of Clicks.cfg`, which holds the line that starts the loader.
### Game Improvements

Built into the loader: improvements to the game itself, each with its own switch under MODS >
CODE MODS > *Game Improvements*.

- **Army numbers on the fronts**: at war, each side's army on a front is written on its own land
  next to the front, turned along it, in both combat modes. A country's army (infantry + artillery
  + tanks) is split over its fronts by its frontline flags and divisions facing each enemy.
- **Zoom further out**: zoom further out of the map than the game allows (*Zoom-out limit*) and
  scroll past its edges (*Scroll past the edges*). This used to be the separate Zoom Out mod; the
  loader removes the old `zoomout.jar` by itself.

If CODE MODS disappears from main menu > MODS, run Setup again and click Install / Update.

## Uninstall

Setup > **Uninstall**. It asks whether to delete the code mods too; mod settings in
`mods/<mod id>/` stay either way. The game's launch settings go back to the original
(`app/Age of Clicks.cfg.vanilla`). Steam's *Verify integrity of game files* also undoes it.

## Problems

| Symptom | Fix |
|---|---|
| No CODE MODS button in main menu > MODS | Run Setup again (a game update reset the launch settings) |
| Setup says *No Age of Clicks here* | Browse to the folder with `Age of Clicks.exe` |
| Setup cannot write files | The game is still running, or the folder needs admin rights: close the game; right-click Setup > *Run as administrator* |
| MULTIPLAYER shows no lobbies | Check *Central server* in MODS > CODE MODS; the server may be offline |
| Anything else | Send `<game>/mods/aoc-loader.log` |
