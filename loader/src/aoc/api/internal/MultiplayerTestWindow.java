package aoc.api.internal;

import aoc.api.settings.Setting;
import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.ListView;
import aoc.api.ui.Panel;
import aoc.api.ui.Separator;
import aoc.api.ui.TextField;
import aoc.api.ui.Theme;
import aoc.api.ui.Ui;
import aoc.api.ui.Window;
import com.badlogic.gdx.Gdx;
import ru.ius.david.cgs.cgsGame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * Main menu > MULTIPLAYER: online lobbies on the central server (list, create, join), plus the
 * game's own LAN lobby and joining by address. Joining hands over to the game's lobby screen
 * (countries, Ready); the match itself runs on the central server, never through Steam.
 */
final class MultiplayerTestWindow {
    private static final float REFRESH_SECONDS = 3f;

    private final Ui ui;
    private Window window;
    private CentralServer server;
    private volatile CentralServer.Info info;
    private volatile List<CentralServer.Lobby> lobbies = List.of();
    private ListView<CentralServer.Lobby> list;
    private TextField name, lobbyName, address;
    private ListView<CentralServer.Scenario> scenarioList;
    private int needed = 2;
    private volatile String status = "";
    private volatile boolean statusBad;
    private float sinceRefresh = REFRESH_SECONDS;
    /** Address to join once the game's lobby screen is on screen; see {@link #finishJoin}. */
    private InetAddress pendingJoin;
    private float pendingFor;

    /** The lobby this player created and may start early. */
    private static volatile CentralServer createdServer;
    private static volatile String createdId, createdToken;
    private static volatile int createdPort;
    /** The central-server lobby this player joined (its match port; 0 for LAN / direct games). */
    private static volatile CentralServer joinedServer;
    private static volatile int joinedPort;
    /** The local relay port the game connects to when the lobby is reached through a tunnel, else 0. */
    private static volatile int joinedVia;
    private static volatile CentralServer.Lobby joinedLobby;
    private float sinceLobbyPoll = REFRESH_SECONDS;
    private final Label lobbyStatus = new Label(MultiplayerTestWindow::lobbyStatusText).font(Theme.Font.SMALL).center();
    private final Button startButton = new Button(() -> inCentralLobby() ? "Start the match now" : "Start without waiting")
            .primary().onClick(this::startCreatedLobby);
    /** The creator's player count in the lobby: when the match starts by itself. */
    private static volatile int createdNeeded;
    private final Panel neededRow = Panel.row().gap(8).height(34).add(
            new Button("-").width(44).tooltip("Start by itself with fewer players").onClick(() -> changeNeeded(-1)),
            new Label(() -> neededText(createdNeeded)).center().flex(1),
            new Button("+").width(44).tooltip("Wait for more players").onClick(() -> changeNeeded(1)));

    MultiplayerTestWindow(Ui ui) {
        this.ui = ui;
        // Over the game's lobby screen: who is here, and the creator's start button (the game's own
        // Start button belongs to the host, which is the match server).
        ui.addHud(Panel.column().background(Panel.Background.CARD).padding(10).gap(8).width(560)
                        .add(lobbyStatus, neededRow, startButton),
                Ui.Anchor.TOP_CENTER, 0, 20, this::showLobbyPanel);
    }

    boolean showLobbyPanel() {
        boolean inLobby = inCentralLobby(), canStart = canStartOwnLobby();
        lobbyStatus.visible(inLobby);
        neededRow.visible(inLobby && canStart);
        startButton.visible(canStart);
        return inLobby || canStart;
    }

    void open() {
        if (window == null) build();
        server = new CentralServer(centralAddress());
        refreshInfo();
        sinceRefresh = REFRESH_SECONDS;
        ui.open(window);
    }

