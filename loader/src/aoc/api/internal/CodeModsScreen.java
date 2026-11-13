package aoc.api.internal;

import aoc.api.AocMod;
import aoc.api.ModContext;
import aoc.api.settings.Setting;
import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.ListView;
import aoc.api.ui.Panel;
import aoc.api.ui.ScrollPanel;
import aoc.api.ui.Separator;
import aoc.api.ui.TextField;
import aoc.api.ui.Theme;
import aoc.api.ui.Toggle;
import aoc.api.ui.Ui;
import aoc.api.ui.Window;
import com.badlogic.gdx.graphics.Color;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Main menu > MODS > Code Mods: every installed code mod, with an editor for its declared settings. */
final class CodeModsScreen {
    record Entry(AocMod mod, ModContext ctx) {}

    private final Ui ui;
    private final Supplier<List<Entry>> mods;
    private final File modsDir;
    private Window window;
    private ListView<Entry> list;
    private Entry selected;
    private Panel form;
    private ScrollPanel formScroll;
    private final Map<String, Supplier<String>> edits = new LinkedHashMap<>();
    private String status = "";
    private Color statusColor;

    CodeModsScreen(Ui ui, Supplier<List<Entry>> mods, File modsDir) {
        this.ui = ui;
        this.mods = mods;
        this.modsDir = modsDir;
    }

    void open() {
        if (window == null) build();
        if (selected == null && !mods.get().isEmpty()) select(mods.get().get(0));
        ui.open(window);
    }

    private void build() {
        window = new Window("Code Mods", 1000, 640);
        list = new ListView<Entry>()
                .items(mods)
                .label(e -> e.mod().name())
                .note(e -> new ListView.Note(e.mod().version(), null))
                .onSelect(this::select)
                .flex(1);
        Label intro = new Label("Mods made with the Age of Clicks Mod API. Scenario mods are in the list behind this window.")
                .font(Theme.Font.SMALL).muted();
        Panel left = Panel.column().gap(8).width(280).add(intro, list,
                new Button("Open mods folder").onClick(() -> openFolder(modsDir)));

        // Mods' own screens (Ui.addMainMenuButton) that did not fit in the main menu.
        java.util.List<MainMenuInjector.Row> extra = Hooks.MOD_ROWS.subList(Hooks.modRowsInMenu(), Hooks.MOD_ROWS.size());
        if (!extra.isEmpty()) {
            Panel launch = Panel.row().gap(8).height(38).add(new Label("Open").font(Theme.Font.MAIN).width(60));
            for (MainMenuInjector.Row r : extra) {
                launch.add(new Button(r.label()).primary().width(170).onClick(() -> {
                    window.close();
                    r.onClick().run();
                }));
            }
            launch.add(Separator.spacer().flex(1));
            window.content().add(launch, new Separator());
        }

        Label name = new Label(() -> selected == null ? "No code mods installed" : selected.mod().name()).font(Theme.Font.LARGE);
        Label meta = new Label(this::metaLine).font(Theme.Font.SMALL).muted();
        Label description = new Label(() -> selected == null
                ? "Put mod jars in " + modsDir.getAbsolutePath() + " and restart the game."
                : selected.mod().description()).font(Theme.Font.MAIN);
        form = Panel.column().gap(14).padding(4);
        formScroll = new ScrollPanel(form).flex(1);
        Label statusLabel = new Label(() -> status).font(Theme.Font.SMALL).color(() -> statusColor == null ? Theme.textMuted() : statusColor).flex(1);
        Panel buttons = Panel.row().gap(8).height(36).add(statusLabel,
                new Button("Mod folder").width(130).enabled(() -> selected != null).onClick(() -> openFolder(selected.ctx().dataDir())),
                new Button("Revert").width(110).enabled(() -> selected != null).onClick(() -> select(selected)),
                new Button("Save").primary().width(110).enabled(() -> selected != null && !edits.isEmpty()).onClick(this::save));
        Panel right = Panel.column().gap(8).flex(1).add(name, meta, description, new Separator(), formScroll, buttons);
        window.content().add(Panel.row().gap(14).flex(1).add(left, new Separator().width(2), right));
    }

    private String metaLine() {
        if (selected == null) return "";
        AocMod m = selected.mod();
        StringBuilder b = new StringBuilder();
        if (!m.version().isEmpty()) b.append("Version ").append(m.version());
        if (!m.author().isEmpty()) b.append(b.length() > 0 ? "    by " : "by ").append(m.author());
        b.append(b.length() > 0 ? "    " : "").append("id: ").append(m.id());
        return b.toString();
    }

    private void select(Entry e) {
        selected = e;
        list.select(e);
        status = "";
        edits.clear();
        form.clear();
        formScroll.scrollToTop();
        e.ctx().settings().reload();
        List<Setting<?>> settings = e.ctx().settings().all();
        if (settings.isEmpty()) {
            form.add(new Label("This mod has no settings.").muted());
            return;
        }
        for (Setting<?> s : settings) form.add(row(s));
    }

    private Panel row(Setting<?> s) {
        Panel p = Panel.column().gap(4);
        p.add(new Label(s.label()).font(Theme.Font.MAIN));
        switch (s.type()) {
            case TOGGLE: {
                Toggle t = new Toggle("", Boolean.parseBoolean(s.text()));
                edits.put(s.key(), () -> String.valueOf(t.value()));
                p.add(t);
                break;
            }
            case CHOICE: {
                String[] value = {s.text()};
                Button b = new Button(() -> value[0] + "   >").width(320).onClick(() -> {
                    List<String> c = s.choices();
                    value[0] = c.get((c.indexOf(value[0]) + 1) % c.size());
                });
                edits.put(s.key(), () -> value[0]);
                p.add(Panel.row().height(34).add(b));
                break;
            }
            default: {
                TextField f = new TextField().text(s.text()).masked(s.type() == Setting.Type.SECRET);
                if (s.type() == Setting.Type.SECRET) f.placeholder(s.text().isEmpty() ? "not set" : "set (hidden)");
                edits.put(s.key(), f::text);
                p.add(f);
            }
        }
        if (s.help() != null && !s.help().isEmpty()) p.add(new Label(s.help()).font(Theme.Font.SMALL).muted());
        return p;
    }

    private void save() {
        if (selected == null) return;
        Map<String, String> changes = new HashMap<>();
        for (Setting<?> s : selected.ctx().settings().all()) {
            Supplier<String> edit = edits.get(s.key());
            if (edit == null) continue;
            String value = edit.get().trim();
            String error = s.validate(value);
            if (error != null) {
                status = error;
                statusColor = Theme.bad();
                return;
            }
            if (!value.equals(s.text())) changes.put(s.key(), value);
        }
        if (changes.isEmpty()) {
            status = "Nothing changed.";
            statusColor = null;
            return;
        }
        selected.ctx().settings().save(changes);
        status = "Saved " + changes.size() + " setting" + (changes.size() == 1 ? "" : "s") + ".";
        statusColor = Theme.good();
        ui.toast(selected.mod().name() + ": settings saved", 3);
    }

    private static void openFolder(File dir) {
        try {
            dir.mkdirs();
            new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
        } catch (Exception e) {
            Log.error("Could not open " + dir, e);
        }
    }
}
