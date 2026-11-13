# Settings

Declare settings in `onLoad`; the loader builds an editor for them in main menu > **MODS** > **CODE MODS** >
your mod, validates what the player types, and saves to `<game>/mods/<id>/config.properties`.

```java
private Setting<String> apiKey;
private Setting<Integer> rounds;

public void onLoad(ModContext ctx) {
    apiKey = ctx.settings().secret("api_key", "API key", "From your provider's dashboard");
    rounds = ctx.settings().integer("rounds", "Rounds", 5, 1, 20, "Between 1 and 20");
    ctx.settings().toggle("verbose", "Verbose log", false, "More lines in aoc-loader.log");
    ctx.settings().choice("size", "Map labels", "M", List.of("S", "M", "L"), "");
    ctx.settings().onChange(() -> ctx.log("rounds is now " + rounds.get()));
}
```

| Declare | Type | Editor |
|---|---|---|
| `text(key, label, default, help)` | `String` | Text field |
| `secret(key, label, help)` | `String` | Masked text field, for keys and passwords |
| `toggle(key, label, default, help)` | `Boolean` | On/off |
| `integer(key, label, default, min, max, help)` | `Integer` | Number field, checked against min/max |
| `decimal(key, label, default, min, max, help)` | `Double` | Number field, checked against min/max |
| `choice(key, label, default, choices, help)` | `String` | Cycles through the choices |

`help` is shown under the field (and written as a comment in the file).

## Reading values

Call `setting.get()` whenever you need the value instead of copying it once: when the player
saves, `get()` returns the new value right away, without a restart. `onChange(runnable)` runs
after every save, for things you have to rebuild.

Other methods: `ctx.settings().all()` (declared settings), `file()` (the properties file),
`reload()` (re-read the file after editing it by hand). Keys you didn't declare are kept when the
player saves, so you can store extra values in the same file.

## Files

```
<game>/mods/<id>/config.properties
```

Plain `key=value` lines; players can edit the file with the game closed. Uninstalling the mod
loader leaves these folders in place, so settings survive a reinstall.
