package aoc.api.actions;

/**
 * Outcome of an action.
 *
 * @param ok       false when the game refused or the call failed
 * @param message  what happened, for players
 * @param verified true when the effect was confirmed in the game state afterwards; game
 *                 actions without a check report {@code ok} with {@code verified=false}
 */
public record ActionResult(boolean ok, String message, boolean verified) {
    public static ActionResult ok(String message) { return new ActionResult(true, message, true); }

    /** Accepted by the game without errors, but not confirmed against the game state. */
    public static ActionResult sent(String message) { return new ActionResult(true, message, false); }

    public static ActionResult fail(String message) { return new ActionResult(false, message, false); }
}
