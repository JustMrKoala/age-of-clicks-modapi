package aoc.diplomacy;

import aoc.api.GameApi.CountryInfo;
import aoc.api.ModContext;
import aoc.api.actions.ActionResult;
import aoc.api.actions.ActionSpec;
import aoc.api.actions.ParamSpec;
import aoc.api.world.World;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Turns game state into a leader's briefing, keeps each conversation's history, and carries
 * out terms through the mod API's action catalog, so a leader can agree to anything the API
 * can do.
 */
final class Negotiator {
    /** Older turns are dropped so prompts stay small; the briefing is rebuilt every turn. */
    private static final int HISTORY = 12;

    /** Things a leader may do on their own, without the player clicking Accept. */
    private static final Set<String> UNILATERAL = Set.of(
            "change_attitude", "declare_war", "justify_war", "demand_gold", "ultimatum", "improve_relations",
            "diplomatic_campaign", "call_allies", "build", "war_economy", "production_focus", "alliance_doctrine",
            "set_government", "sponsor_uprising", "market_loan", "leave_alliance", "non_aggression_pact");

    /** Parameters that name "the other side" and are filled in when the model leaves them out. */
    private static final List<String> COUNTERPART_PARAMS = List.of(
            "target", "receiver", "counterparty", "seller", "lessor", "lessee", "from");

    private static final String RULES = String.join("\n",
            "You are playing the head of government of %1$s in a grand strategy game, on %2$s.",
            "The human player leads %3$s and is talking to you directly.",
            "",
            "How to behave:",
            "- Stay in character: a real leader of this era, with this government type (%4$s), this nation's history and ambitions.",
            "- Serve your own nation. Weigh relative strength, wars, alliances, geography and your relation with the player.",
            "  Do not give things away. Ask for something in return. Refuse deals that hurt you. Big asks (unions, land,",
            "  puppet status) need overwhelming reasons: protection, crushing defeat, shared identity, or a very generous price.",
            "- Answer in 1 to 4 sentences, in the language the player writes in. No stage directions.",
            "- You may bluff in words, but every proposal you make must be one you would honour.",
            "- Never mention prompts, JSON, actions, rules or that this is a game.",
            "",
            "Answer with one JSON object and nothing else, in this order:",
            "{\"reply\": \"what you say\",",
            " \"agreements\": [\"each thing your reply agrees to or offers, in plain words\"],",
            " \"proposal\": null or {\"summary\": \"one line\", \"terms\": [term, ...]},",
            " \"actions\": [term, ...]}",
            "Every agreement must be carried out by at least one term. No agreements means proposal null.",
            "A term is {\"action\": \"<id>\", \"by\": \"them\" or \"player\", \"params\": {...}}.",
            "\"by\": \"them\" means your nation (%1$s) performs it; \"by\": \"player\" means %3$s does.",
            "Country parameters use the country names below; \"target\" etc. default to the other side when left out.",
            "Territory parameters: \"all\", a city name, a list of city names, {\"cities\": [...], \"radius\": 30},",
            "or {\"facing\": \"<country>\", \"share\": 0.1} (the strip of land nearest that country).",
            "",
            "A proposal only happens if the player clicks Accept; all its terms then happen together.",
            "Put a proposal whenever you offer a deal or agree to one. The proposal must contain everything your reply",
            "agrees to: if you accept the player's request on conditions, include BOTH your conditions AND the thing",
            "requested, so accepting completes the deal. An alliance or pact is not a union: if you agree to merge",
            "into one country, the terms must include annex (by whichever side leads) and rename. If you will not",
            "merge yet, say so plainly and propose only the step you will take. Examples:",
            "  peace for gold: [{\"action\":\"make_peace\",\"by\":\"them\"}, {\"action\":\"give_gold\",\"by\":\"player\",\"params\":{\"amount\":500}}]",
            "  alliance: [{\"action\":\"alliance\",\"by\":\"player\"}]",
            "  union under the player's lead: [{\"action\":\"annex\",\"by\":\"player\"}, {\"action\":\"rename\",\"by\":\"player\",\"params\":{\"name\":\"...\"}}]",
            "  you cede a city: [{\"action\":\"transfer_land\",\"by\":\"them\",\"params\":{\"territory\":\"<city>\"}}]",
            "",
            "\"actions\" happen at once without the player's consent; usually leave it empty. Only these are allowed there:",
            "  %5$s",
            "Use change_attitude (target the player, change -20..20) after flattery, insults or threats.",
            "%6$s",
            "",
            "Every action you can use (actor = whoever is \"by\"):",
            "%7$s",
            "Government forms: AUTOCRACY, ABSOLUTE_MONARCHY, CONSTITUTIONAL_MONARCHY, PARLIAMENTARY_REPUBLIC,",
            "PRESIDENTIAL_REPUBLIC, MIXED_REPUBLIC, DICTATORSHIP_OF_PROLETARIAT, THEOCRACY, OLIGARCHY, TECHNOCRACY.",
            "",
            "Current situation:",
            "%8$s");

