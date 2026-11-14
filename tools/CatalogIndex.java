import aoc.api.AocMod;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ServiceLoader;

/**
 * Prints the Mod Browser's index (catalog/index.json) for the mod jars in a folder: each mod's own
 * id, name, version, author and description, plus file name, size and SHA-256.
 * Run by build.sh; args: the folder with the jars, the loader version they were built for.
 */
public class CatalogIndex {
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    public static void main(String[] a) throws Exception {
        File[] jars = new File(a[0]).listFiles((d, n) -> n.endsWith(".jar"));
        Arrays.sort(jars);
        StringBuilder out = new StringBuilder("{\n  \"mods\": [");
        boolean first = true;
        for (File jar : jars) {
            byte[] bytes = Files.readAllBytes(jar.toPath());
            StringBuilder sum = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(bytes)) sum.append(String.format("%02x", x));
            try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()}, CatalogIndex.class.getClassLoader())) {
                for (AocMod m : ServiceLoader.load(AocMod.class, cl)) {
                    out.append(first ? "\n" : ",\n").append("    {")
                            .append("\"id\": ").append(q(m.id()))
                            .append(", \"name\": ").append(q(m.name()))
                            .append(", \"version\": ").append(q(m.version()))
                            .append(", \"author\": ").append(q(m.author()))
                            .append(",\n     \"description\": ").append(q(m.description()))
                            .append(",\n     \"file\": ").append(q(jar.getName()))
                            .append(", \"size\": ").append(bytes.length)
                            .append(", \"loader\": ").append(q(a[1]))
                            .append(",\n     \"sha256\": ").append(q(sum.toString())).append("}");
                    first = false;
                }
            }
        }
        System.out.println(out.append("\n  ]\n}"));
    }
}
