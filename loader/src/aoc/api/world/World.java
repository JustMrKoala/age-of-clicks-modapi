package aoc.api.world;

import ru.ius.david.cgs.CityData;
import ru.ius.david.cgs.CountryData;
import ru.ius.david.cgs.WarManager;
import ru.ius.david.cgs.events.EventEffect;
import ru.ius.david.cgs.events.EventEffects;
import ru.ius.david.cgs.events.EventsManager;
import ru.ius.david.cgs.game.CityManager;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct access to the world: countries, cities, territory, governments, events.
 *
 * <p>Every method must run on the render thread (inside a mod callback, or through
 * {@code GameApi.onGameThread}). Countries are identified by internal name; display
 * names and tags are accepted wherever a name is asked for.
 *
 * <p>Territory is a set of map pixels packed as {@code (x << 32) | y}, the format the
 * game's own land actions use.
 */
public final class World {
    /** One city as the player sees it. */
    public record CityInfo(int id, String name, float x, float y, String owner, int level, boolean main) {}

    private float cityScale = Float.NaN;

    // ---- lookup ----

    public GameManager game() { return GameManager.getInstance(); }

    public WarManager wars() { return game().getWarManager(); }

    /** The country with this internal name, display name or tag; {@code null} if none. */
    public CountryState country(String nameOrTag) {
        if (nameOrTag == null) return null;
        Map<String, CountryState> all = game().getWorldCountries();
        CountryState exact = all.get(nameOrTag);
        if (exact != null) return exact;
        for (CountryState s : all.values()) {
            if (nameOrTag.equals(s.getName())) return s;
        }
        for (CountryState s : all.values()) {
            if (nameOrTag.equalsIgnoreCase(s.getName()) || nameOrTag.equalsIgnoreCase(s.getDisplayName())
                    || nameOrTag.equalsIgnoreCase(s.getTag())) return s;
        }
        return null;
    }

    /** Internal name for any accepted spelling, or the input unchanged. */
    public String internalName(String nameOrTag) {
        CountryState s = country(nameOrTag);
        return s == null ? nameOrTag : s.getName();
    }

    /** The key {@code game().getWorldCountries()} files this country under. */
    public String mapKey(CountryState state) {
        for (Map.Entry<String, CountryState> e : game().getWorldCountries().entrySet()) {
            if (e.getValue() == state) return e.getKey();
        }
        return state.getName();
    }

    /** The colour key the war and map systems use for a country; -1 if unknown. */
    public int colorKey(String nameOrTag) {
        CountryState s = country(nameOrTag);
        if (s == null) return -1;
        return wars().getColorKeyByName(s.getName());
    }

    public String nameOfKey(int colorKey) {
        CountryData d = wars().getCountryData(colorKey);
        return d == null ? null : d.name;
    }

    public boolean exists(String nameOrTag) {
        int key = colorKey(nameOrTag);
        if (key < 0) return false;
        CountryData d = wars().getCountryData(key);
        return d != null && d.currentPixels > 0;
    }

    // ---- cities ----

    /** All cities, or only those owned by {@code owner} when it is not null. */
    public List<CityInfo> cities(String owner) {
        CityManager cm = game().getCityManager();
        int ownerKey = owner == null ? Integer.MIN_VALUE : colorKey(owner);
        List<CityInfo> out = new ArrayList<>();
        List<CityData> list = cm.getCities();
        for (int i = 0; i < list.size(); i++) {
            CityData c = list.get(i);
            int key = cm.getOwnerColorKey(i);
            if (owner != null && key != ownerKey) continue;
            out.add(new CityInfo(c.id, cm.getName(i), c.x, c.y, nameOfKey(key), cm.getLevel(i), c.main));
        }
        return out;
    }

    public CityInfo city(String name) {
        for (CityInfo c : cities(null)) if (c.name().equalsIgnoreCase(name)) return c;
        return null;
    }

    /** Map pixel position of a city (city files and the ownership grid can use different scales). */
    public int[] cityPixel(CityInfo c) {
        float s = cityScale();
        return new int[]{Math.round(c.x() * s), Math.round(c.y() * s)};
    }

    private float cityScale() {
        if (!Float.isNaN(cityScale)) return cityScale;
        CityManager cm = game().getCityManager();
        List<CityData> list = cm.getCities();
        float best = 1f;
        int bestHits = -1;
        for (float s : new float[]{1f, 0.5f, 2f}) {
            int hits = 0;
            for (int i = 0; i < list.size(); i += Math.max(1, list.size() / 200)) {
                CityData c = list.get(i);
                int x = Math.round(c.x * s), y = Math.round(c.y * s);
                if (x < 0 || y < 0 || x >= wars().getMapWidth() || y >= wars().getMapHeight()) continue;
                if (wars().getOwnerAt(x, y) == cm.getOwnerColorKey(i)) hits++;
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = s;
            }
        }
        cityScale = best;
        return best;
    }

    // ---- territory ----

