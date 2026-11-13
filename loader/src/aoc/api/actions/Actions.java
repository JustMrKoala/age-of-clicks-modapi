package aoc.api.actions;

import aoc.api.GameApi;
import aoc.api.actions.ParamSpec.Kind;
import aoc.api.internal.GameLog;
import aoc.api.internal.Log;
import aoc.api.world.World;
import ru.ius.david.cgs.game.BudgetLedger;
import ru.ius.david.cgs.game.CleanRunState;
import ru.ius.david.cgs.game.CountryState;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.game.InputAction;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static aoc.api.actions.ParamSpec.of;
import static aoc.api.actions.ParamSpec.opt;

/**
 * Everything a country can do, as a catalog of named actions.
 *
 * <p>Built-in actions wrap the game's own player actions, so the game's rules, costs and
 * AI reactions apply, plus world actions (annexation, territory transfer, government, events)
 * that the game normally only does through peace deals and scenario events.
 * {@link #performGameAction} reaches any of the game's action classes directly by name.
 *
 * <pre>{@code
 * ctx.actions().perform("alliance", "Germany", Map.of("target", "Italy"), false)
 *     .thenAccept(r -> ctx.log(r.message()));
 * }</pre>
 */
public final class Actions {
    /** Every player action class in {@code ru.ius.david.cgs.game.actions}, for {@link #performGameAction}. */
    public static final List<String> GAME_ACTION_CLASSES = List.of(
            "AllianceInviteAction", "AllianceMembershipAction", "AllianceRulesAction", "AllyAction",
            "AssignBuildingToCityAction", "BuyAutoclickerAction", "CallAlliesAction", "CancelJustificationAction",
            "CancelTradeContractAction", "CedeCapitulationTermsAction", "CedeLandAction", "ChangeSpeedAction",
            "ChooseTierOptionAction", "ClickAttackAction", "ClickBoostProductionAction", "ClickBuildAirDefenseAction",
            "ClickBuildSiloAction", "ClickDiplomacyAction", "ClickFortifyAction", "DeclareWarAction",
            "DeclareWarOn3rdAction", "DemandGoldAction", "DiplomacyAction", "DiplomacyCampaignAction",
            "DisbandUnitAction", "EconomicUnionRulesAction", "FinanceAction", "FormNationAction", "FormUnitAction",
            "FoundCityAction", "GovernmentAction", "IntegrateVassalAction", "InternalPoliticsAction",
            "JoinAllyWarAction", "JustifyWarAction", "KickAllyAction", "LaunchLaserAction", "LaunchNukeAction",
            "LeaveAllianceAction", "LendLeaseAction", "ManualClickAction", "NavalInvasionAction",
            "NonAggressionPactAction", "OffensivePlanAction", "PeaceAction", "PinProductionAllocationAction",
            "ProposeLoanAction", "ProposeTerritoryLeaseAction", "ProposeTerritoryPurchaseAction",
            "ProposeTradeContractAction", "ProxyWarAction", "PullOutOfAllianceAction", "PuppetAction",
            "RefuseLeaseReturnAction", "RegimeChangeAction", "RenameCityAction", "RepayMarketLoanAction",
            "RequestMarketAccessAction", "RequestPeaceAction", "RequestTerritoryLeaseAction", "ResearchTechAction",
            "RevokeMarketAccessAction", "SelectCountryAction", "SendGiftAction", "SendGoldAction",
            "SendResourcesAction", "SetAllianceDoctrineAction", "SetArmyDoctrineAction", "SetArmyPlanAction",
            "SetLandingOpAction", "SetProductionAllocationAction", "SetProductionDecreeAction",
            "SetProductionFocusAction", "SetResearchFocusAction", "SetResourceStockpileFloorAction",
            "SetSocialBudgetPartAction", "SetTradeAutomationAction", "SetTradeContractRenewAction",
            "SetTradeOrderAction", "SetWarEconomyAction", "SignConferencePeaceAction", "SponsorUprisingAction",
            "StartAssimilationAction", "StartColonyAction", "StartIntegrationAction", "TakeMarketLoanAction",
            "ToggleProductionPauseAction", "TransferGoldReserveAction", "TributeAction", "UltimatumAction",
            "UnitOrderAction", "UpgradeAutoclickerAction", "UpgradeBuildingAction", "UsurpLeadershipAction",
            "VassalLandAction");

    private static final String PACKAGE = "ru.ius.david.cgs.game.actions.";

    private final GameApi game;
    private final World world;
    private final Map<String, ActionSpec> catalog = new LinkedHashMap<>();

    public Actions(GameApi game, World world) {
        this.game = game;
        this.world = world;
        registerBuiltins();
    }

    // ---- catalog ----

    /** Adds or replaces an action; mods use this to publish their own. */
    public synchronized void register(ActionSpec spec) { catalog.put(spec.id(), spec); }

    public synchronized ActionSpec get(String id) { return catalog.get(id); }

    public synchronized List<ActionSpec> all() { return Collections.unmodifiableList(new ArrayList<>(catalog.values())); }

    public List<ActionSpec> where(Predicate<ActionSpec> filter) {
        List<ActionSpec> out = new ArrayList<>();
        for (ActionSpec s : all()) if (filter.test(s)) out.add(s);
        return out;
    }

