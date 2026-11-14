package aoc.replay;

import aoc.api.AocMod;
import aoc.api.ModContext;
import aoc.api.settings.Setting;
import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.ListView;
import aoc.api.ui.Panel;
import aoc.api.ui.Separator;
import aoc.api.ui.Theme;
import aoc.api.ui.Window;
import com.badlogic.gdx.InputProcessor;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Replays: films every single-player game with the game's own graphics from a cinematic
 * camera (no interface, not the player's camera), straight to an MP4; main menu > REPLAYS
 * plays the films in the game.
 */
public final class ReplayMod implements AocMod {
    private ModContext ctx;
    private Recorder recorder;
    private LiveFilm film;
    private final VideoPlayer player = new VideoPlayer();

    private Setting<Boolean> filming;
    private Setting<Double> peaceDays, warDays;
    private Setting<Integer> maxPerSecond;
    private Setting<String> warSpeed;
    private Setting<String> quality, fps, ffmpegPath, videoFolder;

    private volatile String ffmpeg;
    private volatile boolean ffmpegChecked;

    private SelfTest selfTest;
    private Window window;
    private ListView<Entry> list;
    private volatile List<Entry> entries = List.of();
    private String message = "";
    private long deleteArmedUntil;

    /** A film on disk. */
    private record Entry(File file, String label) {}

    @Override public String id() { return "replay"; }
    @Override public String name() { return "Replays"; }
    @Override public String version() { return "2.1.0"; }
    @Override public String author() { return "Age of Clicks Mod API"; }

    @Override
    public String description() {
        return "Films your single-player games with the game's own graphics from a cinematic camera that follows your country and its wars. Needs ffmpeg.";
    }

    @Override
    public void onLoad(ModContext ctx) {
        this.ctx = ctx;
        filming = ctx.settings().toggle("film", "Film single-player games", true,
                "Films every single-player game while you play; you never see the extra camera.");
        peaceDays = ctx.settings().decimal("peace_days_per_second", "Film speed in peace (game days per second)", 20.0, 2.0, 365.0,
                "Quiet years go by quicker than wars. 20 = a peaceful year in about 18 seconds.");
        warDays = ctx.settings().decimal("war_days_per_second", "Film speed at war (game days per second)", 6.0, 1.0, 120.0,
                "While your country or its alliance fights, the film slows to this as far as the game gives it frames: "
                        + "fully when you play at x1 or x2, about 10 days per second at x4, faster at x8 and x16.");
        warSpeed = ctx.settings().choice("war_speed_limit", "Game speed during your own wars", "x2", List.of("off", "x1", "x2", "x4", "x8"),
                "A film can only show what the game draws, so a war played at x16 is over in seconds in the film too. "
                        + "While your own country is at war the game is held at this speed, and returns to yours afterwards. Off: never touch the game speed.");
        maxPerSecond = ctx.settings().integer("max_frames_per_second", "Film frames per real second (max)", 60, 2, 120,
                "Each film frame draws the map once more; slow wars need many. Lower this if the game stutters while filming.");
        quality = ctx.settings().choice("video_quality", "Video resolution", "1080p", List.of("720p", "1080p", "1440p"),
                "Never more than your window; play fullscreen for the sharpest film.");
        fps = ctx.settings().choice("video_fps", "Video frames per second", "30", List.of("30", "60"), "");
        videoFolder = ctx.settings().text("video_folder", "Video folder", defaultVideoFolder(), "Where the films go.");
        ffmpegPath = ctx.settings().text("ffmpeg_path", "ffmpeg", "",
                "Leave empty to find ffmpeg automatically (PATH, scoop, C:\\ffmpeg\\bin). Get it from ffmpeg.org.");
        ctx.settings().onChange(() -> ffmpegChecked = false);
        recorder = new Recorder(ctx, new File(ctx.dataDir(), "recordings"));
        film = new LiveFilm(ctx, recorder);
        selfTest = SelfTest.load(ctx);
        film.selfTest = selfTest;
        if (selfTest != null) aoc.api.internal.Hooks.setFilmDebug(true);
        ctx.ui().addMainMenuButton("Replays", this::openWindow);
    }

    @Override
    public InputProcessor input() { return player; }

    @Override
    public void beforeFrame(ModContext ctx, float delta) {
        if (!filming.get() || (selfTest != null && !selfTest.filming())) {
            film.finish();
            return;
        }
        String ff = ffmpeg();
        int height = switch (quality.get()) {
            case "720p" -> 720;
            case "1440p" -> 1440;
            default -> 1080;
        };
        film.warSpeedLimit = warSpeed.get().equals("off") ? 0 : Integer.parseInt(warSpeed.get().substring(1));
        long t0 = System.nanoTime();
        film.beforeFrame(delta, ff, new File(videoFolder.get()), Integer.parseInt(fps.get()), height,
                peaceDays.get().floatValue(), warDays.get().floatValue(), maxPerSecond.get());
        if (selfTest != null) selfTest.filmNanos += System.nanoTime() - t0;
    }

    @Override
    public void render(ModContext ctx, float delta) {
        ru.ius.david.cgs.MapScreen map = ru.ius.david.cgs.MapScreen.getActiveInstance();
        if (map != null && ctx.game().inGame() && filming.get() && !map.isCountrySelectMode()) {
            long t0 = System.nanoTime();
            recorder.tick(map, map.getScenarioPath(), 3);
            if (selfTest != null) selfTest.recorderNanos += System.nanoTime() - t0;
        }
        else if (map == null) recorder.stop();
        player.render(delta, Theme.font(Theme.Font.MAIN));
        if (selfTest != null) selfTest.tick(delta, film);
    }

    /** Looked up once (and again after settings change); finding it starts a process. */
    private String ffmpeg() {
        if (!ffmpegChecked) {
            ffmpegChecked = true;
            ffmpeg = LiveFilm.findFfmpeg(ffmpegPath.get());
            if (ffmpeg == null) ctx.log("ffmpeg not found; games are not filmed");
            else LiveFilm.probeEncoder(ffmpeg, ctx);
        }
        return ffmpeg;
    }

    // ---- window ----

    private void openWindow() {
        if (window == null) buildWindow();
        refresh();
        ctx.ui().open(window);
    }

    private void buildWindow() {
        window = new Window("Replays", 820, 520);
        list = new ListView<Entry>().items(() -> entries).label(Entry::label).flex(1);
        Button watch = new Button("Watch").primary()
                .enabled(() -> list.selected() != null && ffmpeg() != null)
                .onClick(() -> {
                    window.close();
                    player.open(list.selected().file(), ffmpeg());
                });
        Button folder = new Button("Open video folder").onClick(this::openFolder);
        Button delete = new Button(() -> System.currentTimeMillis() < deleteArmedUntil ? "Click again to delete" : "Delete")
                .enabled(() -> list.selected() != null)
                .onClick(this::delete);
        window.content().add(
                new Label(() -> ffmpeg() == null
                        ? "ffmpeg is needed to film and play replays. Install it (ffmpeg.org, or 'scoop install ffmpeg') or set its path in MODS > CODE MODS > Replays."
                        : "Your single-player games are filmed while you play, from a cinematic camera. Settings: MODS > CODE MODS > Replays.")
                        .font(Theme.Font.SMALL).muted(),
                list,
                new Label(() -> message).font(Theme.Font.SMALL).muted(),
                Panel.row().gap(8).height(38).add(watch.width(130), folder.width(200), Separator.spacer().flex(1), delete.width(200)));
    }

    private void refresh() {
        File dir = new File(videoFolder.get());
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".mp4"));
        List<Entry> out = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : files) out.add(new Entry(f, label(f)));
        }
        entries = out;
        message = out.isEmpty() ? "No films yet. Play a single-player game; it is filmed as you play." : "";
    }

    private static String label(File f) {
        String n = f.getName();
        boolean part = n.endsWith(LiveFilm.PART);
        n = n.substring(0, n.length() - (part ? LiveFilm.PART.length() : 4));
        int cut = n.length() - 17; // "_yyyy-MM-dd_HH-mm"
        String name = cut > 0 && n.charAt(cut) == '_' ? n.substring(0, cut).replace('_', ' ') : n;
        String when = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)
                .format(Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()));
        String size = String.format(Locale.ROOT, "%.0f MB", f.length() / 1048576.0);
        return name + "   -   " + when + "   -   " + size + (part ? "   (unfinished)" : "");
    }

    private void delete() {
        Entry e = list.selected();
        if (e == null) return;
        if (System.currentTimeMillis() > deleteArmedUntil) {
            deleteArmedUntil = System.currentTimeMillis() + 3000;
            return;
        }
        deleteArmedUntil = 0;
        if (e.file().delete()) {
            list.select(null);
            refresh();
        } else {
            message = "Could not delete " + e.file().getName() + " (still in use?)";
        }
    }

    private void openFolder() {
        File dir = new File(videoFolder.get());
        dir.mkdirs();
        try {
            new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
        } catch (Exception e) {
            message = "Films are in " + dir.getAbsolutePath();
        }
    }

    private static String defaultVideoFolder() {
        return new File(new File(System.getProperty("user.home"), "Videos"), "Age of Clicks Replays").getPath();
    }
}
