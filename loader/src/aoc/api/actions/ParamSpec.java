package aoc.api.actions;

/**
 * One parameter of an action.
 *
 * <p>Value forms by kind:
 * <ul>
 *   <li>{@code COUNTRY}: internal name, display name or tag.</li>
 *   <li>{@code CITY}: city name.</li>
 *   <li>{@code TERRITORY}: {@code "all"}; a city name; a list of city names;
 *       {@code {"cities":[...], "radius":30}}; or {@code {"facing":"Country", "share":0.1}}.</li>
 *   <li>{@code RESOURCE}: iron, coal, oil, timber, rubber, aluminum, uranium, manpower,
 *       infantry, artillery, tanks, aircraft, ships (some actions also take steel, fuel, ammo, parts).</li>
 *   <li>{@code GOVERNMENT}: a government form such as AUTOCRACY or PARLIAMENTARY_REPUBLIC.</li>
 *   <li>numbers, booleans and text as usual.</li>
 * </ul>
 * A parameter with a {@code defaultValue} may be left out.
 */
public record ParamSpec(String name, Kind kind, String description, Object defaultValue) {
    public enum Kind { COUNTRY, CITY, TERRITORY, RESOURCE, GOVERNMENT, INT, LONG, FLOAT, BOOL, TEXT }

    public boolean required() { return defaultValue == null; }

    public static ParamSpec of(String name, Kind kind, String description) {
        return new ParamSpec(name, kind, description, null);
    }

    public static ParamSpec opt(String name, Kind kind, String description, Object defaultValue) {
        return new ParamSpec(name, kind, description, defaultValue);
    }
}