    /** Called every frame by ModManager. */
    void tick(float delta) {
        if (pendingJoin != null) finishJoin(delta);
        pollJoinedLobby(delta);
        if (window == null || !window.isOpen()) return;
        sinceRefresh += delta;
        if (sinceRefresh >= REFRESH_SECONDS) {
            sinceRefresh = 0;
            server.lobbies().whenComplete((l, err) -> {
                if (err == null) lobbies = l;
                else if (info != null) setStatus("Lost contact with the central server: " + reason(err), true);
            });
        }
    }

    private static String centralAddress() {
        Setting<String> s = LoaderMod.centralServer();
        return s == null ? Version.CENTRAL_SERVER : s.get();
    }

    private void build() {
        window = new Window("Multiplayer", 1020, 760);
        Setting<String> saved = LoaderMod.playerName();
        name = new TextField().text(saved == null ? "Player" : saved.get()).maxLength(24);

        list = new ListView<CentralServer.Lobby>()
                .items(() -> lobbies)
                .label(l -> l.name() + "  -  " + l.scenario() + (l.host().isEmpty() ? "" : "  (host " + l.host() + ")"))
                .note(l -> l.started() ? new ListView.Note("playing, day " + l.day(), Theme.textMuted())
                        : new ListView.Note(l.needed() > 0 ? l.players() + " / " + l.needed() + " players"
                                : l.players() + (l.players() == 1 ? " player" : " players"), l.players() > 0 ? Theme.good() : Theme.textMuted()))
                .flex(1);
        Label header = new Label(() -> info == null ? "Connecting to the central server..."
                : "Online lobbies on " + info.name() + "  (" + info.free() + " free)").font(Theme.Font.LARGE);
        Panel left = Panel.column().gap(8).flex(1).add(header, list,
                Panel.row().gap(8).height(36).add(
                        new Button("Join lobby").primary().width(160).enabled(() -> list.selected() != null && !list.selected().started())
                                .onClick(() -> join(list.selected().port(), null)),
                        Separator.spacer().flex(1),
                        new Label(() -> status).font(Theme.Font.SMALL).color(() -> statusBad ? Theme.bad() : Theme.textMuted()).width(380)));

        lobbyName = new TextField().placeholder("Lobby name").maxLength(32);
        scenarioList = new ListView<CentralServer.Scenario>()
                .items(() -> info == null ? List.of() : info.scenarios())
                .label(CentralServer.Scenario::title)
                .rowHeight(26)
                .flex(1);
        Button players = new Button(() -> neededText(needed))
                .tooltip("Click to change. The match can start by itself once this many players are in, or only when you press Start. You can change it in the lobby too, and start earlier there.")
                .onClick(() -> needed = needed >= maxPlayers() ? 0 : needed + 1);
        address = new TextField().placeholder("Address, e.g. 192.168.1.20").maxLength(64).onSubmit(t -> joinAddress());
        Panel right = Panel.column().gap(8).width(340).add(
                new Label("Your name").font(Theme.Font.SMALL), name,
                new Separator(),
                new Label("Create a lobby").font(Theme.Font.MAIN), lobbyName,
                new Label(() -> info == null ? "Map" : "Map (" + info.scenarios().size() + ")").font(Theme.Font.SMALL), scenarioList,
                players,
                new Button("Create & join").primary().enabled(() -> info != null && info.free() > 0).onClick(this::create),
                new Separator(),
                new Label("Local network or direct address").font(Theme.Font.MAIN),
                new Button("Open the LAN lobby (host / browse)").onClick(this::openLanLobby),
                Panel.row().gap(8).height(34).add(address.flex(1), new Button("Join").width(90).onClick(this::joinAddress)));
        window.content().add(
                new Label("Matches run on the central server; nothing goes through Steam. Code mods are off in multiplayer.")
                        .font(Theme.Font.SMALL).muted(),
                Panel.row().gap(14).flex(1).add(left, new Separator().width(2), right));
    }

