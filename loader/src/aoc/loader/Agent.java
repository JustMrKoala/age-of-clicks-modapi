package aoc.loader;

import aoc.api.internal.ModManager;

import java.io.File;
import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Started by the game's JVM through
 * {@code java-options=-javaagent:$APPDIR\aoc-loader.jar} in "app/Age of Clicks.cfg".
 *
 * <p>Premain only registers the bytecode hooks and discovers mods; nothing touches
 * game or libGDX classes until the first frame is rendered.
 */
public final class Agent {
    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        // Hooks first, so no game class can load before it is patched.
        inst.addTransformer(new HookTransformer(), false);
        File gameDir = locateGameDir();
        ModManager.init(gameDir);
        ModManager.discoverMods();
    }

    /** The loader jar lives in {@code <game>/app/}, mods in {@code <game>/mods/}. */
    private static File locateGameDir() {
        try {
            File jar = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParentFile().getParentFile();
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }
}
