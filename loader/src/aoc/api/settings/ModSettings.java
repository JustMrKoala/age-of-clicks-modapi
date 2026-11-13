package aoc.api.settings;

import aoc.api.internal.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mod's settings: declare them in {@code onLoad}, read them with {@link Setting#get()}.
 * The "Code Mods" screen on the main menu builds an editor for every declared setting and
 * saves to {@code mods/<id>/config.properties}. Keys the mod did not declare are kept.
 *
 * <pre>{@code
 * Setting<String> key = ctx.settings().secret("api_key", "API key", "Your provider's key");
 * Setting<Boolean> war = ctx.settings().toggle("allow_war", "Allow war", true, "Leaders may attack you");
 * ctx.settings().onChange(this::reload);
 * }</pre>
 */
public final class ModSettings {
    private final File file;
    private final String modName;
    private final Properties values = new Properties();
    private final List<Setting<?>> declared = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public ModSettings(File file, String modName) {
        this.file = file;
        this.modName = modName;
        reload();
    }

    public File file() { return file; }

    // ---- declaring ----

    public Setting<String> text(String key, String label, String defaultValue, String help) {
        return add(new Setting<>(this, key, label, help, Setting.Type.TEXT, defaultValue, List.of(), 0, 0));
    }

    /** Text shown masked, for API keys and passwords. */
    public Setting<String> secret(String key, String label, String help) {
        return add(new Setting<>(this, key, label, help, Setting.Type.SECRET, "", List.of(), 0, 0));
    }

    public Setting<Boolean> toggle(String key, String label, boolean defaultValue, String help) {
        return add(new Setting<>(this, key, label, help, Setting.Type.TOGGLE, defaultValue, List.of(), 0, 0));
    }

    public Setting<Integer> integer(String key, String label, int defaultValue, int min, int max, String help) {
        return add(new Setting<>(this, key, label, help, Setting.Type.INTEGER, defaultValue, List.of(), min, max));
    }

    public Setting<Double> decimal(String key, String label, double defaultValue, double min, double max, String help) {
        return add(new Setting<>(this, key, label, help, Setting.Type.DECIMAL, defaultValue, List.of(), min, max));
    }

    public Setting<String> choice(String key, String label, String defaultValue, List<String> choices, String help) {
        return add(new Setting<>(this, key, label, help, Setting.Type.CHOICE, defaultValue, List.copyOf(choices), 0, 0));
    }

    private <T> Setting<T> add(Setting<T> s) {
        declared.removeIf(d -> d.key().equals(s.key()));
        declared.add(s);
        if (!file.exists()) save(Map.of());
        return s;
    }

    public List<Setting<?>> all() { return Collections.unmodifiableList(declared); }

    /** Called after the player saves new values. */
    public void onChange(Runnable listener) { listeners.add(listener); }

    // ---- storage ----

    synchronized String raw(String key) { return values.getProperty(key); }

    public synchronized void reload() {
        values.clear();
        if (!file.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            values.load(r);
        } catch (Exception e) {
            Log.error("Could not read " + file, e);
        }
    }

    /**
     * Writes new values (key to text) and tells listeners. Values are validated by the caller
     * ({@link Setting#validate}); declared settings are written first, with their help as comments.
     */
    public void save(Map<String, String> changes) {
        synchronized (this) {
            values.putAll(changes);
            StringBuilder out = new StringBuilder();
            out.append("# Settings for ").append(modName).append(". Edit them in game: main menu > MODS > Code Mods.\n");
            List<String> written = new ArrayList<>();
            for (Setting<?> s : declared) {
                out.append('\n');
                if (s.help() != null && !s.help().isEmpty()) {
                    for (String line : s.help().split("\n")) out.append("# ").append(line).append('\n');
                }
                String v = values.getProperty(s.key(), String.valueOf(s.defaultValue()));
                out.append(s.key()).append('=').append(escape(v)).append('\n');
                written.add(s.key());
            }
            Map<String, String> rest = new TreeMap<>();
            for (String k : values.stringPropertyNames()) if (!written.contains(k)) rest.put(k, values.getProperty(k));
            if (!rest.isEmpty()) out.append("\n# Other values\n");
            for (Map.Entry<String, String> e : rest.entrySet()) out.append(e.getKey()).append('=').append(escape(e.getValue())).append('\n');
            try {
                file.getParentFile().mkdirs();
                Files.writeString(file.toPath(), out.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.error("Could not save " + file, e);
            }
        }
        if (!changes.isEmpty()) {
            for (Runnable r : listeners) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.error("Settings listener of " + modName + " failed", t);
                }
            }
        }
    }

    /** Escapes like {@link Properties#store}, but keeps non-ASCII text readable. */
    private static String escape(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        if (b.length() > 0 && b.charAt(0) == ' ') b.insert(0, '\\');
        return b.toString();
    }
}
