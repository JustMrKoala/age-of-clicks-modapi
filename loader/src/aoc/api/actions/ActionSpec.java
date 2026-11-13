package aoc.api.actions;

import java.util.List;

/**
 * A named thing a country can do. Built-in specs wrap the game's own player actions
 * ({@code ru.ius.david.cgs.game.actions.*}) or direct world changes; mods can register more
 * through {@link Actions#register}.
 *
 * @param id          stable snake_case id, e.g. {@code declare_war}
 * @param category    diplomacy, war, trade, territory, economy, politics, world
 * @param description one sentence for players and AI prompts; "the actor" is the country doing it
 * @param params      parameters, in the order they are best explained
 * @param executor    runs on the render thread
 */
public record ActionSpec(String id, String category, String description, List<ParamSpec> params, Executor executor) {
    @FunctionalInterface
    public interface Executor {
        ActionResult run(ActionCall call) throws Exception;
    }

    /** One line per action, used in AI prompts and the in-game catalog. */
    public String signature() {
        StringBuilder b = new StringBuilder(id).append('(');
        for (int i = 0; i < params.size(); i++) {
            ParamSpec p = params.get(i);
            if (i > 0) b.append(", ");
            b.append(p.name()).append(':').append(p.kind().name().toLowerCase());
            if (!p.required()) b.append('=').append(p.defaultValue());
        }
        return b.append(')').toString();
    }
}
