package com.example.hello;

import aoc.api.AocMod;
import aoc.api.GameApi.CountryInfo;
import aoc.api.ModContext;
import aoc.api.actions.Actions;
import aoc.api.settings.Setting;
import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.ListView;
import aoc.api.ui.Panel;
import aoc.api.ui.Theme;
import aoc.api.ui.Ui;
import aoc.api.ui.Window;
import com.badlogic.gdx.Input;

import java.util.List;

/**
 * Template mod: one setting, a window with a country list, a HUD button, a hotkey and one
 * catalog action. Rename the package and class, change {@link #id()}, and keep
 * META-INF/services/aoc.api.AocMod pointing at the class.
 */
public final class HelloMod implements AocMod {
    private Setting<Integer> giftAmount;
    private Window window;
    private List<CountryInfo> countries = List.of();

    @Override public String id() { return "hello"; }
    @Override public String name() { return "Hello Mod"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String author() { return "You"; }
    @Override public String description() { return "Template mod: a window, a setting and one action."; }

    @Override
    public void onLoad(ModContext ctx) {
        // Shown in main menu > MODS > CODE MODS > Hello Mod; saved in mods/hello/config.properties.
        giftAmount = ctx.settings().integer("gift_amount", "Gift amount", 100, 1, 10_000,
                "Gold the Gift button sends");
    }

    @Override
    public void onGameReady(ModContext ctx) {
        ListView<CountryInfo> list = new ListView<CountryInfo>()
                .items(() -> countries)
                .label(CountryInfo::displayName)
                .note(c -> c.ally() ? new ListView.Note("ally", Theme.good()) : null)
                .flex(1);
        Button gift = new Button(() -> "Gift " + giftAmount.get() + " gold")
                .primary()
                .enabled(() -> list.selected() != null)
                .onClick(() -> ctx.actions()
                        .perform("give_gold", ctx.game().player().name(),
                                Actions.args("target", list.selected().name(), "amount", giftAmount.get()), false)
                        .thenAccept(r -> ctx.ui().toast(r.message(), 4)));

        window = new Window("Hello", 420, 460);
        window.content().add(
                new Label(() -> "You lead " + ctx.game().player().displayName() + ", " + ctx.game().date()),
                list,
                Panel.row().gap(8).add(gift, new Button("Close").onClick(window::close)));

        ctx.ui().addHud(new Button("Hello").onClick(() -> toggle(ctx)), Ui.Anchor.TOP_LEFT, 12, 188, ctx.game()::inGame);
        ctx.ui().bindKey(Input.Keys.F9, () -> { if (ctx.game().inGame()) toggle(ctx); });
        ctx.log("ready");
    }

    private void toggle(ModContext ctx) {
        // Read the country list once per opening instead of every frame.
        if (!window.isOpen()) countries = ctx.game().countries();
        ctx.ui().toggle(window);
    }
}
