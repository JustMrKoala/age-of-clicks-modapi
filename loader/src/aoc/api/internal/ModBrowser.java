package aoc.api.internal;

import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.ListView;
import aoc.api.ui.Panel;
import aoc.api.ui.Separator;
import aoc.api.ui.Theme;
import aoc.api.ui.Ui;
import aoc.api.ui.Window;
import com.badlogic.gdx.graphics.Color;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Main menu > MODS > CODE MODS > Browse mods: every mod in the catalog, with what is installed here,
 * and install / update / remove. New mods and changes take effect the next time the game starts.
 */
final class ModBrowser {
    /** What is installed: the loaded mod's version and jar by mod id (null when not installed). */
    record Installed(String version, File jar) {}

    private enum State { AVAILABLE, INSTALLED, UPDATE, NEEDS_LOADER, RESTART_INSTALL, RESTART_UPDATE, RESTART_REMOVE }

    private final Ui ui;
    private final File modsDir;
    private final Function<String, Installed> installed;
    private final Supplier<String> catalogUrl;
    private final ModCatalog catalog = new ModCatalog();
    private volatile List<ModCatalog.Entry> entries = ModCatalog.bundled();
    private volatile String status = "";
    private volatile Color statusColor;
    private volatile boolean busy, online;
    /** Jars downloaded in this session that are not loaded yet, by mod id. */
    private final Map<String, File> fresh = new ConcurrentHashMap<>();
    private Window window;
    private ListView<ModCatalog.Entry> list;
    private ModCatalog.Entry selected;

    ModBrowser(Ui ui, File modsDir, Function<String, Installed> installed, Supplier<String> catalogUrl) {
        this.ui = ui;
        this.modsDir = modsDir;
        this.installed = installed;
        this.catalogUrl = catalogUrl;
    }

    void open() {
        if (window == null) build();
        ui.open(window);
        if (!online && !busy) refresh();
    }

    void close() {
        if (window != null) window.close();
    }

    private void build() {
        window = new Window("Mod Browser", 1000, 640);
        list = new ListView<ModCatalog.Entry>()
                .items(() -> entries)
                .label(ModCatalog.Entry::name)
                .note(e -> new ListView.Note(shortState(state(e)), stateColor(state(e))))
                .onSelect(e -> selected = e)
                .flex(1);
        Label intro = new Label("Code mods you can add to the game. They load the next time the game starts.")
                .font(Theme.Font.SMALL).muted();
        Panel left = Panel.column().gap(8).width(300).add(intro, list,
                new Button("Refresh").enabled(() -> !busy).onClick(this::refresh));

        Label name = new Label(() -> selected == null ? "No mods in the catalog" : selected.name()).font(Theme.Font.LARGE);
        Label meta = new Label(this::metaLine).font(Theme.Font.SMALL).muted();
        Label description = new Label(() -> selected == null ? "" : selected.description()).font(Theme.Font.MAIN);
        Label stateLine = new Label(() -> selected == null ? "" : longState(selected))
                .font(Theme.Font.MAIN).color(() -> selected == null ? Theme.textMuted() : stateColor(state(selected)));
        Label statusLabel = new Label(() -> status).font(Theme.Font.SMALL)
                .color(() -> statusColor == null ? Theme.textMuted() : statusColor).flex(1);
        Panel buttons = Panel.row().gap(8).height(36).add(statusLabel,
                new Button("Remove").width(120).enabled(() -> !busy && canRemove()).onClick(this::remove),
                new Button(this::mainLabel).primary().width(150).enabled(() -> !busy && canInstall()).onClick(this::install));
        Panel right = Panel.column().gap(8).flex(1).add(name, meta, new Separator(), description, stateLine,
                Separator.spacer().flex(1), buttons);
        window.content().add(Panel.row().gap(14).flex(1).add(left, new Separator().width(2), right));
        selectFirst();
    }

    private void selectFirst() {
        List<ModCatalog.Entry> all = entries;
        if (selected != null) {
            // Keep the selection across a refresh (entries are new objects).
            for (ModCatalog.Entry e : all) if (e.id().equals(selected.id())) selected = e;
        } else if (!all.isEmpty()) {
            selected = all.get(0);
        }
        if (list != null) list.select(selected);
    }

    private void refresh() {
        busy = true;
        say("Reading the catalog...", null);
        String url = catalogUrl.get();
        catalog.fetch(url).whenComplete((found, err) -> {
            busy = false;
            if (err != null) {
                say("Catalog unreachable (" + reason(err) + "). Showing the list built into the loader.", Theme.warn());
                return;
            }
            online = true;
            entries = found;
            say(found.size() + " mods in the catalog.", null);
            com.badlogic.gdx.Gdx.app.postRunnable(this::selectFirst);
        });
    }

    // ---- state ----

