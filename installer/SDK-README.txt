Age of Clicks Mod API - SDK for mod makers (unofficial)
=======================================================

docs\README.md        start here: guides for players and modders
docs\javadoc\         every public class and method (open index.html)
template\             a complete example mod with build scripts

Quick start
-----------
1. Install the mod loader into your game (AgeOfClicks-ModAPI-Setup.exe).
2. Install a JDK 17 or newer (for example Eclipse Temurin).
3. In template\ run:   build.bat install
   (set AOC_GAME=<your game folder> first if the game is not in Steam's default folder)
4. Start the game. Main menu > MODS > CODE MODS shows "Hello Mod"; in a game, click Hello or press F9.

Then read docs\getting-started.md to make the template your own.

Mods compile against two jars from your game folder: app\desktop-*.jar (the game) and
app\aoc-loader.jar (this API). Never ship either of them with your mod; ship only your own jar.
