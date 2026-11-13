package aoc.bulkcities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Makes up town names in the style of a region: a letter-by-letter chain learned from the names of
 * the towns around the spot (three letters of memory, falling back to fewer for rare letter runs),
 * so a new town in Bavaria sounds German and one in the Urals sounds Russian.
 */
final class NameForge {
    private static final char START = '^', END = '$';
    private static final int ORDER = 3;

    private NameForge() {}

    /**
     * A new name learned from {@code examples}, none of whose keys are in {@code takenKeys}, or null
     * when the examples are too few or nothing good came out.
     */
    static String make(List<String> examples, Set<String> takenKeys, long seed) {
        List<String> words = new ArrayList<>();
        Set<String> exampleKeys = new HashSet<>();
        for (String e : examples) {
            String w = e.toLowerCase(Locale.ROOT).trim();
            if (w.length() < 3 || w.length() > 20 || !w.chars().allMatch(c -> Character.isLetter(c) || c == ' ' || c == '-' || c == '\'')) continue;
            words.add(w);
            exampleKeys.add(Gazetteer.key(w));
        }
        if (words.size() < 6) return null;

        // counts[order][context] -> next letter -> count
        List<Map<String, Map<Character, Integer>>> counts = new ArrayList<>();
        for (int o = 0; o <= ORDER; o++) counts.add(new HashMap<>());
        int lengthSum = 0;
        for (String w : words) {
            lengthSum += w.length();
            String padded = String.valueOf(START).repeat(ORDER) + w + END;
            for (int i = ORDER; i < padded.length(); i++) {
                char next = padded.charAt(i);
                for (int o = 1; o <= ORDER; o++) {
                    String ctx = padded.substring(i - o, i);
                    counts.get(o).computeIfAbsent(ctx, k -> new HashMap<>()).merge(next, 1, Integer::sum);
                }
            }
        }
        int meanLength = Math.round(lengthSum / (float) words.size());

        Random rnd = new Random(seed);
        for (int attempt = 0; attempt < 300; attempt++) {
            StringBuilder sb = new StringBuilder(String.valueOf(START).repeat(ORDER));
            while (sb.length() < ORDER + 18) {
                Character next = null;
                // Stronger memory first; with few choices left, fall back now and then for variety.
                for (int o = ORDER; o >= 1 && next == null; o--) {
                    Map<Character, Integer> options = counts.get(o).get(sb.substring(sb.length() - o));
                    if (options == null || (o > 1 && options.size() == 1 && rnd.nextInt(4) == 0)) continue;
                    next = pick(options, rnd);
                }
                if (next == null || next == END) break;
                sb.append(next);
            }
            String name = sb.substring(ORDER).trim();
            if (!good(name, meanLength)) continue;
            String k = Gazetteer.key(name);
            if (exampleKeys.contains(k) || takenKeys.contains(k)) continue;
            return capitalise(name);
        }
        return null;
    }

    private static Character pick(Map<Character, Integer> options, Random rnd) {
        int total = 0;
        for (int c : options.values()) total += c;
        int r = rnd.nextInt(total);
        for (Map.Entry<Character, Integer> e : options.entrySet()) {
            r -= e.getValue();
            if (r < 0) return e.getKey();
        }
        return null;
    }

    private static boolean good(String name, int meanLength) {
        if (name.length() < 4 || name.length() > Math.max(10, meanLength + 4)) return false;
        String[] parts = name.split("[ -]");
        if (parts.length > 2 || name.startsWith("-") || name.endsWith("-") || name.contains("  ")) return false;
        if (parts.length == 2 && parts[1].length() < 3) return false; // a cut-off word: "Caldas Da"
        boolean vowel = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ("aeiouyàáâãäåæèéêëìíîïòóôõöøùúûüýаеёиоуыэюя".indexOf(c) >= 0) vowel = true;
            if (i >= 2 && c == name.charAt(i - 1) && c == name.charAt(i - 2)) return false;
        }
        return vowel;
    }

    /** "bad tölz" -> "Bad Tölz", "saint-malo" -> "Saint-Malo". */
    static String capitalise(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean up = true;
        for (char c : name.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == ' ' || c == '-';
        }
        return sb.toString();
    }
}
