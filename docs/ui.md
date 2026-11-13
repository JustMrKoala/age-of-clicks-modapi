# UI toolkit

`aoc.api.ui` draws windows and widgets with the game's own parts: its riveted iron-and-parchment
panels, buttons, fonts, colours and click sound. Everything follows the game's light/dark theme
and UI scale, so a mod's window looks like part of the game.

```java
Window w = new Window("Treaty", 420, 300);
TextField name = new TextField().placeholder("Treaty name");
w.content().add(
    new Label("Name your treaty:"),
    name,
    Separator.spacer().flex(1),
    Panel.row().gap(8).add(
        new Button("Sign").primary().onClick(() -> { ctx.ui().toast("Signed " + name.text(), 3); w.close(); }),
        new Button("Cancel").onClick(w::close)));
ctx.ui().open(w);
```

Build UI in `onGameReady` or later (fonts don't exist in `onLoad`).

## `Ui`: your mod's layer

| Method | |
|---|---|
| `open(w)`, `close(w)`, `toggle(w)`, `isOpen(w)` | Show and hide windows; the last opened is on top |
| `addHud(widget, anchor, dx, dy, when)` | Pins a widget to a screen corner or edge while `when` is true (design-pixel offsets) |
| `toast(text, seconds)` | A short notice at the top of the screen; safe from any thread |
| `addEconomyCityRow(name, description, chip, button, onClick)` | A row in the game's Economy menu, Cities section, right under its own "Found city" row, drawn like the game's rows: `chip` and `button` are suppliers for the row's badge text and button caption; `onClick` runs when the row or its button is clicked. Call once, e.g. in `onLoad` |
| `addMainMenuButton(label, onClick)` | A row for your mod in the main menu under MODS; the loader keeps at most two rows there (with multiplayer on, one) so EXIT stays on screen, and later mods get a button at the top of MODS > CODE MODS instead. Call once, e.g. in `onLoad` |
| `bindKey(keycode, action)`, `unbindKey(keycode)` | Global hotkey (`com.badlogic.gdx.Input.Keys`); works even while typing, so bind function keys, not letters |

`Ui.Anchor`: TOP_LEFT, TOP_CENTER, TOP_RIGHT, LEFT_CENTER, RIGHT_CENTER, BOTTOM_LEFT,
BOTTOM_CENTER, BOTTOM_RIGHT. The game's own HUD covers much of the top edge. Down the left edge
(TOP_LEFT), the Diplomacy button sits at `dy` 112 and the loader's Action Tester at 150; start
your own at 188 or lower down.

Clicks on your windows never reach the map; clicks elsewhere go to the game as usual.

## Layout and sizes

Every widget extends `Widget<S>`; setters return the widget itself, so they chain.

- `width(px)`, `height(px)`: fixed size in **design pixels** (scaled by the game's UI scale).
- `flex(weight)`: a share of the space left in its row or column after fixed-size siblings.
- Without either, a widget uses its natural size (text height, button width).
- `tooltip(text)`, `visible(bool)`.

Coordinates are **y-down** window pixels, like the game's UI.

## Widgets

| Widget | |
|---|---|
| `Window(title, w, h)` | Movable window with title bar and close button. `content()` is a column `Panel`. `closable(false)`, `onClose(r)`, `position(x, y)`, `title(t)`. Centres itself on first open. |
| `Panel.column()`, `Panel.row()` | Lay children out vertically / horizontally. `add(...)`, `remove`, `clear`, `padding(px)`, `gap(px)`, `background(CARD or WELL)` |
| `ScrollPanel(panel)` | Shows a column taller than its space; mouse wheel scrolls |
| `Label(text)`, `Label(() -> text)` | Wrapped text, fixed or live. `font(Theme.Font.LARGE)`, `color(c)`, `muted()`, `center()`, `right()` |
| `Button(text)`, `Button(() -> text)` | `primary()` (the game's red), `style(Theme.ButtonStyle.BLUE)`, `onClick(r)`, `enabled(() -> bool)` |
| `Toggle(label, on)` | On/off box. `value()`, `value(b)`, `onChange(b -> ...)` |
| `TextField()` | One-line input: Enter submits, Ctrl+V pastes, Escape leaves. `placeholder`, `maxLength`, `masked(true)` (keys, passwords), `onSubmit`, `onChange`, `text()`, `focus()` |
| `ListView<T>()` | Selectable, scrollable list. `items(() -> list)`, `label(t -> text)`, `note(t -> new ListView.Note(text, colour))`, `filter(t -> bool)`, `onSelect(t -> ...)`, `selected()`, `rowHeight(px)` |
| `MessageList()` | Chat log with bubbles: `add(Side.LEFT/RIGHT/NOTICE, author, text)`, `typing("Italy")`, `clear()`. `add` is safe from any thread. |
| `Separator()`, `Separator.spacer()` | The game's divider line; an invisible gap (give it `flex(1)` to push siblings apart) |

Live suppliers (`Label(() -> ...)`, `enabled(...)`, `items(...)`) run every frame on the render
thread. Keep them cheap: read a cached list, not `ctx.game().countries()` each frame.

## `Theme`: the game's look

For custom widgets (extend `Widget<S>` and implement `draw(SpriteBatch, float)`):

- Fonts: `Theme.font(Theme.Font.SMALL / MAIN / LARGE / TITLE)`, `text(...)`, `wrapped(...)`,
  `textWidth`, `lineHeight`.
- Colours (follow light/dark): `textDark`, `textMuted`, `panelBg`, `red`, `blue`, `good`, `warn`,
  `bad`, `gold`, `border`, `separator`, `surfaceLow`, `surfaceHigh`, ...
- Parts: `panel` (riveted window), `box` (bordered card), `button`, `selected` (the game's
  red-framed selection), `separator`, `row`, `shadow`, `closeButton`.
- `Theme.s(px)` scales a design size by the UI scale; `Theme.click()` plays the click sound.

Input hooks on `Widget`: `touchDown` (return true to capture), `touchUp`, `dragged`,
`scrolled`, and `keyTyped`/`keyDown` for focusable widgets (`focusable()` returns true).

## `Overlay`: raw drawing

`ctx.overlay()` (from `onGameReady` on) draws straight on the screen inside `render`: `rect`,
`outline`, `text`, `wrapped`, `clip`/`unclip`, `font(size)`, `batch()`. It uses libGDX's
**y-up** coordinates; `flipY(screenY)` converts input coordinates. Prefer the toolkit for
anything interactive.
