import aoc.api.GameApi;
import aoc.api.actions.ActionSpec;
import aoc.api.actions.Actions;
import aoc.api.actions.ParamSpec;
import aoc.api.world.World;

import java.util.ArrayList;
import java.util.List;

/** Prints docs/action-catalog.md from the built-in catalog. Run by build.sh --dist; needs no running game. */
public class CatalogDump {
    static String cell(String s) { return s.replace("|", "\\|").replace("\n", " "); }

    public static void main(String[] a) {
        List<ActionSpec> specs = new ArrayList<>(new Actions(new GameApi(), new World()).all());
        specs.sort((x, y) -> x.category().compareTo(y.category()));
        StringBuilder b = new StringBuilder("# Action catalog\n\n")
                .append("Generated from the loader's code: the ").append(specs.size())
                .append(" built-in actions, by category. How to call them and what the parameter kinds accept: ")
                .append("[Actions](actions.md). \"The actor\" is the country passed to `perform`.\n");
        String category = null;
        for (ActionSpec s : specs) {
            if (!s.category().equals(category)) {
                category = s.category();
                b.append("\n## ").append(category).append("\n\n| Action | What it does | Parameters |\n|---|---|---|\n");
            }
            StringBuilder p = new StringBuilder();
            for (ParamSpec ps : s.params()) {
                if (p.length() > 0) p.append("<br>");
                p.append('`').append(ps.name()).append("` ").append(ps.kind().name().toLowerCase());
                if (!ps.required()) {
                    String d = String.valueOf(ps.defaultValue());
                    p.append(d.isEmpty() ? " (optional)" : " (optional, default " + d + ")");
                }
                if (ps.description() != null && !ps.description().isEmpty()) p.append(": ").append(ps.description());
            }
            b.append("| `").append(s.id()).append("` | ").append(cell(s.description())).append(" | ")
                    .append(p.length() == 0 ? "none" : cell(p.toString())).append(" |\n");
        }
        System.out.print(b);
    }
}