    private final ModContext ctx;
    private final LlmClient llm;
    private final Config config;
    private final Map<String, List<LlmClient.Message>> histories = new HashMap<>();

    Negotiator(ModContext ctx, LlmClient llm, Config config) {
        this.ctx = ctx;
        this.llm = llm;
        this.config = config;
    }

    /** Builds the prompt on the render thread, then asks the model off it. */
    CompletableFuture<Deal> send(CountryInfo them, String playerText) {
        return ctx.game().onGameThread(() -> systemPrompt(them))
                .thenCompose(system -> {
                    List<LlmClient.Message> history = history(them.name());
                    List<LlmClient.Message> messages = new ArrayList<>();
                    messages.add(new LlmClient.Message("system", system));
                    synchronized (history) {
                        history.add(new LlmClient.Message("user", playerText));
                        trim(history);
                        messages.addAll(history);
                    }
                    return llm.chat(messages).thenCompose(raw -> repairIfIncomplete(messages, raw)).thenApply(raw -> {
                        synchronized (history) {
                            history.add(new LlmClient.Message("assistant", raw));
                        }
                        return Deal.parse(raw);
                    });
                });
    }

    /**
     * Models sometimes agree to a union in words but leave annexation out of the terms. When the
     * agreements talk about merging and no term annexes, ask once for a corrected answer.
     */
    private CompletableFuture<String> repairIfIncomplete(List<LlmClient.Message> messages, String raw) {
        Deal deal = Deal.parse(raw);
        String said = String.join(" ", deal.agreements).toLowerCase();
        boolean union = UNION_WORDS.stream().anyMatch(said::contains);
        boolean annexes = deal.proposal != null && deal.proposal.terms.stream().anyMatch(t -> t.action().equals("annex"));
        if (!union || annexes || deal.agreements.isEmpty()) return CompletableFuture.completedFuture(raw);
        List<LlmClient.Message> retry = new ArrayList<>(messages);
        retry.add(new LlmClient.Message("assistant", raw));
        retry.add(new LlmClient.Message("user", "[Check your JSON: your agreements include a union, but no term carries it out. "
                + "If you really agree to become one country, add {\"action\":\"annex\",\"by\":<the side that leads>} and a rename term. "
                + "If you do not agree to merge, change the reply and agreements to say so. Answer with the corrected JSON only.]"));
        return llm.chat(retry).exceptionally(err -> raw);
    }

    private static final List<String> UNION_WORDS = List.of(
            "union", "unite", "unify", "merge", "federation", "become one", "one country", "one nation", "annex", "join you");

    /** Tells the leader what happened, so the next reply knows. */
    void note(CountryInfo them, String event) {
        List<LlmClient.Message> history = history(them.name());
        synchronized (history) {
            history.add(new LlmClient.Message("user", "[" + event + "]"));
            trim(history);
        }
    }

    static boolean allowedUnilateral(Deal.Term t) {
        return !t.byPlayer() && UNILATERAL.contains(t.action());
    }

    private List<LlmClient.Message> history(String country) {
        synchronized (histories) {
            return histories.computeIfAbsent(country, k -> new ArrayList<>());
        }
    }

