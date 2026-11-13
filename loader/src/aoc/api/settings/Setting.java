package aoc.api.settings;

import java.util.List;

/**
 * One user-editable value of a mod, shown in the "Code Mods" screen and stored in the mod's
 * {@code config.properties}. Read it with {@link #get()} whenever you need it; the value
 * changes when the player saves new settings.
 */
public final class Setting<T> {
    public enum Type { TEXT, SECRET, TOGGLE, INTEGER, DECIMAL, CHOICE }

    private final ModSettings owner;
    private final String key;
    private final String label;
    private final String help;
    private final Type type;
    private final T defaultValue;
    private final List<String> choices;
    private final double min, max;

    Setting(ModSettings owner, String key, String label, String help, Type type, T defaultValue,
            List<String> choices, double min, double max) {
        this.owner = owner;
        this.key = key;
        this.label = label;
        this.help = help;
        this.type = type;
        this.defaultValue = defaultValue;
        this.choices = choices;
        this.min = min;
        this.max = max;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String help() { return help; }
    public Type type() { return type; }
    public T defaultValue() { return defaultValue; }
    public List<String> choices() { return choices; }
    public double min() { return min; }
    public double max() { return max; }

    /** Current saved value. */
    @SuppressWarnings("unchecked")
    public T get() { return (T) parse(owner.raw(key)); }

    /** Text form of the saved value, as the settings screen edits it. */
    public String text() {
        String raw = owner.raw(key);
        return raw == null ? String.valueOf(defaultValue) : raw;
    }

    /** Checks text typed by the player; returns an error message or null. */
    public String validate(String text) {
        String t = text == null ? "" : text.trim();
        try {
            switch (type) {
                case INTEGER: {
                    long v = Long.parseLong(t);
                    if (v < min || v > max) return label + " must be between " + (long) min + " and " + (long) max;
                    return null;
                }
                case DECIMAL: {
                    double v = Double.parseDouble(t);
                    if (v < min || v > max) return label + " must be between " + min + " and " + max;
                    return null;
                }
                case CHOICE:
                    return choices.contains(t) ? null : label + " must be one of " + choices;
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return label + " must be a number";
        }
    }

    private Object parse(String raw) {
        if (raw == null) return defaultValue;
        String t = raw.trim();
        try {
            switch (type) {
                case TOGGLE: return Boolean.parseBoolean(t);
                case INTEGER: return (int) Math.max(min, Math.min(max, Long.parseLong(t)));
                case DECIMAL: return Math.max(min, Math.min(max, Double.parseDouble(t)));
                case CHOICE: return choices.contains(t) ? t : defaultValue;
                default: return raw;
            }
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
