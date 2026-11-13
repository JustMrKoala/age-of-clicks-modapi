package aoc.replay;

import ru.ius.david.cgs.CountryData;
import ru.ius.david.cgs.WarManager;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the film's bottom bar shows about the focus country, read live from the game: flag,
 * name, government, alliance and enemies, and a row of stats.
 *
 * @param flagImage the game's image id of the flag ({@code ImageManager.getImage}), or -1
 * @param stats     label and value pairs, most important first
 */
record CountryCard(int flagImage, String name, String subtitle, List<String[]> stats, double growth) {

    static CountryCard of(ReplayState st, int focus) {
        GameManager gm = GameManager.getInstance();
        WarManager wm = gm == null ? null : gm.getWarManager();
        CountryState s = null;
        if (wm != null) {
            for (CountryState c : gm.getWorldCountries().values()) {
                if (wm.getColorKeyByName(c.getName()) == focus) {
                    s = c;
                    break;
                }
            }
        }
        String name = s != null ? s.getDisplayName() : st.replay.countryName(focus);
        int flag = -1;
        CountryData cd = wm == null ? null : wm.getCountryData(focus);
        if (cd != null && cd.tag != null) {
            try {
                flag = ru.ius.david.cgs.images.CountryFlagManager.getInstance().getFlagImageId(cd.tag, cd.color);
            } catch (Throwable ignored) {
                // no flag for this country; the bar shows its colour instead
            }
        }

        // Subtitle: enemies first (they matter most during a war), then government and alliance.
        List<String> sub = new ArrayList<>();
        if (s != null && s.currentForm != null && s.currentForm.displayName != null) sub.add(s.currentForm.displayName);
        if (s != null && s.allianceName != null && !s.allianceName.isBlank()) sub.add(s.allianceName);
        List<String> enemies = new ArrayList<>();
        for (Replay.War w : st.replay.frames.get(st.frame()).wars()) {
            if (w.attacker() == focus) enemies.add(st.replay.countryName(w.defender()));
            else if (w.defender() == focus) enemies.add(st.replay.countryName(w.attacker()));
        }
        if (!enemies.isEmpty()) sub.add(0, "At war with " + String.join(", ", enemies.subList(0, Math.min(3, enemies.size())))
                + (enemies.size() > 3 ? " +" + (enemies.size() - 3) : ""));

        List<String[]> stats = new ArrayList<>();
        Replay.Stats mine = st.stats.get(focus);
        double growth = growth(st, focus);
        if (mine != null) stats.add(new String[]{"GDP", FilmOverlay.compact(mine.income())});
        if (s != null) {
            stats.add(new String[]{"Population", FilmOverlay.compact(s.getTotalPopulation())});
            long army = s.getInfantry() + s.getArtillery() + s.getTanks();
            stats.add(new String[]{"Army", FilmOverlay.compact(army)});
            stats.add(new String[]{"Treasury", FilmOverlay.compact(s.getGold())});
        }
        ReplayState.Area area = st.areas.get(focus);
        int total = 0;
        for (ReplayState.Area a : st.areas.values()) total += a.cells;
        if (area != null && total > 0) stats.add(new String[]{"Land", String.format(Locale.ROOT, "%.1f%%", area.cells * 100.0 / total)});
        stats.add(new String[]{"Cities", String.valueOf(cities(st, focus))});
        int rank = rank(st, focus);
        if (rank > 0) stats.add(new String[]{"World rank", "#" + rank});
        return new CountryCard(flag, name, String.join("   ·   ", sub), stats, growth);
    }

    /** Cities whose ground the country holds now. */
    private static int cities(ReplayState st, int focus) {
        int n = 0;
        int scale = st.replay.scale;
        for (Replay.City c : st.cities.values()) if (st.ownerAt(c.x() / scale, c.y() / scale) == focus) n++;
        return n;
    }

    /** Place by GDP among all countries. */
    private static int rank(ReplayState st, int focus) {
        Replay.Stats mine = st.stats.get(focus);
        if (mine == null) return 0;
        int better = 0;
        for (Replay.Stats s : st.stats.values()) if (s.income() > mine.income()) better++;
        return better + 1;
    }

    /** GDP growth over the last year of recorded frames, in percent (NaN if unknown). */
    private static double growth(ReplayState st, int focus) {
        Replay.Stats now = st.stats.get(focus);
        List<Replay.Frame> fs = st.replay.frames;
        int f = st.frame(), back = f;
        while (back > 0 && fs.get(back).day() > fs.get(f).day() - 365) back--;
        if (now == null || back == f) return Double.NaN;
        for (Replay.Stats s : fs.get(back).stats()) if (s.key() == focus && s.income() > 0) return (now.income() / s.income() - 1) * 100;
        return Double.NaN;
    }
}