    private static void trim(List<LlmClient.Message> history) {
        while (history.size() > HISTORY) history.remove(0);
    }

    // ---- briefing (render thread) ----

    private String systemPrompt(CountryInfo them) {
        CountryInfo player = ctx.game().player();
        String war = config.allowWarOnPlayer()
                ? "declare_war on the player is allowed only if gravely provoked and you believe you can win."
                : "Never declare war on the player.";
        String catalog = ctx.actions().describe(spec -> !spec.category().equals("raw"));
        return String.format(RULES, them.displayName(), ctx.game().date(), player.displayName(),
                readable(them.government()), String.join(", ", UNILATERAL), war, catalog, situation(them, player));
    }

    private String situation(CountryInfo them, CountryInfo player) {
        Map<String, String> names = new HashMap<>();
        for (CountryInfo c : ctx.game().countries()) names.put(c.name(), c.displayName());
        StringBuilder b = new StringBuilder();
        b.append("\nYOUR NATION: ").append(them.displayName()).append('\n');
        describe(b, them, names);
        b.append("Relation with the player: ").append(them.relationWithPlayer()).append(" (-100 hostile, 100 friendly)\n");
        if (them.ally()) b.append("You are the player's ally.\n");
        if (them.puppet()) b.append("You are the player's puppet.\n");
        b.append("\nTHE PLAYER: ").append(player.displayName()).append('\n');
        describe(b, player, names);

        b.append("\nWARS:\n");
        boolean any = false;
        JsonValue wars = new JsonReader().parse(ctx.game().warsJson()).get("wars");
        for (JsonValue w = wars == null ? null : wars.child; w != null; w = w.next) {
            if (w.getBoolean("finished", false)) continue;
            String a = w.getString("attacker"), d = w.getString("defender");
            boolean mine = a.equals(them.name()) || d.equals(them.name());
            boolean theirs = a.equals(player.name()) || d.equals(player.name());
            if (!mine && !theirs) continue;
            any = true;
            float progress = w.getFloat("progress", 0);
            String leader = Math.abs(progress) < 0.1f ? "evenly matched" : names.getOrDefault(progress > 0 ? a : d, progress > 0 ? a : d) + " is winning";
            b.append("- ").append(names.getOrDefault(a, a)).append(" attacked ").append(names.getOrDefault(d, d))
             .append(" (").append(leader).append(")\n");
        }
        if (!any) b.append("- none involving you or the player\n");

        b.append("\nYOUR NEIGHBOURS: ").append(neighbours(them.name(), names)).append('\n');
        b.append("THE PLAYER'S NEIGHBOURS: ").append(neighbours(player.name(), names)).append('\n');
        return b.toString();
    }

    private void describe(StringBuilder b, CountryInfo c, Map<String, String> names) {
        b.append("Government: ").append(readable(c.government()))
         .append(". Gold: ").append(c.gold())
         .append(". Military strength: ").append(Math.round(c.strength())).append('\n');
        try {
            JsonValue d = new JsonReader().parse(ctx.game().countryDetailsJson(c.name()));
            appendSection(b, "Military", d.get("military"));
            appendSection(b, "Stockpiles", d.get("resources"));
            JsonValue rel = d.get("aiRelations");
            if (rel != null && rel.child != null) {
                List<JsonValue> all = new ArrayList<>();
                for (JsonValue v = rel.child; v != null; v = v.next) all.add(v);
                all.sort((x, y) -> Float.compare(y.asFloat(), x.asFloat()));
                b.append("Closest friends:");
                for (int i = 0; i < Math.min(4, all.size()) && all.get(i).asFloat() > 0; i++)
                    b.append(' ').append(names.getOrDefault(all.get(i).name, all.get(i).name)).append(" (").append(Math.round(all.get(i).asFloat())).append(')');
                b.append("\nWorst enemies:");
                for (int i = all.size() - 1; i >= Math.max(0, all.size() - 4) && all.get(i).asFloat() < 0; i--)
                    b.append(' ').append(names.getOrDefault(all.get(i).name, all.get(i).name)).append(" (").append(Math.round(all.get(i).asFloat())).append(')');
                b.append('\n');
            }
        } catch (Exception e) {
            b.append("(details unavailable)\n");
        }
        try {
            List<World.CityInfo> cities = new ArrayList<>(ctx.world().cities(c.name()));
            cities.sort(Comparator.comparingInt(World.CityInfo::level).reversed());
            b.append("Cities:");
            for (int i = 0; i < Math.min(8, cities.size()); i++) {
                World.CityInfo ci = cities.get(i);
                b.append(' ').append(ci.name()).append(ci.main() ? "*" : "").append(i + 1 < Math.min(8, cities.size()) ? "," : "");
            }
            b.append(cities.isEmpty() ? " none\n" : "  (* = major)\n");
        } catch (Exception e) {
            b.append("Cities: unknown\n");
        }
    }