    /** The catalog as text, one action per line, grouped by category. For AI prompts and docs. */
    public String describe(Predicate<ActionSpec> filter) {
        StringBuilder b = new StringBuilder();
        String category = null;
        List<ActionSpec> specs = where(filter);
        specs.sort((x, y) -> x.category().compareTo(y.category()));
        for (ActionSpec s : specs) {
            if (!s.category().equals(category)) {
                category = s.category();
                b.append(category.toUpperCase()).append(":\n");
            }
            b.append("  ").append(s.signature()).append("  ").append(s.description());
            for (ParamSpec p : s.params()) {
                if (p.description() != null && !p.description().isEmpty()) {
                    b.append(" [").append(p.name()).append(": ").append(p.description()).append(']');
                }
            }
            b.append('\n');
        }
        return b.toString();
    }

    // ---- running ----

    /**
     * Runs a catalog action for {@code actor}. Completes on the render thread's next frame with
     * the game's verdict; never completes exceptionally (failures become {@code ok=false}).
     *
     * @param approved true when the affected country already agreed, e.g. an accepted deal
     */
    public CompletableFuture<ActionResult> perform(String id, String actor, Map<String, Object> args, boolean approved) {
        return game.onGameThread(() -> performNow(id, actor, args, approved));
    }

    /** Same as {@link #perform}, for callers already on the render thread. */
    public ActionResult performNow(String id, String actor, Map<String, Object> args, boolean approved) {
        ActionSpec spec = get(id);
        if (spec == null) return ActionResult.fail("Unknown action " + id);
        CountryState actorState = world.country(actor);
        if (actorState == null) return ActionResult.fail("Unknown country " + actor);
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        for (ParamSpec p : spec.params()) {
            if (p.required() && safeArgs.get(p.name()) == null) {
                return ActionResult.fail(id + " needs '" + p.name() + "'");
            }
        }
        ActionResult result;
        if (multiplayer()) {
            result = ActionResult.fail("Code mods are switched off in multiplayer games");
        } else {
            try {
                // World actions and pre-approved deals go beyond what a player can do by clicking,
                // so they mark the run the same way the game's own external tools do.
                if (approved || spec.category().equals("world")) CleanRunState.breach("cleanrun_r_external");
                result = spec.executor().run(new ActionCall(spec, world.mapKey(actorState), safeArgs, approved, world));
            } catch (Throwable t) {
                result = ActionResult.fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        }
        Log.info("action " + id + " by " + actorState.getName() + " " + safeArgs + (approved ? " (approved)" : "")
                + " -> " + (result.ok() ? (result.verified() ? "done" : "sent") : "FAILED") + ": " + result.message());
        return result;
    }

    /** Mods are off in multiplayer (fair play); see {@code ModManager.multiplayerActive()}. */
    private static boolean multiplayer() {
        return aoc.api.internal.ModManager.multiplayerActive();
    }

    /**
     * Parses JSON arguments ({@code {"target": "Italy", "amount": 100}}) into the map
     * {@link #perform} takes; nested objects and lists are kept.
     */
    public static Map<String, Object> argsFromJson(String json) {
        String s = json == null ? "" : json.trim();
        if (s.isEmpty()) return new HashMap<>();
        Object v = toJava(new com.badlogic.gdx.utils.JsonReader().parse(s));
        if (!(v instanceof Map)) throw new IllegalArgumentException("Arguments must be a JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    private static Object toJava(com.badlogic.gdx.utils.JsonValue v) {
        if (v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isLong()) return v.asLong();
        if (v.isDouble()) return v.asDouble();
        if (v.isArray()) {
            List<Object> out = new ArrayList<>();
            for (com.badlogic.gdx.utils.JsonValue c = v.child; c != null; c = c.next) out.add(toJava(c));
            return out;
        }
        if (v.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (com.badlogic.gdx.utils.JsonValue c = v.child; c != null; c = c.next) out.put(c.name, toJava(c));
            return out;
        }
        return v.asString();
    }

    /**
     * Runs any game action class by simple name with raw field values. Country fields
     * ({@code ...Name}, {@code ...ColorKey}) take names; pixel sets take territory selections
     * of the actor's land (or {@code {"owner": "...", ...}}).
     */
    public CompletableFuture<ActionResult> performGameAction(String className, String actor, Map<String, Object> fields, boolean approved) {
        return game.onGameThread(() -> {
            try {
                if (multiplayer()) return ActionResult.fail("Code mods are switched off in multiplayer games");
                CountryState actorState = world.country(actor);
                if (actorState == null) return ActionResult.fail("Unknown country " + actor);
                if (approved) CleanRunState.breach("cleanrun_r_external");
                InputAction a = newAction(className, actorState, approved);
                ActionSpec spec = new ActionSpec(className, "raw", "", List.of(), c -> null);
                ActionCall call = new ActionCall(spec, actorState.getName(), fields, approved, world);
                for (Map.Entry<String, Object> e : fields.entrySet()) setRawField(a, e.getKey(), e.getValue(), call);
                return execute(a);
            } catch (Throwable t) {
                return ActionResult.fail(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        });
    }

    // ---- game action plumbing ----

    @FunctionalInterface
    private interface Binder {
        void bind(ActionCall c, Fields f) throws Exception;
    }

    /** Reflective setter for an action's public fields. */
    private static final class Fields {
        final InputAction action;

        Fields(InputAction action) { this.action = action; }

        Fields set(String field, Object value) throws ReflectiveOperationException {
            Field f = findField(action.getClass(), field);
            f.setAccessible(true);
            f.set(action, coerce(value, f.getType()));
            return this;
        }
    }

    /**
     * Confirms an action against the game state. Many game actions refuse silently (they only
     * log), so a check is what turns "sent" into "done".
     */
    private interface Check {
        Object before(ActionCall c) throws Exception;

        /** Null when the effect is visible in the game, otherwise why it is not. */
        String after(ActionCall c, Object before) throws Exception;
    }

    private ActionSpec game(String id, String category, String description, String className,
                            List<ParamSpec> params, Binder binder) {
        return game(id, category, description, className, params, binder, null);
    }

    private ActionSpec game(String id, String category, String description, String className,
                            List<ParamSpec> params, Binder binder, Check check) {
        return new ActionSpec(id, category, description, params, call -> {
            CountryState actor = world.country(call.actor());
            InputAction a = newAction(className, actor, call.approved());
            try {
                binder.bind(call, new Fields(a));
            } catch (Skip skip) {
                return ActionResult.ok(skip.getMessage());
            }
            return run(a, call, check);
        });
    }

    private static ActionResult run(InputAction a, ActionCall call, Check check) throws Exception {
        Object before = check == null ? null : check.before(call);
        GameLog.begin();
        ActionResult r;
        List<String> log;
        try {
            r = execute(a);
        } finally {
            log = GameLog.end();
        }
        String refusal = GameLog.refusal(log);
        if (!r.ok()) return refusal == null ? r : ActionResult.fail(refusal);
        if (check == null) return refusal == null ? r : ActionResult.fail(refusal);
        String problem = check.after(call, before);
        if (problem == null) return ActionResult.ok(r.message());
        return ActionResult.fail(refusal != null ? problem + " - game says: " + refusal : problem);
    }

    private InputAction newAction(String className, CountryState actor, boolean approved) throws ReflectiveOperationException {
        Class<?> cls = Class.forName(PACKAGE + className);
        InputAction a = (InputAction) cls.getDeclaredConstructor().newInstance();
        a.sourceCountryName = world.mapKey(actor);
        a.sourceColorKey = world.colorKey(actor.getName());
        a.packageApproved = approved;
        return a;
    }

    /** Runs a game action and reads its own result fields, if it has any. */
    private static ActionResult execute(InputAction a) {
        GameManager gm = GameManager.getInstance();
        a.execute(gm);
        Object success = readField(a, "success");
        Object message = readField(a, "resultMessage");
        String text = message instanceof String s && !s.isEmpty() ? s : a.describe();
        if (success instanceof Boolean ok) return new ActionResult(ok, text, ok);
        return ActionResult.sent(text);
    }

    private static Object readField(Object o, String name) {
        try {
            Field f = findField(o.getClass(), name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep looking in the superclass
            }
        }
        throw new NoSuchFieldException(cls.getSimpleName() + " has no field " + name);
    }

    private static Object coerce(Object v, Class<?> type) {
        if (v == null || type.isInstance(v)) return v;
        if (type == int.class || type == Integer.class) return (int) toLong(v);
        if (type == long.class || type == Long.class) return toLong(v);
        if (type == float.class || type == Float.class) return (float) toDouble(v);
        if (type == double.class || type == Double.class) return toDouble(v);
        if (type == boolean.class || type == Boolean.class) return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v).trim());
        if (type == String.class) return String.valueOf(v);
        if (type.isEnum()) return enumConstant(type, String.valueOf(v));
        if (type == int[].class && v instanceof Collection<?> c) {
            int[] out = new int[c.size()];
            int i = 0;
            for (Object o : c) out[i++] = (int) toLong(o);
            return out;
        }
        return v;
    }

    /** Enum constant from "TANK_FACTORY", "tank factory" or "tankFactory". */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        String key = name.trim().replaceAll("([a-z])([A-Z])", "$1_$2").replace(' ', '_').replace('-', '_').toUpperCase();
        return Enum.valueOf((Class<? extends Enum>) type, key);
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? Math.round(n.doubleValue()) : Math.round(Double.parseDouble(String.valueOf(v).trim()));
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
    }

    @SuppressWarnings("unchecked")
    private void setRawField(InputAction a, String name, Object value, ActionCall call) throws ReflectiveOperationException {
        Field f = findField(a.getClass(), name);
        if (Modifier.isStatic(f.getModifiers())) throw new IllegalArgumentException(name + " is not settable");
        f.setAccessible(true);
        Class<?> t = f.getType();
        Object v = value;
        String lower = name.toLowerCase();
        if ((t == int.class) && lower.endsWith("colorkey") && !(value instanceof Number)) {
            v = world.colorKey(String.valueOf(value));
        } else if (t == String.class && lower.endsWith("name") && !lower.equals("name") && !lower.contains("unit")
                && !lower.contains("alliance") && world.country(String.valueOf(value)) != null) {
            v = world.internalName(String.valueOf(value));
        } else if (Set.class.isAssignableFrom(t)) {
            String owner = call.actor();
            if (value instanceof Map<?, ?> m && m.get("owner") != null) owner = world.internalName(String.valueOf(m.get("owner")));
            ActionCall sel = new ActionCall(call.spec(), call.actor(), Map.of("t", value), call.approved(), world);
            v = sel.territory("t", owner);
        }
        f.set(a, coerce(v, t));
    }

    // ---- built-in catalog ----

    private static List<ParamSpec> p(ParamSpec... params) { return List.of(params); }

    private void registerBuiltins() {
        final String target = "";
        // Diplomacy
        register(new ActionSpec("alliance", "diplomacy", "The actor forms a military alliance with the target.",
                p(of("target", Kind.COUNTRY, target), opt("bribe", Kind.LONG, "gold offered to sweeten it", 0)),
                c -> {
                    boolean bribing = !c.approved() && c.number("bribe") > 0;
                    InputAction a = newAction(bribing ? "AllyAction" : "AllianceInviteAction", world.country(c.actor()), c.approved());
                    Fields f = new Fields(a).set("targetCountryName", world.mapKey(world.country(c.country("target"))));
                    if (bribing) f.set("goldBribe", c.number("bribe")).set("customAllianceName", "");
                    else f.set("kind", 0).set("customName", "").set("preApproved", c.approved());
                    return run(a, c, allied(0));
                }));
        register(game("alliance_invite", "diplomacy", "The actor invites the target into its alliance bloc.", "AllianceInviteAction",
                p(of("target", Kind.COUNTRY, target), opt("economic", Kind.BOOL, "economic union instead of military alliance", false)),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("kind", c.flag("economic") ? 1 : 0)
                        .set("customName", "").set("preApproved", c.approved()),
                after(c -> allied(c.flag("economic") ? 1 : 0).after(c, null))));
        register(game("leave_alliance", "diplomacy", "The actor leaves its alliance.", "LeaveAllianceAction", p(), (c, f) -> {}));
        register(game("kick_ally", "diplomacy", "The actor expels the target from its alliance.", "KickAllyAction",
                p(of("target", Kind.COUNTRY, target)), (c, f) -> f.set("targetCountryName", c.country("target"))));
        register(game("pull_out_of_alliance", "diplomacy", "The actor pulls the target out of its current alliance.", "PullOutOfAllianceAction",
                p(of("target", Kind.COUNTRY, target)), (c, f) -> f.set("targetCountryName", c.country("target"))));
        register(game("non_aggression_pact", "diplomacy", "The actor signs (or breaks) a non-aggression pact with the target.", "NonAggressionPactAction",
                p(of("target", Kind.COUNTRY, target), opt("breaking", Kind.BOOL, "true to break an existing pact", false),
                        opt("bribe", Kind.LONG, "gold offered", 0)),
                (c, f) -> {
                    CountryState a = world.country(c.actor()), t = world.country(c.country("target"));
                    if (!c.flag("breaking") && a.isInSameAlliance(t.getName(), world.game().getWorldCountries())) {
                        throw new Skip("Not needed: they are already in the same alliance");
                    }
                    f.set("targetCountryName", c.country("target")).set("breaking", c.flag("breaking")).set("goldBribe", c.number("bribe"));
                },
                after(c -> {
                    CountryState a = world.country(c.actor()), t = world.country(c.country("target"));
                    boolean pact = a.nonAggressionPacts != null && (a.nonAggressionPacts.containsKey(t.getName())
                            || a.nonAggressionPacts.containsKey(world.mapKey(t)));
                    return pact != c.flag("breaking") ? null : "The game refused the pact change";
                })));
        register(game("improve_relations", "diplomacy", "The actor spends diplomacy to improve (or worsen) relations with the target.", "DiplomacyAction",
                p(of("target", Kind.COUNTRY, target), opt("cost", Kind.LONG, "diplomacy points to spend", 10), opt("worsen", Kind.BOOL, "", false)),
                (c, f) -> f.set("type", c.flag("worsen") ? "WORSEN_RELATIONS" : "IMPROVE_RELATIONS")
                        .set("targetCountryName", c.country("target")).set("diplomacyCost", c.number("cost"))));
        register(game("diplomatic_campaign", "diplomacy", "The actor starts or stops a long campaign aimed at the target.", "DiplomacyCampaignAction",
                p(of("target", Kind.COUNTRY, target), of("kind", Kind.TEXT, "improve, worsen, influence or integration"),
                        opt("start", Kind.BOOL, "false stops it", true)),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("kind", campaignKind(c.text("kind"))).set("start", c.flag("start"))));
        register(game("puppet", "diplomacy", "The actor makes the target its puppet.", "PuppetAction",
                p(of("target", Kind.COUNTRY, target)), (c, f) -> f.set("targetCountryName", c.country("target")),
                after(c -> c.actor().equals(world.country(c.country("target")).puppetMasterName)
                        || world.mapKey(world.country(c.actor())).equals(world.country(c.country("target")).puppetMasterName)
                        ? null : "The game refused: " + c.country("target") + " did not become a puppet")));
        register(game("integrate_vassal", "diplomacy", "The actor works to absorb its vassal.", "IntegrateVassalAction",
                p(of("target", Kind.COUNTRY, "the vassal"), opt("finalize", Kind.BOOL, "true to complete the integration", false)),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("mode", c.flag("finalize") ? 1 : 0)));
        register(game("tribute", "diplomacy", "The actor sets the tribute its vassal pays.", "TributeAction",
                p(of("target", Kind.COUNTRY, "the vassal"), opt("gold_percent", Kind.INT, "", 10), opt("resource_percent", Kind.INT, "", 10)),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("goldTributePercent", c.integer("gold_percent"))
                        .set("resourceTributePercent", c.integer("resource_percent"))));
        register(game("demand_gold", "diplomacy", "The actor demands gold from the target.", "DemandGoldAction",
                p(of("target", Kind.COUNTRY, target), of("amount", Kind.LONG, "")),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("amount", c.number("amount")),
                new Check() {
                    public Object before(ActionCall c) { return world.country(c.country("target")).getGold(); }

                    public String after(ActionCall c, Object b) {
                        return world.country(c.country("target")).getGold() > (Long) b ? null : "The gold was not transferred (not enough reserves?)";
                    }
                }));
        register(game("ultimatum", "diplomacy", "The actor issues an ultimatum to the target.", "UltimatumAction",
                p(of("target", Kind.COUNTRY, target), opt("annex", Kind.BOOL, "demand annexation", false),
                        opt("puppet", Kind.BOOL, "demand puppet status", false), opt("leave_alliance", Kind.BOOL, "", false),
                        opt("gold", Kind.BOOL, "demand gold", false), opt("army_threat_percent", Kind.INT, "", 50),
                        opt("territory", Kind.TERRITORY, "land of the target to demand", "")),
                (c, f) -> {
                    f.set("targetCountryName", c.country("target")).set("demandAnnex", c.flag("annex")).set("demandPuppet", c.flag("puppet"))
                            .set("demandLeaveAlliance", c.flag("leave_alliance")).set("demandGold", c.flag("gold"))
                            .set("armyThreatPercent", c.integer("army_threat_percent")).set("acceptedByAI", c.approved());
                    if (!c.text("territory").isEmpty()) f.set("demandedPixels", c.territory("territory", c.country("target")));
                }));
        register(game("market_access", "trade", "The actor asks the target for access to its market.", "RequestMarketAccessAction",
                p(of("target", Kind.COUNTRY, target)), (c, f) -> f.set("targetCountryName", c.country("target"))));
        register(game("revoke_market_access", "trade", "The actor revokes the target's access to its market.", "RevokeMarketAccessAction",
                p(of("target", Kind.COUNTRY, target)), (c, f) -> f.set("beneficiaryCountryName", c.country("target"))));
        // War
        register(game("declare_war", "war", "The actor declares war on the target.", "DeclareWarAction",
                p(of("target", Kind.COUNTRY, target)),
                (c, f) -> f.set("playerColorKey", world.colorKey(c.actor())).set("targetColorKey", c.countryKey("target")),
                after(c -> world.atWar(c.actor(), c.country("target")) ? null
                        : "The game refused the war (a border, a pact or a peace cooldown may block it)")));
        register(game("justify_war", "war", "The actor starts fabricating a reason for war on the target.", "JustifyWarAction",
                p(of("target", Kind.COUNTRY, target), opt("reason", Kind.TEXT, "war goal", "claims")),
                (c, f) -> f.set("playerColorKey", world.colorKey(c.actor())).set("targetColorKey", c.countryKey("target"))
                        .set("warGoalReason", c.text("reason")).set("claimCells", 0).set("claimShare", 0f)));
        register(game("call_allies", "war", "The actor calls its allies into the war against the target.", "CallAlliesAction",
                p(of("target", Kind.COUNTRY, "the enemy")), (c, f) -> f.set("targetCountryName", c.country("target"))));
        register(game("join_ally_war", "war", "The actor joins its ally's war against an enemy.", "JoinAllyWarAction",
                p(of("ally", Kind.COUNTRY, ""), of("enemy", Kind.COUNTRY, "")),
                (c, f) -> f.set("allyName", c.country("ally")).set("enemyName", c.country("enemy"))));
        register(game("joint_war", "war", "The actor and a partner declare war on a third country together.", "DeclareWarOn3rdAction",
                p(of("partner", Kind.COUNTRY, ""), of("target", Kind.COUNTRY, "")),
                (c, f) -> f.set("partnerCountryName", c.country("partner")).set("targetCountryName", c.country("target"))));
        register(game("request_peace", "war", "The actor asks the target for peace (the target decides).", "RequestPeaceAction",
                p(of("target", Kind.COUNTRY, "the enemy")),
                (c, f) -> f.set("attackerColorKey", world.colorKey(c.actor())).set("targetColorKey", c.countryKey("target"))));
        register(game("lend_lease", "war", "The actor ships equipment to the receiver in regular shipments.", "LendLeaseAction",
                p(of("receiver", Kind.COUNTRY, ""), of("equipment", Kind.TEXT, "infantry, artillery, tanks or aircraft"),
                        of("per_shipment", Kind.LONG, ""), opt("shipments", Kind.INT, "", 6), opt("cancel", Kind.BOOL, "", false)),
                (c, f) -> f.set("receiverName", c.country("receiver")).set("type", lendLeaseType(c.text("equipment")))
                        .set("perShipment", c.number("per_shipment")).set("shipments", c.integer("shipments")).set("cancel", c.flag("cancel"))));
        register(game("sponsor_uprising", "war", "The actor funds rebels inside the target, starting near a city.", "SponsorUprisingAction",
                p(of("target", Kind.COUNTRY, target), of("city", Kind.CITY, "where it starts"), of("gold", Kind.LONG, ""),
                        opt("government", Kind.GOVERNMENT, "rebel government", "REBELS")),
                (c, f) -> {
                    int[] xy = c.city("city");
                    f.set("targetCountryName", c.country("target")).set("formId", c.government("government"))
                            .set("seedX", xy[0]).set("seedY", xy[1]).set("gold", c.number("gold")).set("mode", 0);
                }));
        register(game("regime_change", "war", "The actor works to change the target's government.", "RegimeChangeAction",
                p(of("target", Kind.COUNTRY, target), of("government", Kind.GOVERNMENT, ""), opt("strike", Kind.BOOL, "act now instead of investing", false),
                        opt("points", Kind.INT, "", 10)),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("formId", c.government("government"))
                        .set("mode", c.flag("strike") ? 1 : 0).set("points", c.integer("points"))));
        // Trade and money
        // The game's own transfer actions only work inside an alliance, so deals move gold and
        // resources directly, with the same calls the game uses for alliance bribes.
        register(new ActionSpec("give_gold", "trade", "The actor pays gold from its treasury to the target.",
                p(of("target", Kind.COUNTRY, ""), of("amount", Kind.LONG, "")),
                c -> {
                    CountryState from = world.country(c.actor()), to = world.country(c.country("target"));
                    long amount = c.number("amount");
                    if (amount <= 0) return ActionResult.fail("The amount must be positive");
                    if (from.getGold() < amount) {
                        return ActionResult.fail(from.getDisplayName() + " has only " + from.getGold() + " gold");
                    }
                    if (!from.spendGold(amount)) return ActionResult.fail("The treasury refused the payment");
                    to.addGold(amount, BudgetLedger.Flow.DEALS);
                    return ActionResult.ok(from.getDisplayName() + " paid " + amount + " gold to " + to.getDisplayName());
                }));
        register(new ActionSpec("give_resources", "trade", "The actor sends part of a stockpile to the receiver.",
                p(of("receiver", Kind.COUNTRY, ""), of("resource", Kind.RESOURCE, ""), of("amount", Kind.LONG, "")),
                c -> {
                    CountryState from = world.country(c.actor()), to = world.country(c.country("receiver"));
                    String res = c.resource("resource");
                    long amount = c.number("amount");
                    if (res.equals("gold")) return ActionResult.fail("Use give_gold for gold");
                    if (amount <= 0) return ActionResult.fail("The amount must be positive");
                    long have = from.getResourceAmount(res);
                    if (have < amount) return ActionResult.fail(from.getDisplayName() + " has only " + have + " " + res);
                    from.deductResourceAmount(res, amount);
                    to.addResourceAmount(res, amount);
                    return ActionResult.ok(from.getDisplayName() + " sent " + amount + " " + res + " to " + to.getDisplayName());
                }));
        register(game("alliance_gold_transfer", "trade", "Inside an alliance only: the actor moves gold from its reserves to an ally.",
                "TransferGoldReserveAction", p(of("target", Kind.COUNTRY, ""), of("amount", Kind.LONG, "")),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("amount", c.number("amount"))));
        register(game("gift", "trade", "The actor gifts a share of one stockpile to the target, for goodwill.", "SendGiftAction",
                p(of("target", Kind.COUNTRY, ""), of("resource", Kind.RESOURCE, ""), opt("percent", Kind.INT, "", 10)),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("resourceKey", c.resource("resource")).set("percent", c.integer("percent"))));
        register(game("trade_contract", "trade", "The actor buys from or sells to the counterparty at a fixed price.", "ProposeTradeContractAction",
                p(of("counterparty", Kind.COUNTRY, ""), of("resource", Kind.RESOURCE, ""), of("buying", Kind.BOOL, "true: the actor buys"),
                        of("amount", Kind.LONG, ""), of("unit_price", Kind.INT, "gold per unit"), opt("monthly", Kind.BOOL, "repeat every month", false)),
                (c, f) -> f.set("counterpartyName", c.country("counterparty")).set("resource", c.resource("resource")).set("buying", c.flag("buying"))
                        .set("amount", c.number("amount")).set("unitPrice", c.integer("unit_price")).set("mode", c.flag("monthly") ? 1 : 0)));
        register(game("loan", "trade", "A loan between the actor and the counterparty.", "ProposeLoanAction",
                p(of("counterparty", Kind.COUNTRY, ""), of("actor_lends", Kind.BOOL, "true: the actor is the lender"),
                        of("principal", Kind.LONG, ""), of("daily_repayment", Kind.LONG, ""), of("days", Kind.INT, "")),
                (c, f) -> f.set("counterpartyName", c.country("counterparty")).set("proposerIsLender", c.flag("actor_lends"))
                        .set("principal", c.number("principal")).set("dailyRepayment", c.number("daily_repayment")).set("days", c.integer("days"))
                        .set("collateralPixels", new java.util.HashSet<Long>()),
                new Check() {
                    public Object before(ActionCall c) { return loans(c); }

                    public String after(ActionCall c, Object b) {
                        return loans(c) > (Integer) b ? null : "The game refused the loan";
                    }

                    private int loans(ActionCall c) {
                        CountryState borrower = world.country(c.flag("actor_lends") ? c.country("counterparty") : c.actor());
                        return borrower.loansTaken == null ? 0 : borrower.loansTaken.size();
                    }
                }));
        register(game("market_loan", "economy", "The actor borrows from the world market.", "TakeMarketLoanAction",
                p(of("principal", Kind.LONG, ""), opt("days", Kind.INT, "", 360)),
                (c, f) -> f.set("principal", c.number("principal")).set("days", c.integer("days"))));
        // Territory
        register(game("cede_land", "territory", "The actor hands part of its own land to the target.", "CedeLandAction",
                p(of("target", Kind.COUNTRY, "who receives it"), of("territory", Kind.TERRITORY, "land of the actor")),
                (c, f) -> f.set("targetCountryName", c.country("target")).set("pixels", c.territory("territory", c.actor())),
                landGrows("target")));
        register(game("buy_land", "territory", "The actor buys land from the seller for gold.", "ProposeTerritoryPurchaseAction",
                p(of("seller", Kind.COUNTRY, ""), of("territory", Kind.TERRITORY, "land of the seller"), of("gold", Kind.LONG, "")),
                (c, f) -> f.set("sellerCountryName", c.country("seller")).set("pixels", c.territory("territory", c.country("seller")))
                        .set("goldOffered", c.number("gold")).set("barterPixels", new java.util.HashSet<Long>()),
                landGrows(null)));
        register(game("lease_out_land", "territory", "The actor leases part of its land to the lessee for daily rent.", "ProposeTerritoryLeaseAction",
                p(of("lessee", Kind.COUNTRY, ""), of("territory", Kind.TERRITORY, "land of the actor"), of("daily_rent", Kind.LONG, ""),
                        opt("days", Kind.INT, "", 3650)),
                (c, f) -> f.set("lesseeCountryName", c.country("lessee")).set("pixels", c.territory("territory", c.actor()))
                        .set("dailyRent", c.number("daily_rent")).set("daysRequested", c.integer("days"))));
        register(game("lease_land", "territory", "The actor leases land from the lessor for daily rent.", "RequestTerritoryLeaseAction",
                p(of("lessor", Kind.COUNTRY, ""), of("territory", Kind.TERRITORY, "land of the lessor"), of("daily_rent", Kind.LONG, ""),
                        opt("days", Kind.INT, "", 3650)),
                (c, f) -> f.set("lessorCountryName", c.country("lessor")).set("pixels", c.territory("territory", c.country("lessor")))
                        .set("dailyRent", c.number("daily_rent")).set("daysRequested", c.integer("days"))));
        // Economy and politics of the actor itself
        register(game("war_economy", "economy", "The actor sets how far its economy is geared for war.", "SetWarEconomyAction",
                p(of("percent", Kind.INT, "0..100")), (c, f) -> f.set("percent", c.integer("percent"))));
        register(game("production_focus", "economy", "The actor sets the military share of production.", "SetProductionFocusAction",
                p(of("military_percent", Kind.INT, "0..100")), (c, f) -> f.set("targetMilitaryPermille", c.integer("military_percent") * 10)));
        register(game("build", "economy", "The actor builds upgrades (e.g. barracks, tankFactory, university).", "UpgradeBuildingAction",
                p(of("building", Kind.TEXT, ""), opt("quantity", Kind.INT, "", 1)),
                (c, f) -> {
                    try {
                        f.set("upgradeType", c.text("building")).set("customUpgradeKey", null);
                    } catch (IllegalArgumentException notBuiltIn) {
                        f.set("customUpgradeKey", c.text("building"));
                    }
                    f.set("quantity", c.integer("quantity"));
                }));
        register(game("alliance_doctrine", "politics", "The actor sets its alliance doctrine.", "SetAllianceDoctrineAction",
                p(of("doctrine", Kind.TEXT, "none, defence, offensive, standards or mobilization")),
                (c, f) -> f.set("doctrine", doctrine(c.text("doctrine")))));

        // World: the things the game otherwise only does through peace deals and scenario events
        register(new ActionSpec("make_peace", "world", "The war between the actor and the target ends at once.",
                p(of("target", Kind.COUNTRY, target)),
                c -> world.makePeace(c.actor(), c.country("target")) ? ActionResult.ok("Peace signed")
                        : ActionResult.fail("They are not at war")));
        register(new ActionSpec("annex", "world", "The actor absorbs the whole target country (a union, or total surrender).",
                p(of("target", Kind.COUNTRY, target)),
                c -> {
                    world.annex(c.actor(), c.country("target"));
                    return ActionResult.ok(c.country("target") + " is now part of " + c.actor());
                }));
        register(new ActionSpec("transfer_land", "world", "Land of the actor passes to the receiver immediately.",
                p(of("receiver", Kind.COUNTRY, ""), of("territory", Kind.TERRITORY, "land of the actor")),
                c -> ActionResult.ok(world.transferTerritory(c.actor(), c.country("receiver"), c.territory("territory", c.actor()))
                        + " map pixels transferred")));
        register(new ActionSpec("take_land", "world", "Land of the other country passes to the actor immediately.",
                p(of("from", Kind.COUNTRY, ""), of("territory", Kind.TERRITORY, "land of 'from'")),
                c -> ActionResult.ok(world.transferTerritory(c.country("from"), c.actor(), c.territory("territory", c.country("from")))
                        + " map pixels transferred")));
        register(new ActionSpec("set_government", "world", "The actor's government changes form; flag and name follow.",
                p(of("government", Kind.GOVERNMENT, "")),
                c -> {
                    world.setGovernment(c.actor(), c.government("government"));
                    return ActionResult.ok("Government is now " + c.government("government").toLowerCase().replace('_', ' '));
                }));
        register(new ActionSpec("rename", "world", "The actor takes a new name, e.g. after a union.",
                p(of("name", Kind.TEXT, "")),
                c -> {
                    world.rename(c.actor(), c.text("name"));
                    return ActionResult.ok("Now known as " + c.text("name"));
                }));
        register(new ActionSpec("change_attitude", "world", "How the actor's government feels about the target changes.",
                p(of("target", Kind.COUNTRY, ""), of("change", Kind.INT, "-100..100")),
                c -> {
                    world.changeAiRelation(c.actor(), c.country("target"), c.integer("change"));
                    return ActionResult.ok("Attitude towards " + c.country("target") + " " + c.integer("change"));
                }));
        register(new ActionSpec("effect", "world", "Applies any scenario-event effect to the actor (add_gold, add_tanks, set_tax, add_war_exhaustion ...).",
                p(of("type", Kind.TEXT, "event effect type"), opt("value", Kind.FLOAT, "", 0), opt("text", Kind.TEXT, "text argument", "")),
                c -> {
                    world.applyEffect(c.actor(), c.text("type"), c.decimal("value"), c.text("text"));
                    return ActionResult.ok(c.text("type") + " " + c.decimal("value"));
                }));
        register(new ActionSpec("fire_event", "world", "Fires a scenario event (by id) for the actor.",
                p(of("event", Kind.TEXT, "event id from events.json"), opt("delay_days", Kind.INT, "", 0)),
                c -> {
                    world.fireEvent(c.text("event"), c.actor(), c.integer("delay_days"));
                    return ActionResult.ok("Event " + c.text("event") + " fired");
                }));
    }

    // ---- checks ----

    /** Thrown by a binder when the action would change nothing; reported as done, with the reason. */
    private static final class Skip extends RuntimeException {
        Skip(String message) { super(message); }
    }

    /** A check that only looks at the game after the action. */
    private interface After {
        String test(ActionCall c) throws Exception;
    }

    private static Check after(After test) {
        return new Check() {
            public Object before(ActionCall c) { return null; }

            public String after(ActionCall c, Object b) throws Exception { return test.test(c); }
        };
    }

    /** Actor and target end up in the same alliance of this kind (0 military, 1 economic). */
    private Check allied(int kind) {
        return after(c -> {
            CountryState a = world.country(c.actor()), t = world.country(c.country("target"));
            String la = ru.ius.david.cgs.game.AllianceRules.leaderName(a, kind);
            String lt = ru.ius.david.cgs.game.AllianceRules.leaderName(t, kind);
            return la != null && la.equals(lt) ? null : "The game refused the alliance (rules such as wars or rival blocs can block it)";
        });
    }

    /** The receiving country holds more land afterwards; {@code param} null means the actor receives. */
    private Check landGrows(String param) {
        return new Check() {
            public Object before(ActionCall c) { return pixels(param == null ? c.actor() : c.country(param)); }

            public String after(ActionCall c, Object b) {
                return pixels(param == null ? c.actor() : c.country(param)) > (Integer) b ? null : "No land changed hands";
            }
        };
    }

    private int pixels(String country) {
        ru.ius.david.cgs.CountryData d = world.wars().getCountryData(world.colorKey(country));
        return d == null ? 0 : d.currentPixels;
    }

    private static int campaignKind(String s) {
        switch (s == null ? "" : s.toLowerCase()) {
            case "worsen": return 1;
            case "influence": return 2;
            case "integration": case "integrate": return 3;
            default: return 0;
        }
    }

    private static int lendLeaseType(String s) {
        switch (s == null ? "" : s.toLowerCase()) {
            case "artillery": return 1;
            case "tanks": case "tank": return 2;
            case "aircraft": case "planes": return 3;
            default: return 0;
        }
    }

    private static int doctrine(String s) {
        switch (s == null ? "" : s.toLowerCase()) {
            case "defence": case "defense": return 1;
            case "offensive": return 2;
            case "standards": return 3;
            case "mobilization": case "mobilisation": return 4;
            default: return 0;
        }
    }

    /** Helper for mods: a fresh mutable argument map. */
    public static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        return m;
    }
}
