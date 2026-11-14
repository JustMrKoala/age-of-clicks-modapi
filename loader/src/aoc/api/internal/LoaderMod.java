package aoc.api.internal;

import aoc.api.AocMod;
import aoc.api.ModContext;
import aoc.api.settings.Setting;
import aoc.api.ui.Button;
import aoc.api.ui.Ui;

/**
 * The loader's own entry in Code Mods: its settings, the developer tools for testing mods,
 * and the switches for the game's unfinished built-in multiplayer.
 * Registered by {@link ModManager} before any mod jar.
 */
final class LoaderMod implements AocMod {
    private static volatile Setting<Boolean> allowSteam;
    private static volatile Setting<Boolean> experimentalMultiplayer;
    private static volatile Setting<String> playerName;
    private static volatile Setting<String> centralServer;
    private static volatile Setting<String> modCatalog;
    private Setting<Boolean> devTools;
    private ActionTester tester;

    @Override public String id() { return "aoc-loader"; }

    @Override public String name() { return "Age of Clicks Mod Loader"; }

    @Override public String version() { return Version.LOADER; }

    @Override
    public String description() {
        return "Loads code mods and provides the mod API (actions, world, UI, settings). "
                + "Turn on developer tools to test actions in game.";
    }

    @Override
    public void onLoad(ModContext ctx) {
        devTools = ctx.settings().toggle("dev_tools", "Developer tools", false,
                "Shows an \"Action Tester\" button in game: run any API action by hand and see the game's answer.");
        experimentalMultiplayer = ctx.settings().toggle("experimental_multiplayer", "Experimental: built-in multiplayer", false,
                "Adds MULTIPLAYER to the main menu: online lobbies on the central server, your local network, or a "
                        + "direct address, using the game's own unfinished multiplayer. Takes effect after a restart.");
        allowSteam = ctx.settings().toggle("allow_steam_networking", "Allow Steam networking", false,
                "Off (recommended): the multiplayer lobby uses only LAN and direct IP, and Steam lobby invites are ignored. "
                        + "On: the game's lobby may host and join through Steam.");
        playerName = ctx.settings().text("mp_player_name", "Multiplayer name", "Player",
                "The name other players see in the lobby.");
        centralServer = ctx.settings().text("central_server", "Central server", Version.CENTRAL_SERVER,
                "Address of the lobby server that hosts online matches (host:port).");
        modCatalog = ctx.settings().text("mod_catalog", "Mod catalog", ModCatalog.DEFAULT_URL,
                "Address of the mod list the Mod Browser shows (an https link to an index.json).");
    }

    @Override
    public void onGameReady(ModContext ctx) {
        tester = new ActionTester(ctx);
        ctx.ui().addHud(new Button("Action Tester").onClick(tester::toggle), Ui.Anchor.TOP_LEFT,
                () -> devTools.get() && ctx.game().inGame());
    }

    @Override
    public void render(ModContext ctx, float delta) {
        if (tester != null && !ctx.game().inGame()) tester.close();
    }

    static boolean steamNetworkingAllowed() {
        Setting<Boolean> s = allowSteam;
        return s != null && s.get();
    }

    static boolean experimentalMultiplayer() {
        Setting<Boolean> s = experimentalMultiplayer;
        return s != null && s.get();
    }

    static String modCatalog() {
        Setting<String> s = modCatalog;
        String url = s == null ? "" : s.get().trim();
        return url.isEmpty() ? ModCatalog.DEFAULT_URL : url;
    }

    static Setting<String> playerName() { return playerName; }

    static Setting<String> centralServer() { return centralServer; }
}
