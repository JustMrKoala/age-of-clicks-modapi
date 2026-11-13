package aoc.api;

import aoc.api.internal.Hooks;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.mcp.GameAIConnector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Stable facade over the game's internals.
 *
 * <p><b>Reads</b> must happen on the render thread (inside a mod callback, or through
 * {@link #onGameThread}). <b>Actions</b> go through the game's own {@code GameAIConnector}
 * (the same code behind its MCP server), run on a background thread, and complete with the
 * game's JSON result. The game marks a run that used them as externally modified.
 *
 * <p>Countries are identified by their internal name, as listed by {@link #countries()}.
 */
public final class GameApi {
    /** Snapshot of one country, safe to keep and pass between threads. */
    public record CountryInfo(String name, String tag, String displayName, String government,
                              long gold, int relationWithPlayer, boolean ally, boolean puppet,
                              boolean isPlayer, float strength) {}

    private final ExecutorService actions = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aoc-game-actions");
        t.setDaemon(true);
        return t;
    });
    private GameAIConnector connector;

    private GameAIConnector connector() {
        if (connector == null) connector = new GameAIConnector();
        return connector;
    }

    // ---- threading ----

    /** Runs {@code read} on the render thread; completes immediately if already there. */
    public <T> CompletableFuture<T> onGameThread(Supplier<T> read) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                result.complete(read.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };
        if (isRenderThread()) task.run();
        else Gdx.app.postRunnable(task);
        return result;
    }

    private static boolean isRenderThread() {
        return Thread.currentThread() == Hooks.renderThread();
    }

    // ---- reads (render thread) ----

    /** True while a scenario is being played and its map is on screen (not in menus). */
    public boolean inGame() {
        try {
            GameManager gm = GameManager.getInstance();
            if (gm == null || gm.getPlayerCountry() == null) return false;
            Object listener = Gdx.app.getApplicationListener();
            if (!(listener instanceof com.badlogic.gdx.Game)) return true;
            com.badlogic.gdx.Screen screen = ((com.badlogic.gdx.Game) listener).getScreen();
            return screen != null && screen.getClass().getSimpleName().equals("MapScreen");
        } catch (Throwable t) {
            return false;
        }
    }

    /** In-game date, formatted the way the game shows it. */
    public String date() {
        GameManager gm = GameManager.getInstance();
        return GameManager.formatGameDate(gm.getGameDays(), gm.getScenarioStartYear());
    }

    public int year() {
        return GameManager.getInstance().getCurrentYear();
    }

    public CountryInfo player() {
        CountryState p = GameManager.getInstance().getPlayerCountry();
        return p == null ? null : info(p, p.getName(), true, 0f);
    }

    /** Every country that still exists, sorted by display name. */
    public List<CountryInfo> countries() {
        GameManager gm = GameManager.getInstance();
        CountryState player = gm.getPlayerCountry();
        JsonValue world = new JsonReader().parse(worldStateJson());
        List<CountryInfo> out = new ArrayList<>();
        for (JsonValue c = world.get("countries").child; c != null; c = c.next) {
            String name = c.getString("name");
            CountryState state = findState(gm, name);
            float strength = c.getFloat("totalStrength", 0f);
            if (state != null) {
                out.add(info(state, name, state == player, strength));
            } else {
                out.add(new CountryInfo(name, null, name, "", c.getLong("gold", 0), 0,
                        false, false, c.getBoolean("isPlayer", false), strength));
            }
        }
        out.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return out;
    }

    public CountryInfo country(String name) {
        for (CountryInfo c : countries()) if (c.name().equals(name)) return c;
        return null;
    }

    /** Resources, military, income, upgrades, diplomacy and AI relations, as game JSON. */
    public String countryDetailsJson(String name) { return connector().getCountryDetails(name); }

    /** All countries with key stats plus active wars, as game JSON. */
    public String worldStateJson() { return connector().getWorldState(); }

    /** Every war with attacker, defender and progress (-1..1, positive = attacker winning). */
    public String warsJson() { return connector().getWars(); }

    /** Countries sharing a border with {@code name}, and whether they are at war with it. */
    public String neighborsJson(String name) { return connector().getCountryNeighbors(name); }

    private static CountryState findState(GameManager gm, String name) {
        for (CountryState s : gm.getWorldCountries().values()) {
            if (name.equals(s.getName())) return s;
        }
        for (CountryState s : gm.getWorldCountries().values()) {
            if (name.equalsIgnoreCase(s.getName()) || name.equalsIgnoreCase(s.getTag())) return s;
        }
        return null;
    }

    private static CountryInfo info(CountryState s, String name, boolean isPlayer, float strength) {
        String gov = s.currentForm == null ? "" : s.currentForm.name();
        return new CountryInfo(name, s.getTag(), s.getDisplayName(), gov, s.getGold(),
                s.getPlayerRelation(), s.isAlly(), s.isPuppet(), isPlayer, strength);
    }

    // ---- actions (any thread except render; results are game JSON) ----

    public CompletableFuture<String> makePeace(String a, String b) {
        return act(() -> connector().makePeace(a, b));
    }

    /** {@code attacker} declares war on {@code defender}; needs a shared border. */
    public CompletableFuture<String> declareWar(String attacker, String defender) {
        return act(() -> connector().declareWar(attacker, defender));
    }

    /** Relation between two countries, -100..100. */
    public CompletableFuture<String> setRelation(String a, String b, int value) {
        return act(() -> connector().setRelation(a, b, Math.max(-100, Math.min(100, value))));
    }

    /** Alliance with the player. */
    public CompletableFuture<String> setAlly(String country, boolean ally) {
        return act(() -> connector().setAlly(country, ally));
    }

    /** Puppet of the player. */
    public CompletableFuture<String> setPuppet(String country, boolean puppet) {
        return act(() -> connector().setPuppet(country, puppet));
    }

    /** Adds gold; negative removes it. */
    public CompletableFuture<String> addGold(String country, long amount) {
        return act(() -> connector().addMoney(country, amount));
    }

    /** iron, coal, oil, timber, rubber, aluminum, uranium, manpower, infantry, artillery, tanks, aircraft, ships. */
    public CompletableFuture<String> addResource(String country, String resource, long amount) {
        return act(() -> connector().addResource(country, resource, amount));
    }

    private CompletableFuture<String> act(Supplier<String> call) {
        // The connector waits for the render thread, so it always runs on our own thread.
        return CompletableFuture.supplyAsync(call, actions);
    }
}
