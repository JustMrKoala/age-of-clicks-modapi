package aoc.api.internal;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Writes to {@code <game>/mods/aoc-loader.log} and to the game's console log. */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static File file;

    private Log() {}

    static void init(File gameDir) {
        File dir = new File(gameDir, "mods");
        dir.mkdirs();
        file = new File(dir, "aoc-loader.log");
        file.delete();
    }

    public static void info(String message) { write("INFO", message, null); }

    public static void error(String message, Throwable t) { write("ERROR", message, t); }

    private static synchronized void write(String level, String message, Throwable t) {
        String line = "[AocLoader] " + LocalTime.now().format(TIME) + " " + level + " " + message;
        System.out.println(line);
        if (file == null) return;
        try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
            out.println(line);
            if (t != null) t.printStackTrace(out);
        } catch (Exception ignored) {
            // Logging must never break the game.
        }
    }
}
