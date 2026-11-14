package aoc.api.internal;

import aoc.api.AocMod;
import aoc.api.ModContext;
import aoc.api.settings.Setting;

/**
 * Game Improvements: small improvements to the game itself that ship inside the loader. Each one
 * has its own switch in main menu > MODS > CODE MODS > Game Improvements. Like every code mod it
 * is paused in multiplayer.
 */
final class Improvements implements AocMod {
    private Setting<Boolean> frontNumbers;
    private FrontNumbers numbers;

    @Override public String id() { return "improvements"; }

    @Override public String name() { return "Game Improvements"; }

    @Override public String version() { return Version.LOADER; }

    @Override public String author() { return "Age of Clicks Mod API"; }

    @Override
    public String description() {
        return "Improvements to the game itself, built into the mod loader. Switch each one on or off below.";
    }

    @Override
    public void onLoad(ModContext ctx) {
        frontNumbers = ctx.settings().toggle("front_numbers", "Army numbers on the fronts", true,
                "At war, each side's army on a front is written on its own side of the line (classic combat).");
    }

    @Override
    public void render(ModContext ctx, float delta) {
        if (!frontNumbers.get()) return;
        if (numbers == null) numbers = new FrontNumbers();
        numbers.render(delta);
    }
}
