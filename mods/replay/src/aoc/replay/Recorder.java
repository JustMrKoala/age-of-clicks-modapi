package aoc.replay;

import aoc.api.ModContext;
import aoc.api.world.World;
import ru.ius.david.cgs.CityData;
import ru.ius.david.cgs.CountryData;
import ru.ius.david.cgs.NativeIntGrid;
import ru.ius.david.cgs.WarManager;
import ru.ius.david.cgs.game.CityManager;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a {@link Replay} while a single-player game runs: every few game days it samples the
 * ownership grid (only changed cells are stored), every country's economy, the wars and new
 * cities. Runs on the render thread from the mod's {@code render}; mods are paused in multiplayer.
 */
final class Recorder {
    /** Sampled grid is at most this wide, enough for a 1440p video of the whole map. */
    private static final int MAX_GRID = 1600;
    private static final long MIN_SAMPLE_GAP_NANOS = 300_000_000L;
    private long lastSampleNanos;

    private final ModContext ctx;
    private final File dir;

    private Object session;
    private String scenario = "";
    private DataOutputStream out;
    private File file;
    private int nextDay = Integer.MIN_VALUE, lastDay;
    private int scale, gw, gh, frames;
    private int[] prev, row;
    /** Name last written for each country key. */
    private final Map<Integer, String> countryNames = new HashMap<>();
    private final Set<Integer> knownCities = new HashSet<>();
    private final Map<String, Integer> nameIds = new HashMap<>();
    /** The same match kept in memory, for the live camera and captions. */
    private Replay live;
    private ReplayState liveState;
    /** Called after every recorded frame with the analysed state (events of that frame included). */
    java.util.function.Consumer<ReplayState> onFrame;
    /** Called when a country is renamed (a nation formed): its key and "Old becomes New". */
    java.util.function.BiConsumer<Integer, String> onRename;

    Recorder(ModContext ctx, File dir) {
        this.ctx = ctx;
        this.dir = dir;
    }

    File file() { return file; }

    /** The match so far, analysed at its latest frame; null before the first frame. */
    ReplayState live() { return liveState != null && liveState.frame() >= 0 ? liveState : null; }

    int frames() { return frames; }

    /**
     * Called every frame while a map is open. {@code session} identifies the running game (the
     * map screen); a new one, or the calendar going backwards (a loaded save), starts a new file.
     */
    void tick(Object session, String scenario, int everyDays) {
        GameManager gm = GameManager.getInstance();
        WarManager wm = gm == null ? null : gm.getWarManager();
        if (wm == null || wm.getOwnershipGrid() == null || gm.getPlayerCountry() == null) return;
        int day = gm.getGameDays();
        if (session != this.session || day < lastDay || !scenario.equals(this.scenario)) {
            stop();
            this.session = session;
            this.scenario = scenario;
            try {
                start(gm, wm, scenario);
            } catch (IOException e) {
                ctx.error("Could not start a replay file", e);
                this.session = null;
                return;
            }
        }
        lastDay = day;
        if (day < nextDay) return;
        // Sped up, the calendar runs dozens of days a second; a sample scans the whole map, so at most
        // a few per real second (measured at x16: sampling took almost half of the render thread).
        long now = System.nanoTime();
        if (now - lastSampleNanos < MIN_SAMPLE_GAP_NANOS) return;
        lastSampleNanos = now;
        nextDay = day + Math.max(1, everyDays);
        try {
            writeFrame(gm, wm, day);
        } catch (IOException e) {
            ctx.error("Replay recording stopped", e);
            stop();
        }
    }