    private void refreshInfo() {
        if (centralAddress().isBlank()) {
            setStatus("No central server set: MODS > CODE MODS > Age of Clicks Mod Loader > Central server.", true);
            return;
        }
        server.info().whenComplete((i, err) -> {
            if (err == null) {
                info = i;
                if (scenarioList.selected() == null && !i.scenarios().isEmpty()) scenarioList.select(i.scenarios().get(0));
                setStatus("", false);
            } else {
                setStatus("Central server unreachable (" + centralAddress() + "): " + reason(err), true);
            }
        });
    }

    private void create() {
        CentralServer.Info i = info;
        if (i == null || i.scenarios().isEmpty()) return;
        CentralServer.Scenario picked = scenarioList.selected();
        if (picked == null || !i.scenarios().contains(picked)) {
            setStatus("Pick a map first.", true);
            return;
        }
        String scenario = picked.path();
        String title = lobbyName.text().trim().isEmpty() ? name.text().trim() + "'s lobby" : lobbyName.text().trim();
        setStatus("Creating the lobby...", false);
        CentralServer s = server;
        int count = needed;
        s.create(title, scenario, count, name.text().trim()).whenComplete((c, err) -> Gdx.app.postRunnable(() -> {
            if (err != null) {
                setStatus(reason(err), true);
                return;
            }
            createdServer = s;
            createdId = c.id();
            createdToken = c.token();
            createdPort = c.port();
            createdNeeded = count;
            // The match server needs a moment to load before it accepts players.
            new Thread(() -> {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ignored) {
                    // join anyway
                }
                Gdx.app.postRunnable(() -> join(c.port(), null));
            }, "aoc-join-delay").start();
            setStatus("Lobby created; joining...", false);
        }));
    }

    /** Joins a central-server lobby on {@code port}, or a direct host when {@code host} is given. */
    private void join(int port, String host) {
        saveName();
        closeRelay();
        String via = host == null ? server.relayFor(port) : null;
        String target = host != null ? host : server.host();
        if (via != null) {
            // Behind a web tunnel: the game talks to a relay on this machine, which carries it to the server.
            try {
                relay = TunnelRelay.open(via);
            } catch (Exception e) {
                setStatus("Could not start the connection to " + target + ": " + e.getMessage(), true);
                Log.error("Tunnel relay failed to start", e);
                return;
            }
            Hooks.setClientPorts(relay.port(), relay.port());
            target = "127.0.0.1";
            Log.info("Joining through the tunnel: " + via + " via local port " + relay.port());
        } else if (host == null) Hooks.setClientPorts(port, port);
        else Hooks.setClientPorts(54555, 54777);
        joinedServer = host == null ? server : null;
        joinedPort = host == null ? port : 0;
        joinedVia = via != null ? relay.port() : 0;
        joinedLobby = null;
        sinceLobbyPoll = REFRESH_SECONDS;
        setStatus("Connecting to " + (via != null ? server.host() : target) + "...", false);
        String lookup = target;
        new Thread(() -> {
            try {
                InetAddress addr = InetAddress.getByName(lookup);
                Gdx.app.postRunnable(() -> {
                    try {
                        showLobby();
                        pendingJoin = addr;
                        pendingFor = 0;
                    } catch (Throwable t) {
                        setStatus("Could not join: " + t, true);
                        Log.error("Join failed", t);
                    }
                });
            } catch (Exception e) {
                setStatus("Could not find " + lookup + ".", true);
            }
        }, "aoc-mp-lookup").start();
    }

    /**
     * The game switches to its lobby screen after a fade, and the screen's {@code show()} resets
     * it to the Host/Join menu. Joining before that would connect but leave the player on that
     * menu, so the join waits until the lobby screen is the active screen.
     */
    private void finishJoin(float delta) {
        InetAddress addr = pendingJoin;
        try {
            cgsGame game = (cgsGame) Gdx.app.getApplicationListener();
            Object lobby = lobbyScreen(game);
            if (lobby == null || game.getScreen() != lobby) {
                pendingFor += delta;
                if (pendingFor > 15f) {
                    pendingJoin = null;
                    Log.info("Join cancelled: the lobby screen did not open");
                }
                return;
            }
            pendingJoin = null;
            String n = name.text().trim();
            if (!n.isEmpty()) lobby.getClass().getMethod("setLocalPlayerName", String.class).invoke(lobby, n);
            Method join = lobby.getClass().getDeclaredMethod("onJoinRoom", InetAddress.class);
            join.setAccessible(true);
            join.invoke(lobby, addr);
            Log.info("Joining multiplayer game at " + addr.getHostAddress() + ":" + Hooks.clientTcpPort());
        } catch (Throwable t) {
            pendingJoin = null;
            Log.error("Join failed", t);
        }
    }

    private static TunnelRelay relay;

    private static void closeRelay() {
        TunnelRelay r = relay;
        relay = null;
        if (r != null) r.close();
    }

    private void joinAddress() {
        String host = address.text().trim();
        if (host.isEmpty()) {
            setStatus("Type the host's address first.", true);
            return;
        }
        join(54555, host);
    }

    private void openLanLobby() {
        try {
            Hooks.setClientPorts(54555, 54777);
            joinedPort = 0;
            showLobby();
        } catch (Throwable t) {
            setStatus("Could not open the lobby: " + t, true);
            Log.error("Multiplayer lobby failed", t);
        }
    }

    private void showLobby() {
        saveName();
        window.close();
        ((cgsGame) Gdx.app.getApplicationListener()).showMultiplayerLobby();
    }

    private static Object lobbyScreen(cgsGame game) throws ReflectiveOperationException {
        Field f = cgsGame.class.getDeclaredField("multiplayerLobbyScreen");
        f.setAccessible(true);
        return f.get(game);
    }

    /** Keeps the name in our setting and in the game's, which its lobby screen reads when it opens. */
    private void saveName() {
        Setting<String> saved = LoaderMod.playerName();
        String n = name.text().trim();
        if (n.isEmpty()) return;
        if (saved != null && !n.equals(saved.get())) ModManager.loaderSettings().save(Map.of("mp_player_name", n));
        ru.ius.david.cgs.game.SettingsManager.getInstance().setPlayerName(n);
    }

    private void setStatus(String s, boolean bad) {
        status = s;
        statusBad = bad;
    }

    private static String reason(Throwable err) {
        Throwable c = err.getCause() != null ? err.getCause() : err;
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    // ---- "start now" for the lobby's creator, shown over the game's lobby screen ----

    /**
     * The creator can start early twice: in the lobby (before the player count is reached) and
     * while countries are being picked (the server otherwise waits until everyone is Ready).
     */
    static boolean canStartOwnLobby() {
        if (createdToken == null || createdPort == 0 || joinedPort != createdPort) return false;
        if (inCentralLobby()) return true;
        try {
            Object app = Gdx.app.getApplicationListener();
            if (!(app instanceof cgsGame g)) return false;
            ru.ius.david.cgs.multiplayer.MultiplayerManager mp = g.getActiveMultiplayerManager();
            return mp != null && mp.isGameStarted() && pickingCountries();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True while the game's lobby screen shows a central-server lobby we are connected to. The
     * game only sets cgsGame.activeMultiplayerManager when the match starts; in the lobby the
     * session belongs to the lobby screen.
     */
    static boolean inCentralLobby() {
        // Through a tunnel the game is connected to the relay's local port, not to the lobby's own port.
        if (joinedPort == 0 || Hooks.clientTcpPort() != (joinedVia != 0 ? joinedVia : joinedPort)) return false;
        try {
            if (!(Gdx.app.getApplicationListener() instanceof cgsGame g)) return false;
            if (!(g.getScreen() instanceof ru.ius.david.cgs.MultiplayerLobbyScreen lobby)) return false;
            Field mode = lobby.getClass().getDeclaredField("mode");
            mode.setAccessible(true);
            if (!"IN_LOBBY".equals(String.valueOf(mode.get(lobby)))) return false;
            ru.ius.david.cgs.multiplayer.MultiplayerManager mp = lobby.getMultiplayerManager();
            return mp != null && mp.isMultiplayer() && !mp.isGameStarted();
        } catch (Throwable t) {
            return false;
        }
    }

    static CentralServer.Lobby joinedLobby() { return joinedLobby; }

    /** For {@link MultiplayerSelfTest}: creates a lobby like the Create button; false until the server's info is in. */
    boolean selfTestCreate(String scenarioPath, int playersNeeded) {
        CentralServer.Info i = info;
        if (window == null || i == null) return false;
        CentralServer.Scenario s = i.scenarios().stream().filter(x -> x.path().equals(scenarioPath)).findFirst().orElse(null);
        if (s == null) return false;
        scenarioList.select(s);
        needed = playersNeeded;
        lobbyName.text("zz self-test");
        create();
        return true;
    }

    /** Keeps the joined lobby's player count fresh while its lobby screen is up. */
    private void pollJoinedLobby(float delta) {
        if (!inCentralLobby()) return;
        sinceLobbyPoll += delta;
        if (sinceLobbyPoll < REFRESH_SECONDS) return;
        sinceLobbyPoll = 0;
        CentralServer s = joinedServer;
        int port = joinedPort;
        if (s == null) return;
        s.lobbies().whenComplete((l, err) -> {
            if (err != null || port != joinedPort) return;
            joinedLobby = l.stream().filter(x -> x.port() == port).findFirst().orElse(null);
        });
    }

    static String lobbyStatusText() {
        CentralServer.Lobby l = joinedLobby;
        boolean creator = createdToken != null && joinedPort == createdPort;
        String early = creator ? "You created this lobby: start whenever everyone you expect is here."
                : "The player who created it can start it earlier.";
        if (l == null) return "Online lobby on the central server. " + early;
        String count = l.players() + (l.players() == 1 ? " player is" : " players are") + " here";
        if (l.needed() <= 0) return count + ". " + (creator ? "Press Start when everyone you expect is here." : "The match starts when its creator starts it.");
        if (l.players() >= l.needed()) return count + ". The match is starting...";
        return count + "; the match starts by itself at " + l.needed() + ". " + early;
    }

    private int maxPlayers() { return info == null ? 8 : info.maxPlayers(); }

    static String neededText(int n) {
        return n <= 0 ? "Starts only when you press Start" : "Starts by itself at " + n + (n == 1 ? " player" : " players");
    }

    /** Creator, in the lobby: start by itself with one player fewer or more (0 = only on Start). */
    private void changeNeeded(int step) {
        CentralServer s = createdServer;
        if (s == null || createdId == null) return;
        int n = Math.max(0, Math.min(maxPlayers(), createdNeeded + step));
        if (n == createdNeeded) return;
        int before = createdNeeded;
        createdNeeded = n;
        s.setNeeded(createdId, createdToken, n).whenComplete((v, err) -> {
            if (err == null) return;
            createdNeeded = before;
            Log.error("Changing the player count failed", err);
            ui.toast("Could not change the player count: " + reason(err), 6);
        });
    }

    static boolean pickingCountries() {
        try {
            ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
            return map != null && map.isCountrySelectMode();
        } catch (Throwable t) {
            return false;
        }
    }

    void startCreatedLobby() {
        CentralServer s = createdServer;
        if (s == null || createdId == null) return;
        boolean inLobby = inCentralLobby();
        Log.info(inLobby ? "Asked the central server to start the match now" : "Asked the central server to start without waiting");
        s.start(createdId, createdToken).whenComplete((v, err) -> {
            if (err != null) {
                Log.error("Start request failed", err);
                ui.toast("Could not start: " + reason(err), 6);
            } else if (inLobby) {
                ui.toast("Starting the match...", 4);
            }
        });
    }
}