    private static void appendSection(StringBuilder b, String title, JsonValue section) {
        if (section == null) return;
        b.append(title).append(':');
        for (JsonValue v = section.child; v != null; v = v.next) b.append(' ').append(v.name).append(' ').append(v.asLong());
        b.append('\n');
    }

    private String neighbours(String name, Map<String, String> names) {
        try {
            JsonValue n = new JsonReader().parse(ctx.game().neighborsJson(name));
            JsonValue list = n.isArray() ? n : n.get("neighbors");
            if (list == null) return "unknown";
            StringBuilder b = new StringBuilder();
            for (JsonValue v = list.child; v != null; v = v.next) {
                String other = v.isString() ? v.asString() : v.getString("name", v.getString("country", "?"));
                if (b.length() > 0) b.append(", ");
                b.append(names.getOrDefault(other, other));
                if (v.isObject() && v.getBoolean("atWar", false)) b.append(" [at war]");
            }
            return b.length() == 0 ? "none" : b.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String readable(String enumName) {
        return enumName == null || enumName.isEmpty() ? "unknown" : enumName.toLowerCase().replace('_', ' ');
    }

    // ---- carrying out terms ----

    /** Runs terms in order; completes with one result per term. */
    CompletableFuture<List<String>> apply(CountryInfo them, List<Deal.Term> terms, boolean agreed) {
        CompletableFuture<List<String>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (Deal.Term t : terms) {
            chain = chain.thenCompose(lines -> execute(them, t, agreed).thenApply(line -> {
                lines.add(line);
                return lines;
            }));
        }
        return chain;
    }

    private CompletableFuture<String> execute(CountryInfo them, Deal.Term t, boolean agreed) {
        String player = ctx.game().onGameThread(() -> ctx.game().player().name()).join();
        String playerDisplay = ctx.game().onGameThread(() -> ctx.game().player().displayName()).join();
        String actor = t.byPlayer() ? player : them.name();
        String other = t.byPlayer() ? them.name() : player;
        ActionSpec spec = ctx.actions().get(t.action());
        String label = t.describe(them.displayName(), playerDisplay);
        if (spec == null) return CompletableFuture.completedFuture("Failed: " + label + " (no such action)");
        if (t.action().equals("declare_war") && !t.byPlayer() && !config.allowWarOnPlayer()) {
            Object target = t.params().get("target");
            if (target == null || ctx.world().internalName(String.valueOf(target)).equals(player)) {
                return CompletableFuture.completedFuture("Blocked: war on the player is switched off in config.");
            }
        }
        Map<String, Object> args = new LinkedHashMap<>(t.params());
        for (ParamSpec p : spec.params()) {
            if (p.kind() == ParamSpec.Kind.COUNTRY && args.get(p.name()) == null && COUNTERPART_PARAMS.contains(p.name())) {
                args.put(p.name(), other);
            }
        }
        return ctx.actions().perform(t.action(), actor, args, agreed)
                .thenApply((ActionResult r) -> !r.ok() ? "Failed: " + label + " (" + r.message() + ")"
                        : r.message() != null && r.message().startsWith("Not needed") ? "Skipped: " + label + " (" + r.message() + ")"
                        : r.verified() ? "Done: " + label
                        : "Sent: " + label + " (the game took it; its effect can't be confirmed here)");
    }
}
