package aoc.api.internal;

import aoc.api.GameApi.CountryInfo;
import aoc.api.ModContext;
import aoc.api.actions.ActionResult;
import aoc.api.actions.ActionSpec;
import aoc.api.actions.Actions;
import aoc.api.actions.ParamSpec;
import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.ListView;
import aoc.api.ui.MessageList;
import aoc.api.ui.Panel;
import aoc.api.ui.Separator;
import aoc.api.ui.TextField;
import aoc.api.ui.Theme;
import aoc.api.ui.Toggle;
import aoc.api.ui.Window;

import java.util.Map;

/** Developer window: run any catalog action by hand and see what the game made of it. */
final class ActionTester {
    private final ModContext ctx;
    private Window window;
    private ListView<ActionSpec> list;
    private ActionSpec selected;
    private TextField actor;
    private TextField args;
    private Toggle approved;
    private final MessageList results = new MessageList().flex(1);

    ActionTester(ModContext ctx) { this.ctx = ctx; }

    void toggle() {
        if (window == null) build();
        if (window.isOpen()) {
            window.close();
        } else {
            CountryInfo player = ctx.game().player();
            if (actor.text().isEmpty() && player != null) actor.text(player.displayName());
            ctx.ui().open(window);
        }
    }

    void close() {
        if (window != null && window.isOpen()) window.close();
    }

    private void build() {
        window = new Window("Action Tester", 1000, 640);
        TextField search = new TextField().placeholder("Search actions...").maxLength(40);
        list = new ListView<ActionSpec>()
                .items(() -> ctx.actions().all())
                .label(ActionSpec::id)
                .note(s -> new ListView.Note(s.category(), null))
                .onSelect(this::select)
                .flex(1);
        search.onChange(t -> list.filter(s -> s.id().contains(t.toLowerCase()) || s.category().contains(t.toLowerCase())));
        Panel left = Panel.column().gap(8).width(300).add(search, list);

        Label signature = new Label(() -> selected == null ? "Pick an action" : selected.signature()).font(Theme.Font.LARGE);
        Label description = new Label(() -> selected == null ? "" : selected.description() + paramHelp()).font(Theme.Font.SMALL).muted();
        actor = new TextField().placeholder("Acting country (name or tag)");
        args = new TextField().placeholder("Arguments as JSON, e.g. {\"target\": \"Italy\", \"amount\": 100}").maxLength(2000);
        approved = new Toggle("Other side already agreed (approved)", false);
        Button run = new Button("Run").primary().width(120).enabled(() -> selected != null).onClick(this::run);
        Panel right = Panel.column().gap(8).flex(1).add(
                signature, description,
                new Label("Actor").font(Theme.Font.SMALL), actor,
                new Label("Arguments").font(Theme.Font.SMALL), args,
                Panel.row().gap(10).height(36).add(approved.flex(1), run),
                new Separator(), results);
        window.content().add(Panel.row().gap(14).flex(1).add(left, new Separator().width(2), right));
        results.add(MessageList.Side.NOTICE, null,
                "Actions run for real in this game. World actions and approved runs mark the game as externally modified.");
    }

    private String paramHelp() {
        StringBuilder b = new StringBuilder();
        for (ParamSpec p : selected.params()) {
            b.append("\n").append(p.name()).append(" (").append(p.kind().name().toLowerCase())
             .append(p.required() ? ", required" : ", default " + p.defaultValue()).append(")");
            if (p.description() != null && !p.description().isEmpty()) b.append(": ").append(p.description());
        }
        return b.toString();
    }

    private void select(ActionSpec spec) {
        selected = spec;
        StringBuilder skeleton = new StringBuilder("{");
        for (ParamSpec p : spec.params()) {
            if (!p.required()) continue;
            if (skeleton.length() > 1) skeleton.append(", ");
            skeleton.append('"').append(p.name()).append("\": ");
            skeleton.append(p.kind() == ParamSpec.Kind.INT || p.kind() == ParamSpec.Kind.LONG || p.kind() == ParamSpec.Kind.FLOAT ? "0"
                    : p.kind() == ParamSpec.Kind.BOOL ? "false" : "\"\"");
        }
        args.text(skeleton.append('}').toString());
    }

    private void run() {
        if (selected == null) return;
        Map<String, Object> parsed;
        try {
            parsed = Actions.argsFromJson(args.text());
        } catch (Exception e) {
            results.add(MessageList.Side.NOTICE, null, "Arguments are not valid JSON: " + e.getMessage(), Theme.bad());
            return;
        }
        String who = actor.text().trim();
        String call = selected.id() + " by " + who + " " + parsed + (approved.value() ? " (approved)" : "");
        results.add(MessageList.Side.RIGHT, "You", call);
        ActionResult r = ctx.actions().performNow(selected.id(), who, parsed, approved.value());
        String verdict = !r.ok() ? "FAILED" : r.verified() ? "DONE" : "SENT (not confirmable)";
        results.add(MessageList.Side.LEFT, "Game", verdict + ": " + r.message(),
                !r.ok() ? Theme.bad() : r.verified() ? Theme.good() : Theme.textMuted());
    }
}
