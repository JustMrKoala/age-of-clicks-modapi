package aoc.diplomacy;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A leader's parsed answer: what they say, what they propose, what they do right away. */
final class Deal {
    /** One clause: a catalog action, who performs it ("them" or "player"), and its arguments. */
    record Term(String action, String by, Map<String, Object> params) {
        boolean byPlayer() { return "player".equals(by); }

        String describe(String them, String player) {
            StringBuilder b = new StringBuilder(byPlayer() ? player : them).append(": ").append(action.replace('_', ' '));
            if (!params.isEmpty()) {
                b.append(" (");
                boolean first = true;
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    if (!first) b.append(", ");
                    first = false;
                    b.append(e.getKey().replace('_', ' ')).append(' ').append(readable(e.getValue()));
                }
                b.append(')');
            }
            return b.toString();
        }
    }

    static final class Proposal {
        final String summary;
        final List<Term> terms;

        Proposal(String summary, List<Term> terms) {
            this.summary = summary;
            this.terms = terms;
        }
    }

    final String reply;
    /** What the leader said they agree to, in plain words; each should be backed by a term. */
    final List<String> agreements;
    final Proposal proposal;
    final List<Term> actions;

    private Deal(String reply, List<String> agreements, Proposal proposal, List<Term> actions) {
        this.reply = reply;
        this.agreements = agreements;
        this.proposal = proposal;
        this.actions = actions;
    }

    /** Parses the model output; tolerates code fences and prose around the JSON. */
    static Deal parse(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return new Deal(s, List.of(), null, List.of());
        JsonValue json;
        try {
            json = new JsonReader().parse(s.substring(start, end + 1));
        } catch (Exception e) {
            return new Deal(s, List.of(), null, List.of());
        }
        String reply = json.getString("reply", "").trim();
        Proposal proposal = null;
        JsonValue p = json.get("proposal");
        if (p != null && p.isObject()) {
            List<Term> terms = terms(p.get("terms"));
            if (!terms.isEmpty()) proposal = new Proposal(p.getString("summary", "Proposal"), terms);
        }
        List<String> agreements = new ArrayList<>();
        JsonValue ag = json.get("agreements");
        if (ag != null && ag.isArray()) for (JsonValue v = ag.child; v != null; v = v.next) if (v.isString()) agreements.add(v.asString());
        return new Deal(reply, agreements, proposal, terms(json.get("actions")));
    }

    private static List<Term> terms(JsonValue array) {
        List<Term> out = new ArrayList<>();
        if (array == null || !array.isArray()) return out;
        for (JsonValue t = array.child; t != null; t = t.next) {
            if (!t.isObject()) continue;
            String action = t.getString("action", t.getString("type", "")).trim().toLowerCase();
            if (action.isEmpty()) continue;
            String by = t.getString("by", "them").trim().toLowerCase();
            Map<String, Object> params = new LinkedHashMap<>();
            JsonValue ps = t.get("params");
            if (ps != null && ps.isObject()) {
                for (JsonValue v = ps.child; v != null; v = v.next) params.put(v.name, toJava(v));
            }
            out.add(new Term(action, by.equals("player") ? "player" : "them", params));
        }
        return out;
    }

    private static Object toJava(JsonValue v) {
        if (v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isLong()) return v.asLong();
        if (v.isDouble()) return v.asDouble();
        if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonValue c = v.child; c != null; c = c.next) list.add(toJava(c));
            return list;
        }
        if (v.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (JsonValue c = v.child; c != null; c = c.next) map.put(c.name, toJava(c));
            return map;
        }
        return v.asString();
    }

    private static String readable(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (b.length() > 0) b.append(", ");
                b.append(e.getKey()).append(' ').append(readable(e.getValue()));
            }
            return b.toString();
        }
        if (v instanceof List<?> l) return String.join(", ", l.stream().map(Deal::readable).toList());
        if (v instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(v);
    }
}
