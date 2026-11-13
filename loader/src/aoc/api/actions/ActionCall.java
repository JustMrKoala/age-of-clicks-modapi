package aoc.api.actions;

import aoc.api.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One invocation of an action: who acts, with which arguments. Typed getters convert names. */
public final class ActionCall {
    private final ActionSpec spec;
    private final String actor;
    private final Map<String, Object> args;
    private final boolean approved;
    private final World world;

    ActionCall(ActionSpec spec, String actor, Map<String, Object> args, boolean approved, World world) {
        this.spec = spec;
        this.actor = actor;
        this.args = args;
        this.approved = approved;
        this.world = world;
    }

    public ActionSpec spec() { return spec; }

    /** Internal name of the acting country. */
    public String actor() { return actor; }

    /** True when the other side already agreed (an accepted deal); the game then skips its AI check. */
    public boolean approved() { return approved; }

    public World world() { return world; }

    public Object raw(String name) {
        Object v = args.get(name);
        if (v != null) return v;
        for (ParamSpec p : spec.params()) if (p.name().equals(name)) return p.defaultValue();
        return null;
    }

    public boolean has(String name) { return args.get(name) != null; }

    public String text(String name) {
        Object v = raw(name);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** Internal country name; fails if the country does not exist. */
    public String country(String name) {
        String given = text(name);
        if (given == null || given.isEmpty()) throw new IllegalArgumentException("Missing country '" + name + "'");
        if (given.equalsIgnoreCase("self") || given.equalsIgnoreCase("actor")) return actor;
        if (world.country(given) == null) throw new IllegalArgumentException("No country called " + given);
        return world.mapKey(world.country(given));
    }

    public int countryKey(String name) { return world.colorKey(country(name)); }

    public long number(String name) {
        Object v = raw(name);
        if (v == null) throw new IllegalArgumentException("Missing number '" + name + "'");
        if (v instanceof Number n) return Math.round(n.doubleValue());
        return Math.round(Double.parseDouble(String.valueOf(v).trim()));
    }

    public int integer(String name) { return (int) number(name); }

    public float decimal(String name) {
        Object v = raw(name);
        if (v instanceof Number n) return n.floatValue();
        return Float.parseFloat(String.valueOf(v).trim());
    }

    public boolean flag(String name) {
        Object v = raw(name);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(String.valueOf(v).trim());
    }

    public String resource(String name) {
        String r = text(name);
        if (r == null) throw new IllegalArgumentException("Missing resource '" + name + "'");
        return r.toLowerCase();
    }

    public String government(String name) {
        String g = text(name);
        if (g == null) throw new IllegalArgumentException("Missing government '" + name + "'");
        return g.toUpperCase().replace(' ', '_');
    }

    /** Map pixel of a city parameter. */
    public int[] city(String name) {
        String c = text(name);
        World.CityInfo info = c == null ? null : world.city(c);
        if (info == null) throw new IllegalArgumentException("No city called " + c);
        return world.cityPixel(info);
    }

    /** Land of {@code owner} selected by a territory parameter (see {@link ParamSpec}). */
    @SuppressWarnings("unchecked")
    public Set<Long> territory(String name, String owner) {
        Object v = raw(name);
        if (v == null) throw new IllegalArgumentException("Missing territory '" + name + "'");
        Set<Long> out = new HashSet<>();
        if (v instanceof Map<?, ?> m) {
            int radius = m.get("radius") instanceof Number n ? n.intValue() : 30;
            Object cities = m.get("cities");
            if (cities == null) cities = m.get("city");
            if (cities != null) addCities(out, owner, cities, radius);
            Object facing = m.get("facing");
            if (facing != null) {
                double share = m.get("share") instanceof Number n ? n.doubleValue() : 0.1;
                out.addAll(world.territoryFacing(owner, world.internalName(String.valueOf(facing)), share));
            }
            if (Boolean.TRUE.equals(m.get("all"))) out.addAll(world.territoryOf(owner));
        } else if ("all".equalsIgnoreCase(String.valueOf(v).trim())) {
            out.addAll(world.territoryOf(owner));
        } else {
            addCities(out, owner, v, 30);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("That territory selection contains no land of " + owner);
        return out;
    }

    private void addCities(Set<Long> out, String owner, Object cities, int radius) {
        List<Object> list = cities instanceof Collection<?> c ? new ArrayList<>(c) : List.of(cities);
        for (Object city : list) {
            World.CityInfo info = world.city(String.valueOf(city));
            if (info == null) throw new IllegalArgumentException("No city called " + city);
            out.addAll(world.territoryAround(owner, info.name(), radius));
        }
    }
}
