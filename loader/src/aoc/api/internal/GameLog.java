package aoc.api.internal;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Gdx;

import java.util.ArrayList;
import java.util.List;

/**
 * Listens to the game's own log while an action runs. Many game actions refuse silently
 * except for a log line ("REFUSED by Togo: ..."); capturing it turns that into a reason the
 * player can read. Render thread only.
 */
public final class GameLog {
    private static List<String> capture;
    private static boolean installed;
    private static int savedLevel = -1;

    private GameLog() {}

    /** Starts collecting log lines; pair with {@link #end()}. */
    public static void begin() {
        install();
        capture = new ArrayList<>();
        if (Gdx.app != null && Gdx.app.getLogLevel() < Application.LOG_INFO) {
            savedLevel = Gdx.app.getLogLevel();
            Gdx.app.setLogLevel(Application.LOG_INFO);
        }
    }

    /** Lines logged since {@link #begin()}. */
    public static List<String> end() {
        List<String> out = capture == null ? List.of() : capture;
        capture = null;
        if (savedLevel >= 0 && Gdx.app != null) Gdx.app.setLogLevel(savedLevel);
        savedLevel = -1;
        return out;
    }

    /** The first captured line that reads like a refusal, or null. */
    public static String refusal(List<String> lines) {
        for (String l : lines) {
            String low = l.toLowerCase();
            if (low.contains("refused") || low.contains("failed") || low.contains("cannot") || low.contains("can't")
                    || low.contains("not enough") || low.contains("rejected") || low.contains("declined")
                    || low.contains("not in the same") || low.contains("invalid")) {
                return l;
            }
        }
        return null;
    }

    private static void record(String tag, String message) {
        if (capture != null && message != null) capture.add(tag == null ? message : tag + ": " + message);
    }

    private static void install() {
        if (installed || Gdx.app == null) return;
        installed = true;
        ApplicationLogger inner = Gdx.app.getApplicationLogger();
        Gdx.app.setApplicationLogger(new ApplicationLogger() {
            @Override public void log(String tag, String message) { record(tag, message); inner.log(tag, message); }
            @Override public void log(String tag, String message, Throwable t) { record(tag, message); inner.log(tag, message, t); }
            @Override public void error(String tag, String message) { record(tag, message); inner.error(tag, message); }
            @Override public void error(String tag, String message, Throwable t) { record(tag, message); inner.error(tag, message, t); }
            @Override public void debug(String tag, String message) { inner.debug(tag, message); }
            @Override public void debug(String tag, String message, Throwable t) { inner.debug(tag, message, t); }
        });
    }
}