    public static long pack(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    public static int unpackX(long p) { return (int) (p >> 32); }

    public static int unpackY(long p) { return (int) p; }

    /** Every pixel {@code owner} holds. */
    public Set<Long> territoryOf(String owner) {
        Set<Long> px = wars().getCountryPixels().get(colorKey(owner));
        return px == null ? new HashSet<>() : new HashSet<>(px);
    }

    /** Pixels of {@code owner} within {@code radius} map pixels of a city. */
    public Set<Long> territoryAround(String owner, String cityName, int radius) {
        CityInfo c = city(cityName);
        Set<Long> out = new HashSet<>();
        if (c == null) return out;
        int[] p = cityPixel(c);
        long r2 = (long) radius * radius;
        for (long px : territoryOf(owner)) {
            long dx = unpackX(px) - p[0], dy = unpackY(px) - p[1];
            if (dx * dx + dy * dy <= r2) out.add(px);
        }
        return out;
    }

    /**
     * Pixels of {@code owner} closest to {@code neighbour}, about {@code share} of the owner's land
     * (0..1). Good for "the border strip" in land deals.
     */
    public Set<Long> territoryFacing(String owner, String neighbour, double share) {
        Set<Long> mine = territoryOf(owner);
        Set<Long> theirs = wars().getCountryPixels().get(colorKey(neighbour));
        if (mine.isEmpty() || theirs == null || theirs.isEmpty()) return new HashSet<>();
        long sx = 0, sy = 0;
        for (long p : theirs) { sx += unpackX(p); sy += unpackY(p); }
        double cx = (double) sx / theirs.size(), cy = (double) sy / theirs.size();
        List<Long> sorted = new ArrayList<>(mine);
        sorted.sort((a, b) -> Double.compare(dist2(a, cx, cy), dist2(b, cx, cy)));
        int n = (int) Math.max(1, Math.min(sorted.size(), Math.round(sorted.size() * share)));
        return new HashSet<>(sorted.subList(0, n));
    }

    private static double dist2(long p, double cx, double cy) {
        double dx = unpackX(p) - cx, dy = unpackY(p) - cy;
        return dx * dx + dy * dy;
    }

    /** Moves land between countries the way peace deals do. Returns pixels moved. */
    public int transferTerritory(String from, String to, Collection<Long> pixels) {
        int fromKey = colorKey(from), toKey = colorKey(to);
        if (fromKey < 0 || toKey < 0) throw new IllegalArgumentException("Unknown country");
        Set<Long> owned = new HashSet<>();
        for (long p : pixels) {
            if (wars().getOwnerAt(unpackX(p), unpackY(p)) == fromKey) owned.add(p);
        }
        if (!owned.isEmpty()) wars().transferPixels(fromKey, toKey, owned);
        return owned.size();
    }

    /** {@code winner} absorbs all of {@code loser}. */
    public void annex(String winner, String loser) {
        wars().annexCountry(colorKey(winner), colorKey(loser));
    }

    // ---- politics ----

    /**
     * Applies one event effect, exactly as a scenario event would. Types: add_gold, add_resource,
     * add_manpower, add_infantry, add_artillery, add_tanks, add_aircraft, add_ships, add_nukes,
     * add_science, add_diplomacy_points, add_war_exhaustion, add_revolution_risk, add_infamy,
     * add_relation (with the player), add_support, set_government, set_tax, set_conscription,
     * set_military_spending, set_social_security, set_science_funding, add_liberty_desire,
     * declare_war, trigger_event.
     */
    public void applyEffect(String country, String type, float value, String textArg) {
        CountryState s = country(country);
        if (s == null) throw new IllegalArgumentException("Unknown country " + country);
        EventEffect e = new EventEffect();
        e.type = type;
        e.value = value;
        e.textArg = textArg == null ? "" : textArg;
        e.target = "SELF";
        EventEffects.apply(e, s);
    }

    /** Government form by enum name, e.g. AUTOCRACY, PARLIAMENTARY_REPUBLIC. Flags follow. */
    public void setGovernment(String country, String form) {
        applyEffect(country, "set_government", 0, form.trim().toUpperCase().replace(' ', '_'));
    }

    /** Fires a scenario event (id from events.json) for a country. */
    public void fireEvent(String eventId, String country, int delayDays) {
        EventsManager.getInstance().fireById(eventId, country(country), Math.max(0, delayDays));
    }

    /** Shows the country under a new name (the game's own custom-name field). */
    public void rename(String country, String newName) throws ReflectiveOperationException {
        CountryState s = country(country);
        if (s == null) throw new IllegalArgumentException("Unknown country " + country);
        Field f = CountryState.class.getDeclaredField("customName");
        f.setAccessible(true);
        f.set(s, newName);
        CountryData d = wars().getCountryData(colorKey(country));
        if (d != null) d.customName = newName;
    }

    /** How {@code a} feels about {@code b} in the AI's own relation table. */
    public void changeAiRelation(String a, String b, int delta) {
        CountryState sa = country(a), sb = country(b);
        if (sa == null || sb == null) throw new IllegalArgumentException("Unknown country");
        sa.modifyAiRelation(sb.getName(), delta);
    }

    /** Ends the war between two countries, if there is one. */
    public boolean makePeace(String a, String b) {
        int ka = colorKey(a), kb = colorKey(b);
        for (WarManager.War w : new ArrayList<>(wars().getActiveWars())) {
            int att = w.attacker.colorKey, def = w.defender.colorKey;
            if ((att == ka && def == kb) || (att == kb && def == ka)) {
                wars().makePeace(w);
                return true;
            }
        }
        return false;
    }

    public boolean atWar(String a, String b) {
        int ka = colorKey(a), kb = colorKey(b);
        for (WarManager.War w : wars().getActiveWars()) {
            int att = w.attacker.colorKey, def = w.defender.colorKey;
            if ((att == ka && def == kb) || (att == kb && def == ka)) {
                return true;
            }
        }
        return false;
    }
}
