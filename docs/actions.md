# Actions

`ctx.actions()` is a catalog of named actions: everything a country can do. Built-in actions
wrap the game's own player actions (so the game's rules, costs and AI reactions apply), plus
*world* actions that change the world directly (annexation, land transfer, government, events).
The full list with parameters is in the [action catalog](action-catalog.md).

## Performing an action

```java
ctx.actions().perform("declare_war", "Germany", Actions.args("target", "Poland"), false)
    .thenAccept(r -> ctx.log(r.ok() + " " + r.message()));
```

`perform(id, actor, args, approved)`:

- **id**: catalog id, e.g. `alliance`, `give_gold`, `cede_land`.
- **actor**: the country doing it. Internal name, display name or tag (`ctx.game().player().name()`
  for the player).
- **args**: parameter name → value. `Actions.args("k1", v1, "k2", v2, ...)` builds a mutable
  map; `Actions.argsFromJson("{\"target\": \"Italy\"}")` parses JSON (handy for AI output).
- **approved**: `true` when the other side already agreed, e.g. a deal the player accepted. The
  game then skips its own AI acceptance check (not its rules or costs). It marks the run as
  externally modified, see [Concepts](concepts.md#clean-runs-and-achievements).

`perform` runs on the next frame and completes with an `ActionResult`; it never completes
exceptionally. From the render thread you can call `performNow(...)` and get the result directly.

## Results

```java
record ActionResult(boolean ok, String message, boolean verified)
```

- `ok == false`: refused or failed; `message` says why (*Unknown country Prussia*,
  *alliance needs 'target'*, or the game's own refusal, which the loader captures from the game's
  log).
- `ok && verified`: done, and the loader checked the game state afterwards (the gold arrived,
  the alliance exists, the land moved).
- `ok && !verified`: the game accepted it without errors, but there is no check for this action.

Many of the game's actions refuse silently, so always show `message` rather than assuming it
worked.

## Parameter values

| Kind | Accepts |
|---|---|
| `country` | Internal name, display name or tag: `"Germany"`, `"GER"` |
| `city` | City name: `"Paris"` |
| `territory` | Land of a given country (which one depends on the action, see its description):<br>`"all"`: all of it<br>`"Paris"`: around a city (radius 30 map pixels)<br>`["Paris", "Lyon"]`: around several cities<br>`{"cities": ["Paris"], "radius": 40}`: around cities with a radius in map pixels<br>`{"facing": "Germany", "share": 0.1}`: the strip closest to a neighbour, about 10% of the land |
| `resource` | iron, coal, oil, timber, rubber, aluminum, uranium, manpower, infantry, artillery, tanks, aircraft, ships (some actions also take steel, fuel, ammo, parts) |
| `government` | A government form such as `AUTOCRACY`, `PARLIAMENTARY_REPUBLIC` (case and spaces don't matter) |
| `int`, `long`, `float` | Numbers, or numeric strings |
| `bool` | `true`/`false`, or the strings `"true"`/`"false"` |
| `text` | Any string; the catalog lists accepted values where there is a fixed set |

Parameters marked optional in the catalog can be left out.

## Listing the catalog

```java
List<ActionSpec> all = ctx.actions().all();
ActionSpec spec = ctx.actions().get("loan");
String text = ctx.actions().describe(s -> !s.category().equals("world"));
```

`describe` prints one line per action, grouped by category, with every parameter. That is the
text the Diplomacy mod puts in its AI prompt, so an AI can choose actions by name.

## Publishing your own actions

Register in `onLoad` (or later). Actions are shared: every mod, the Action Tester and the
Diplomacy AI see them.

```java
ctx.actions().register(new ActionSpec(
    "war_bonds", "economy",
    "The actor sells war bonds: gold now, more war exhaustion.",
    List.of(ParamSpec.of("amount", ParamSpec.Kind.LONG, "gold raised"),
            ParamSpec.opt("exhaustion", ParamSpec.Kind.FLOAT, "added war exhaustion", 2f)),
    call -> {
        long amount = call.number("amount");
        call.world().applyEffect(call.actor(), "add_gold", amount, "");
        call.world().applyEffect(call.actor(), "add_war_exhaustion", call.decimal("exhaustion"), "");
        return ActionResult.ok("Raised " + amount + " gold");
    }));
```

The executor runs on the render thread with an `ActionCall`:

| Method | Returns |
|---|---|
| `actor()` | Internal name of the acting country |
| `approved()` | Whether the other side already agreed |
| `world()` | The `World` |
| `country(name)` | Internal name from a country parameter; fails with a clear message if it doesn't exist |
| `countryKey(name)` | The country's colour key (what war and map code use) |
| `text`, `number`, `integer`, `decimal`, `flag` | Typed values |
| `resource(name)`, `government(name)` | Normalised resource and government names |
| `city(name)` | Map pixel `[x, y]` of a city parameter |
| `territory(name, owner)` | Set of packed pixels of `owner` selected by a territory parameter |
| `has(name)`, `raw(name)` | Whether a value was given; the value as passed |

Throw an exception (with a readable message) to fail; the loader turns it into
`ActionResult.fail(message)`. Choose an existing category (`diplomacy`, `war`, `trade`,
`territory`, `economy`, `politics`, `world`) or a new one. A `register` with an existing id
replaces that action.

## Any game action class

The game has 95 player action classes in `ru.ius.david.cgs.game.actions`; the catalog covers the
useful ones by name. For the rest:

```java
ctx.actions().performGameAction("DeclareWarAction", "Germany",
        Map.of("playerColorKey", "Germany", "targetColorKey", "Poland"), false);
```

The loader fills in the common `sourceCountryName`/`sourceColorKey` for the actor; a class's own
fields (like `playerColorKey` above) you set yourself, by name. Fields ending in `Name` or
`ColorKey` take country names, and pixel sets take territory selections of the actor's land (or `{"owner": "...", ...}`). The list of class
names is `Actions.GAME_ACTION_CLASSES`; `javap -public -cp <game>/app/desktop-*.jar
ru.ius.david.cgs.game.actions.DeclareWarAction` shows a class's fields. These are the game's
internals and can change with a game update; prefer catalog ids.