    private State state(ModCatalog.Entry e) {
        Installed in = installed.apply(e.id());
        if (in == null) {
            File jar = fresh.get(e.id());
            if (jar != null && jar.isFile()) return State.RESTART_INSTALL;
            if (!e.loader().isEmpty() && ModCatalog.compare(Version.LOADER, e.loader()) < 0) return State.NEEDS_LOADER;
            return State.AVAILABLE;
        }
        if (in.jar() != null && ModCatalog.removeMarker(in.jar()).exists()) return State.RESTART_REMOVE;
        if (in.jar() != null && ModCatalog.updateFile(in.jar()).exists()) return State.RESTART_UPDATE;
        if (ModCatalog.compare(in.version(), e.version()) < 0) {
            return !e.loader().isEmpty() && ModCatalog.compare(Version.LOADER, e.loader()) < 0 ? State.NEEDS_LOADER : State.UPDATE;
        }
        return State.INSTALLED;
    }

    private static String shortState(State s) {
        switch (s) {
            case INSTALLED: return "Installed";
            case UPDATE: return "Update";
            case NEEDS_LOADER: return "Needs new loader";
            case AVAILABLE: return "";
            default: return "Restart";
        }
    }

    private static Color stateColor(State s) {
        switch (s) {
            case INSTALLED: return Theme.good();
            case UPDATE: case RESTART_INSTALL: case RESTART_UPDATE: case RESTART_REMOVE: return Theme.warn();
            case NEEDS_LOADER: return Theme.bad();
            default: return Theme.textMuted();
        }
    }

    private String longState(ModCatalog.Entry e) {
        Installed in = installed.apply(e.id());
        switch (state(e)) {
            case INSTALLED: return "Installed (version " + in.version() + ").";
            case UPDATE: return "Installed: version " + in.version() + ". Version " + e.version() + " is available.";
            case NEEDS_LOADER: return "Needs mod loader " + e.loader() + " or newer (this is " + Version.LOADER + "). Run the latest Setup first.";
            case RESTART_INSTALL: return "Downloaded. Restart the game to load it.";
            case RESTART_UPDATE: return "Update downloaded. Restart the game to finish it.";
            case RESTART_REMOVE: return "Will be removed when the game restarts.";
            default: return "Not installed.";
        }
    }

    private String metaLine() {
        if (selected == null) return "";
        StringBuilder b = new StringBuilder("Version ").append(selected.version());
        if (!selected.author().isEmpty()) b.append("    by ").append(selected.author());
        if (selected.size() > 0) b.append("    ").append(Math.max(1, selected.size() / 1024)).append(" KB");
        return b.toString();
    }

    private String mainLabel() {
        if (selected == null) return "Install";
        switch (state(selected)) {
            case UPDATE: return "Update";
            case RESTART_REMOVE: return "Keep";
            default: return "Install";
        }
    }

    private boolean canInstall() {
        if (selected == null) return false;
        State s = state(selected);
        return s == State.AVAILABLE || s == State.UPDATE || s == State.RESTART_REMOVE;
    }

    private boolean canRemove() {
        if (selected == null) return false;
        State s = state(selected);
        return s == State.INSTALLED || s == State.UPDATE || s == State.RESTART_UPDATE || s == State.RESTART_INSTALL;
    }

    // ---- actions ----

    private void install() {
        ModCatalog.Entry e = selected;
        Installed in = installed.apply(e.id());
        if (state(e) == State.RESTART_REMOVE) {
            ModCatalog.removeMarker(in.jar()).delete();
            say(e.name() + " stays installed.", null);
            return;
        }
        // A loaded jar is in use: its new version waits next to it until the next start.
        File target = in != null && in.jar() != null ? ModCatalog.updateFile(in.jar()) : new File(modsDir, e.file());
        busy = true;
        say("Downloading " + e.name() + "...", null);
        catalog.download(e, catalogUrl.get(), target).whenComplete((ok, err) -> {
            busy = false;
            if (err != null) {
                say("Could not install " + e.name() + ": " + reason(err) + ".", Theme.bad());
                Log.error("Mod Browser: install of " + e.id() + " failed", err);
                return;
            }
            if (in == null) fresh.put(e.id(), target);
            Log.info("Mod Browser: downloaded " + e.id() + " " + e.version() + " to " + target.getName());
            say(e.name() + " " + e.version() + " downloaded. Restart the game to load it.", Theme.good());
        });
    }

    private void remove() {
        ModCatalog.Entry e = selected;
        Installed in = installed.apply(e.id());
        try {
            if (in == null) {
                // Downloaded this session and never loaded: the file is free to delete.
                File jar = fresh.remove(e.id());
                if (jar != null) java.nio.file.Files.deleteIfExists(jar.toPath());
                say(e.name() + " removed.", null);
            } else if (in.jar() != null) {
                java.nio.file.Files.deleteIfExists(ModCatalog.updateFile(in.jar()).toPath());
                ModCatalog.removeMarker(in.jar()).createNewFile();
                Log.info("Mod Browser: " + e.id() + " marked for removal");
                say(e.name() + " will be removed when the game restarts. Its settings folder is kept.", null);
            }
        } catch (java.io.IOException ex) {
            say("Could not remove " + e.name() + ": " + ex.getMessage(), Theme.bad());
        }
    }

    private void say(String text, Color color) {
        status = text;
        statusColor = color;
    }

    private static String reason(Throwable t) {
        while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
