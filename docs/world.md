# Game and world

Two views of the running game:

- **`GameApi`** (`ctx.game()`): stable, simple reads and the game's own JSON reports.
- **`World`** (`ctx.world()`): direct access to countries, cities, territory, governments and
  events, including changes the game normally only makes through peace deals and scenario events.

Both read the game state, so call them on the render thread (in callbacks, click handlers, live
labels) or through `ctx.game().onGameThread(...)`. Check `ctx.game().inGame()` first: in the
main menu there is no world.

## GameApi

```java
CountryInfo me = ctx.game().player();
ctx.log(me.displayName() + " has " + me.gold() + " gold on " + ctx.game().date());
for (CountryInfo c : ctx.game().countries()) if (c.ally()) ctx.log("ally: " + c.displayName());
```

| Method | Returns |
|---|---|
| `inGame()` | True while a scenario is played and its map is on screen |
| `date()`, `year()` | In-game date as the game shows it; the year |
| `player()` | The player's `CountryInfo` |
| `countries()` | Every country that still exists, sorted by display name |
| `country(name)` | One `CountryInfo`, or `null` |
| `countryDetailsJson(name)` | Resources, military, income, upgrades, diplomacy, AI relations (game JSON) |
| `worldStateJson()` | All countries with key stats plus active wars |
| `warsJson()` | Every war with attacker, defender and progress (-1..1, positive = attacker winning) |
| `neighborsJson(name)` | Countries sharing a border, and whether they are at war |
| `onGameThread(supplier)` | Runs a read on the render thread; `CompletableFuture` of the result |

`CountryInfo` is a snapshot record, safe to keep and pass between threads:
`name` (internal), `tag`, `displayName`, `government`, `gold`, `relationWithPlayer`, `ally`,
`puppet`, `isPlayer`, `strength`.

The JSON reports are the same ones the game's built-in AI tools use; they are the easiest way to
give an LLM the full picture.

`GameApi` also has direct writes that go through the game's AI connector and complete with its
JSON answer: `makePeace`, `declareWar`, `setRelation`, `setAlly`, `setPuppet`, `addGold`,
`addResource`. They mark the run as externally modified; for most things prefer the
[action catalog](actions.md), which follows the game's rules and checks results.

## World

```java
World w = ctx.world();
for (World.CityInfo c : w.cities("France")) ctx.log(c.name() + (c.main() ? " (capital)" : ""));
Set<Long> alsace = w.territoryAround("Germany", "Strasbourg", 30);
int moved = w.transferTerritory("Germany", "France", alsace);
w.setGovernment("Spain", "PARLIAMENTARY_REPUBLIC");
```

Countries can be given by internal name, display name or tag everywhere.

### Countries

| Method | |
|---|---|
| `country(nameOrTag)` | The game's `CountryState`, or `null` |
| `internalName(nameOrTag)` | The internal name for any accepted spelling |
| `exists(nameOrTag)` | Whether the country still exists |
| `colorKey(nameOrTag)`, `nameOfKey(key)` | Colour key used by war and map code, and back |
| `game()`, `wars()` | The game's `GameManager` and `WarManager`, for anything not covered |

### Cities

`cities(owner)` (all cities when `owner` is null), `city(name)`, `cityPixel(city)`.
`CityInfo(id, name, x, y, owner, level, main)`.

### Territory

Territory is a `Set<Long>` of map pixels, packed as `(x << 32) | y` (`World.pack`, `unpackX`,
`unpackY`), the format the game's own land actions use.

| Method | |
|---|---|
| `territoryOf(owner)` | All land of a country |
| `territoryAround(owner, city, radius)` | Land of `owner` within `radius` map pixels of a city |
| `territoryFacing(owner, neighbour, share)` | The strip of `owner` closest to `neighbour`, about `share` (0..1) of its land |
| `transferTerritory(from, to, pixels)` | Moves land the way peace deals do; returns pixels moved |
| `annex(winner, loser)` | `winner` absorbs all of `loser` |

### Politics, events, wars

| Method | |
|---|---|
| `setGovernment(country, form)` | Government form by enum name (AUTOCRACY, PARLIAMENTARY_REPUBLIC, ...); flag follows |
| `rename(country, newName)` | The game's custom-name field |
| `applyEffect(country, type, value, text)` | Any scenario-event effect, exactly as an event applies it |
| `fireEvent(eventId, country, delayDays)` | Fires a scenario event from the scenario's `events.json` |
| `changeAiRelation(a, b, delta)` | How `a` feels about `b` in the AI's relation table |
| `makePeace(a, b)`, `atWar(a, b)` | End a war; check for one |

Effect types for `applyEffect`: add_gold, add_resource, add_manpower, add_infantry,
add_artillery, add_tanks, add_aircraft, add_ships, add_nukes, add_science,
add_diplomacy_points, add_war_exhaustion, add_revolution_risk, add_infamy, add_relation (with
the player), add_support, set_government, set_tax, set_conscription, set_military_spending,
set_social_security, set_science_funding, add_liberty_desire, declare_war, trigger_event.

Every `World` change marks the run as externally modified.

### Map camera

`ctx.camera()` changes the map camera's limits (the bundled *Zoom Out* mod is built on it):

```java
ctx.camera().setZoomOutFactor(3f);  // zoom out three times further than the game allows (1..10)
ctx.camera().setEdgeMargin(0.5f);   // scroll past the edges by half the screen (0..1)
float z = ctx.camera().zoom();      // current zoom, larger = further out; 0 without a map
```

`ctx.camera().toScreen(mapX, mapY)` gives where a map point (map pixels, y down) is on the player's
screen, as `{x, y}` in the y-down window pixels of `Ui` and input events (null without a map): for
your own labels and markers on the map.

`ctx.camera().renderMapView(x, y, visibleHeight)` draws the game's own map (no interface) from another
camera into the screen; use it in `beforeFrame`, where the game's frame paints over it afterwards, and read
the pixels in between. `renderCleanMapView` does the same as a neutral documentary view: political colours,
whatever map mode the player uses, no selection, hover, rally point, overlays or half-drawn arrows; army
flags (classic combat, on their front markers) or unit flags with troop counts (divisions) without troop
sprites; besieged cities' garrison bars. The bundled Replays mod films games this way and adds its own
troop-count badges in classic combat, where the game's flags carry no numbers.

Call the setters on the render thread from `onGameReady` on (not in `onLoad`); an open map picks up
the change at once. The values are shared by all mods (last call wins) and do nothing in
multiplayer.

### The game's own classes

`world().game()` returns the game's `GameManager` (package `ru.ius.david.cgs`). You can call
anything public on it, but those are the game's internals: they have no documentation and can
change with any update. The `aoc.api` methods are the part that is kept working.