    void stop() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // the gzip stream was flushed after every frame; the file stays readable
            }
            ctx.log("Replay saved: " + file.getName() + " (" + frames + " frames)");
        }
        out = null;
        live = null;
        liveState = null;
        session = null;
        scenario = "";
        nextDay = Integer.MIN_VALUE;
        lastDay = 0;
    }

    private void start(GameManager gm, WarManager wm, String scenario) throws IOException {
        NativeIntGrid grid = wm.getOwnershipGrid();
        int w = grid.getWidth(), h = grid.getHeight();
        scale = Math.max(1, (Math.max(w, h) + MAX_GRID - 1) / MAX_GRID);
        gw = (w + scale - 1) / scale;
        gh = (h + scale - 1) / scale;
        prev = new int[gw * gh];
        java.util.Arrays.fill(prev, Integer.MIN_VALUE); // first frame stores every cell
        row = new int[w];
        countryNames.clear();
        knownCities.clear();
        nameIds.clear();
        frames = 0;
        dir.mkdirs();
        String id = scenario.replaceAll("/+$", "");
        id = id.substring(id.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_-]+", "_");
        file = new File(dir, id + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".aocreplay");
        out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file), 1 << 16, true), 1 << 16));
        out.writeBytes(Replay.MAGIC);
        out.writeInt(Replay.VERSION);
        out.writeUTF(scenario);
        out.writeUTF(scenarioTitle(scenario));
        out.writeInt(w);
        out.writeInt(h);
        out.writeInt(scale);
        CountryState player = gm.getPlayerCountry();
        out.writeInt(wm.getColorKeyByName(player.getName()));
        out.writeLong(System.currentTimeMillis());
        live = new Replay();
        live.scenario = scenario;
        live.title = scenarioTitle(scenario);
        live.mapW = w;
        live.mapH = h;
        live.scale = scale;
        live.playerKey = wm.getColorKeyByName(player.getName());
        live.created = System.currentTimeMillis();
        liveState = new ReplayState(live);
        ctx.log("Recording replay to " + file.getName() + " (grid " + gw + "x" + gh + ", 1:" + scale + ")");
    }

    private void writeFrame(GameManager gm, WarManager wm, int day) throws IOException {
        // Ownership: sample, compare, store runs of cells that changed to the same owner.
        NativeIntGrid grid = wm.getOwnershipGrid();
        // Only which countries hold land matters here: one set insert per stretch of equal cells.
        Set<Integer> holders = new HashSet<>();
        int lastSeen = -1;
        List<int[]> runs = new ArrayList<>();
        int lastEnd = 0, runStart = -1, runOwner = 0;
        for (int gy = 0; gy < gh; gy++) {
            grid.getRow(Math.min(gy * scale, grid.getHeight() - 1), row);
            for (int gx = 0; gx < gw; gx++) {
                int i = gy * gw + gx;
                int owner = row[Math.min(gx * scale, row.length - 1)];
                if (owner != lastSeen) {
                    lastSeen = owner;
                    if (owner != -1) holders.add(owner);
                }
                boolean changed = owner != prev[i];
                if (runStart >= 0 && (!changed || owner != runOwner)) {
                    runs.add(new int[]{runStart - lastEnd, i - runStart, runOwner});
                    lastEnd = i;
                    runStart = -1;
                }
                if (changed && runStart < 0) {
                    runStart = i;
                    runOwner = owner;
                }
                prev[i] = owner;
            }
        }
        if (runStart >= 0) runs.add(new int[]{runStart - lastEnd, prev.length - runStart, runOwner});

        // Countries that hold land or fight: definitions once, numbers every frame.
        Map<Integer, CountryState> states = new HashMap<>();
        for (CountryState s : gm.getWorldCountries().values()) states.put(wm.getColorKeyByName(s.getName()), s);
        List<WarManager.War> wars = new ArrayList<>(wm.getActiveWars());
        Set<Integer> present = holders;
        for (WarManager.War w : wars) {
            if (w.attacker != null) present.add(w.attacker.colorKey);
            if (w.defender != null) present.add(w.defender.colorKey);
        }
        // Definitions when a country first appears, and again when it is renamed (forming a nation keeps
        // the country's colour key and only changes its name, e.g. Netherlands -> Benelux Union).
        for (int key : present) {
            CountryData cd = wm.getCountryData(key);
            CountryState s = states.get(key);
            String name = s != null ? s.getDisplayName() : cd != null ? cd.name : "?";
            String before = countryNames.get(key);
            if (before != null && before.equals(name)) continue;
            countryNames.put(key, name);
            out.writeByte(Replay.COUNTRY);
            out.writeInt(key);
            out.writeUTF(name);
            out.writeUTF(s != null && s.getTag() != null ? s.getTag() : cd != null && cd.tag != null ? cd.tag : "");
            int rgba = cd != null ? com.badlogic.gdx.graphics.Color.rgba8888(cd.color) : (key << 8) | 0xff;
            out.writeInt(rgba);
            live.countries.put(key, new Replay.Country(key, name, "", rgba));
            if (before != null && onRename != null) onRename.accept(key, before + " becomes " + name);
        }
        List<Object[]> stats = new ArrayList<>();
        for (int key : present) {
            CountryState s = states.get(key);
            if (s == null) continue;
            String alliance = s.allianceName;
            stats.add(new Object[]{key, s.getStructuralGoldIncome(), s.getTotalPopulation(),
                    alliance == null || alliance.isBlank() ? -1 : nameId(alliance)});
        }

        // New cities since the last frame.
        CityManager cm = gm.getCityManager();
        List<Object[]> cities = new ArrayList<>();
        if (cm != null) {
            List<CityData> list = cm.getCities();
            for (int i = 0; i < list.size(); i++) {
                CityData c = list.get(i);
                if (!knownCities.add(c.id)) continue;
                String name = cm.getName(i);
                int level = cm.getLevel(i);
                int[] px = ctx.world().cityPixel(new World.CityInfo(c.id, name, c.x, c.y, "", level, c.main));
                cities.add(new Object[]{c.id, name == null ? "" : name, px[0], px[1], level, cm.getOwnerColorKey(i), c.main});
            }
        }

        out.writeByte(Replay.FRAME);
        out.writeInt(day);
        out.writeUTF(GameManager.formatGameDate(day, gm.getScenarioStartYear()));
        out.writeInt(runs.size());
        for (int[] r : runs) {
            Replay.writeVarint(out, r[0]);
            Replay.writeVarint(out, r[1]);
            out.writeInt(r[2]);
        }
        out.writeInt(stats.size());
        for (Object[] s : stats) {
            out.writeInt((Integer) s[0]);
            out.writeDouble((Double) s[1]);
            out.writeLong((Long) s[2]);
            out.writeInt((Integer) s[3]);
        }
        int warCount = 0;
        for (WarManager.War w : wars) if (w.attacker != null && w.defender != null && !w.finished) warCount++;
        out.writeInt(warCount);
        for (WarManager.War w : wars) {
            if (w.attacker == null || w.defender == null || w.finished) continue;
            out.writeInt(w.uniqueId);
            out.writeInt(w.attacker.colorKey);
            out.writeInt(w.defender.colorKey);
            out.writeFloat(w.progress);
            out.writeFloat(w.attackerFocusX);
            out.writeFloat(w.attackerFocusY);
            out.writeFloat(w.defenderFocusX);
            out.writeFloat(w.defenderFocusY);
        }
        out.writeInt(cities.size());
        for (Object[] c : cities) {
            out.writeInt((Integer) c[0]);
            out.writeUTF((String) c[1]);
            out.writeInt((Integer) c[2]);
            out.writeInt((Integer) c[3]);
            out.writeInt((Integer) c[4]);
            out.writeInt((Integer) c[5]);
            out.writeBoolean((Boolean) c[6]);
        }
        int player = wm.getColorKeyByName(gm.getPlayerCountry().getName());
        out.writeInt(player);
        out.flush();
        frames++;

        int[] runArr = new int[runs.size() * 3];
        for (int i = 0; i < runs.size(); i++) System.arraycopy(runs.get(i), 0, runArr, i * 3, 3);
        Replay.Stats[] statArr = new Replay.Stats[stats.size()];
        for (int i = 0; i < statArr.length; i++) {
            Object[] o = stats.get(i);
            statArr[i] = new Replay.Stats((Integer) o[0], (Double) o[1], (Long) o[2], (Integer) o[3]);
        }
        List<Replay.War> warList = new ArrayList<>();
        for (WarManager.War w : wars) {
            if (w.attacker == null || w.defender == null || w.finished) continue;
            warList.add(new Replay.War(w.uniqueId, w.attacker.colorKey, w.defender.colorKey, w.progress,
                    w.attackerFocusX, w.attackerFocusY, w.defenderFocusX, w.defenderFocusY));
        }
        Replay.City[] cityArr = new Replay.City[cities.size()];
        for (int i = 0; i < cityArr.length; i++) {
            Object[] c = cities.get(i);
            cityArr[i] = new Replay.City((Integer) c[0], (String) c[1], (Integer) c[2], (Integer) c[3], (Integer) c[4],
                    (Integer) c[5], (Boolean) c[6]);
        }
        live.frames.add(new Replay.Frame(day, GameManager.formatGameDate(day, gm.getScenarioStartYear()), runArr, statArr,
                warList.toArray(new Replay.War[0]), cityArr, player));
        liveState.seek(live.frames.size() - 1);
        if (onFrame != null) onFrame.accept(liveState);
    }

    private int nameId(String text) throws IOException {
        Integer id = nameIds.get(text);
        if (id != null) return id;
        id = nameIds.size();
        nameIds.put(text, id);
        out.writeByte(Replay.NAME);
        out.writeInt(id);
        out.writeUTF(text);
        live.names.put(id, text);
        return id;
    }

    private static String scenarioTitle(String scenario) {
        try {
            for (ru.ius.david.cgs.ScenarioData s : ru.ius.david.cgs.ScenarioData.loadAll()) {
                if (scenario.equals(s.folderPath)) return s.name;
            }
        } catch (Throwable ignored) {
            // fall back to the folder name
        }
        String id = scenario.replaceAll("/+$", "");
        return id.substring(id.lastIndexOf('/') + 1);
    }
}
