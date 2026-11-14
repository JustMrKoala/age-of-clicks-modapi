Age of Clicks Mod Loader (unofficial) - tester build
=====================================================

Install
  1. Close Age of Clicks.
  2. Double-click Install.bat. It finds the game through Steam (or asks for the folder),
     copies the loader and mods, and adds one line to the game's launch settings.
  3. Start the game normally through Steam.

What you get
  - Main menu > MODS > CODE MODS: every code mod, with its settings.
  - Game Improvements (in CODE MODS): army numbers along the fronts at war, zooming further out, Bulk Cities; each improvement
    has its own switch.
  - CODE MODS > Browse mods: the Mod Browser, to install, update or remove code mods.
  - AI Diplomacy: in a game press F8 (or the "Diplomacy" button under the flag) and talk to
    any country's leader. It needs an API key: Code Mods > AI Diplomacy > API key.
    Free Groq keys: https://console.groq.com/keys
  - Developer tools (Code Mods > Age of Clicks Mod Loader): an in-game Action Tester.
  - MULTIPLAYER in the main menu (switched on by the installer): online lobbies on the central
    server - nothing to set up. Pick a lobby and Join, or Create & join your own (name, scenario,
    players needed). In the game's lobby choose a country and press Ready; the match starts when
    enough players are ready (the creator can also press "Start the match now").
    The same window also has the local-network lobby and joining by address. No Steam networking.
    Code mods (like AI Diplomacy) switch off automatically during multiplayer games.
  - server\: a dedicated server for Windows or Linux (see server\SERVER-README.txt).

After a Steam update
  Steam may restore the game's launch settings. Run Install.bat again.

Uninstall
  Double-click Uninstall.bat. Your mod settings stay in the mods folder.

Problems?
  Send mods/aoc-loader.log from the game folder (it lists every action the mods ran).
  Note: deals and world actions mark a game as externally modified, which can switch off
  achievements for that save.
