package aoc.api.internal;

import java.io.InputStream;
import java.util.Properties;

/** Version of the loader and API; shown in Code Mods and written to the jar manifest by build.sh. */
public final class Version {
    public static final String LOADER = "0.5.0";

    /**
     * Central lobby server the loader suggests (host:port), or "" when the build has none.
     * build.sh writes it into the jar as aoc-build.properties from AOC_CENTRAL_SERVER or the
     * untracked file central-server.txt, so no server address lives in the source.
     */
    public static final String CENTRAL_SERVER = buildProperty("central_server");

    private Version() {}

    private static String buildProperty(String key) {
        try (InputStream in = Version.class.getResourceAsStream("/aoc-build.properties")) {
            if (in == null) return "";
            Properties p = new Properties();
            p.load(in);
            return p.getProperty(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
