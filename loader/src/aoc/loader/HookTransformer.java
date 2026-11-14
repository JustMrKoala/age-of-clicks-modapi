package aoc.loader;

import aoc.api.internal.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Inserts the hooks the mod API needs:
 * <ul>
 *   <li>{@code cgsGame.render()}: call {@code Hooks.afterRender()} before every return,
 *       so mods draw on top of the finished frame.</li>
 *   <li>{@code DefaultLwjgl3Input.setInputProcessor(p)}: replace {@code p} with
 *       {@code Hooks.wrapInput(p)}, so mods see input before the game.</li>
 *   <li>{@code DefaultLwjgl3Input.getInputProcessor()}: return {@code Hooks.unwrapInput(..)},
 *       so game dialogs that save and restore the processor keep working.</li>
 *   <li>{@code MainMenuScreen.buildMenu()}: call {@code Hooks.afterMainMenuBuilt(this)} to add
 *       the rows of mods (Ui.addMainMenuButton) and MULTIPLAYER.</li>
 *   <li>Steam multiplayer gate: in the multiplayer lobby, {@code SteamManager.isAvailable()} becomes
 *       {@code Hooks.lobbySteamAvailable()}, and Steam lobby joins from invites go through
 *       {@code Hooks.joinSteamLobby}/{@code Hooks.joinLobbyDirectly}, so Steam networking stays off
 *       unless the player allows it.</li>
 *   <li>{@code GameClient$2}: the fixed ports 54555/54777 become {@code Hooks.clientTcpPort()} /
 *       {@code clientUdpPort()}, so the client can join lobbies on other ports of a central server.</li>
 *   <li>{@code MapScreen.calculateZoomThresholds()}: call {@code Hooks.afterZoomThresholds(this)} so
 *       {@code MapCamera} can raise the zoom-out cap; in {@code updateCameraBounds()} the
 *       {@code MathUtils.clamp} calls become {@code Hooks.clampCamera}, which can let the camera
 *       pan past the map edges.</li>
 *   <li>{@code CGSUpgradesMenu} (the Economy menu): mod rows under its "Found city" row, see
 *       {@code aoc.api.internal.EconomyRows}.</li>
 * </ul>
 * Every insertion happens where the operand stack shape is unchanged, so the existing
 * stack map frames stay valid and only max stack/locals need recomputing.
 */
final class HookTransformer implements ClassFileTransformer {
    private static final String GAME = "ru/ius/david/cgs/cgsGame";
    private static final String MENU = "ru/ius/david/cgs/MainMenuScreen";
    private static final String INPUT = "com/badlogic/gdx/backends/lwjgl3/DefaultLwjgl3Input";
    private static final String LOBBY = "ru/ius/david/cgs/MultiplayerLobbyScreen";
    /** The game map: zoom-out cap and camera bounds, for {@code aoc.api.MapCamera}. */
    private static final String MAP = "ru/ius/david/cgs/MapScreen";
    /** The Economy menu: mod rows under its "Found city" row ({@code aoc.api.internal.EconomyRows}). */
    private static final String UPGRADES = "ru/ius/david/cgs/menus/CGSUpgradesMenu";
    private static final String ECONOMY_ROWS = "aoc/api/internal/EconomyRows";
    /** Country fill colours fade per frame here; the film pass makes the fade complete at once. */
    private static final String TERRITORY = "ru/ius/david/cgs/TerritoryRenderer";
    /**
     * Methods that do nothing during a film pass ({@code Hooks.filmPass()}): the troop sprites, and the
     * unit display smoothing that would otherwise run twice per frame. Key: class, value: name+descriptor.
     */
    private static final java.util.Map<String, java.util.Set<String>> FILM_SKIPPED = java.util.Map.of(
            MAP, java.util.Set.of("drawDivisionUnits(Lru/ius/david/cgs/units/UnitSprites;Ljava/util/List;FF)V"),
            "ru/ius/david/cgs/units/MilitaryUnit", java.util.Set.of("smoothDisplay(F)V", "updateDisplay(FZ)V"),
            // Classic combat: the front-line troop pass. Its figures, their motion and fire, the front and
            // battle state updates and the ambience sound stay with the player's frame; flags, counts,
            // sparks and flares are drawn.
            "ru/ius/david/cgs/WarManager", java.util.Set.of("updateFrontlineDecor(F)V", "updateDivisionMarch(F)V", "updateBattleFx(F)V"),
            "ru/ius/david/cgs/units/UnitSprites", java.util.Set.of(
                    "addUnit(IFFFLru/ius/david/cgs/units/UnitMotion;FLcom/badlogic/gdx/graphics/Color;Lcom/badlogic/gdx/graphics/Color;)V",
                    "addUnit(IFFFLru/ius/david/cgs/units/UnitMotion;FLcom/badlogic/gdx/graphics/Color;Lcom/badlogic/gdx/graphics/Color;I)V"),
            "ru/ius/david/cgs/units/UnitMotion", java.util.Set.of("follow(FFFFFF)V", "steer(FFFFZZFFFF)V", "animate(FFF)V"),
            // Where the troop figures stand (their anchors); the film draws no figures.
            "ru/ius/david/cgs/units/FrontUnitLayout", java.util.Set.of(
                    "standTarget(Lru/ius/david/cgs/units/FrontlineDecor$Marker;FLru/ius/david/cgs/WarManager;F[F)V"),
            "ru/ius/david/cgs/units/UnitFire", java.util.Set.of(
                    "add(Lru/ius/david/cgs/units/UnitSprites;IFFFFJFFFLru/ius/david/cgs/units/UnitFire$Listener;)V"),
            "ru/ius/david/cgs/managers/WorldAmbience", java.util.Set.of("beginFrame(IIF)V", "addWar(F)V"));
    /** The anonymous class whose run() connects the game client to TCP 54555 / UDP 54777. */
    private static final String CLIENT_CONNECT = "ru/ius/david/cgs/multiplayer/GameClient$2";
    /** Creates the KryoNet client with 4 MB buffers; PortSwap raises them. */
    private static final String CLIENT = "ru/ius/david/cgs/multiplayer/GameClient";
    private static final int GAME_NET_BUFFER = 4 * 1024 * 1024;
    private static final int CLIENT_NET_BUFFER = 16 * 1024 * 1024;
    private static final String STEAM_ID = "Lcom/codedisaster/steamworks/SteamID;";
    /** Classes whose calls into Steam multiplayer are redirected through {@code Hooks}. */
    private static final java.util.Set<String> STEAM_CALLERS = java.util.Set.of(
            LOBBY, GAME, "ru/ius/david/cgs/cgsGame$2", "ru/ius/david/cgs/steam/SteamManager$2$1");
    private static final String HOOKS = "aoc/api/internal/Hooks";
    private static final String PROCESSOR = "Lcom/badlogic/gdx/InputProcessor;";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] bytes) {
        if (!GAME.equals(name) && !INPUT.equals(name) && !MENU.equals(name) && !STEAM_CALLERS.contains(name)
                && !CLIENT_CONNECT.equals(name) && !CLIENT.equals(name) && !MAP.equals(name)
                && !FILM_SKIPPED.containsKey(name) && !TERRITORY.equals(name) && !UPGRADES.equals(name)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String m, String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, m, desc, sig, ex);
                    if (STEAM_CALLERS.contains(name)) mv = new SteamGate(mv, LOBBY.equals(name));
                    if (CLIENT_CONNECT.equals(name) || CLIENT.equals(name)) mv = new PortSwap(mv);
                    if (GAME.equals(name) && m.equals("render") && desc.equals("()V")) {
                        return new BeforeReturn(mv, Opcodes.RETURN, "afterRender", "()V", false) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beforeRender", "()V", false);
                            }
                        };
                    }
                    if (TERRITORY.equals(name) && m.equals("renderLayers")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String mName, String mDesc, boolean itf) {
                                if (op == Opcodes.INVOKEVIRTUAL && owner.equals("com/badlogic/gdx/graphics/Color") && mName.equals("lerp")
                                        && mDesc.equals("(Lcom/badlogic/gdx/graphics/Color;F)Lcom/badlogic/gdx/graphics/Color;")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "filmLerp",
                                            "(Lcom/badlogic/gdx/graphics/Color;Lcom/badlogic/gdx/graphics/Color;F)Lcom/badlogic/gdx/graphics/Color;", false);
                                } else {
                                    super.visitMethodInsn(op, owner, mName, mDesc, itf);
                                }
                            }
                        };
                    }
                    if (UPGRADES.equals(name)) {
                        MethodVisitor rows = economyRows(mv, m, desc);
                        if (rows != null) return rows;
                    }
                    java.util.Set<String> skipped = FILM_SKIPPED.get(name);
                    if (skipped != null && skipped.contains(m + desc)) return new SkipDuringFilm(mv);
                    if (MAP.equals(name) && m.equals("calculateZoomThresholds") && desc.equals("()V")) {
                        return new BeforeReturn(mv, Opcodes.RETURN, "afterZoomThresholds", "(Ljava/lang/Object;)V", true);
                    }
                    if (MAP.equals(name) && m.equals("constrainCamera") && desc.equals("()V")) {
                        // if (Hooks.constrainCamera(this)) return;  -- this is where the game really stops the camera
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                org.objectweb.asm.Label go = new org.objectweb.asm.Label();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "constrainCamera", "(Ljava/lang/Object;)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        };
                    }
                    if (MAP.equals(name) && m.equals("updateCameraBounds") && desc.equals("()V")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String mName, String mDesc, boolean itf) {
                                if (op == Opcodes.INVOKESTATIC && owner.equals("com/badlogic/gdx/math/MathUtils")
                                        && mName.equals("clamp") && mDesc.equals("(FFF)F")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "clampCamera", "(FFF)F", false);
                                } else {
                                    super.visitMethodInsn(op, owner, mName, mDesc, itf);
                                }
                            }
                        };
                    }
                    if (MENU.equals(name) && m.equals("buildMenu") && desc.equals("()V")) {
                        return new BeforeReturn(mv, Opcodes.RETURN, "afterMainMenuBuilt", "(Ljava/lang/Object;)V", true);
                    }
                    if (INPUT.equals(name) && m.equals("getInputProcessor") && desc.equals("()" + PROCESSOR)) {
                        return new BeforeReturn(mv, Opcodes.ARETURN, "unwrapInput", "(" + PROCESSOR + ")" + PROCESSOR, false);
                    }
                    if (INPUT.equals(name) && m.equals("setInputProcessor") && desc.equals("(" + PROCESSOR + ")V")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "wrapInput",
                                        "(" + PROCESSOR + ")" + PROCESSOR, false);
                                super.visitVarInsn(Opcodes.ASTORE, 1);
                            }
                        };
                    }
                    return mv;
                }
            }, 0);
            Log.info("Hooked " + name);
            return writer.toByteArray();
        } catch (Throwable t) {
            Log.error("Could not hook " + name + "; mods will not be shown", t);
            return null;
        }
    }

    /**
     * Economy menu hooks for mod rows: the row list gets the mod rows on its way out of
     * {@code buildUpgradesRows()}, {@code update(float)} ends with the rows' texts and actions, and
     * {@code openUpgradeDetail(row)} starts with {@code if (EconomyRows.opened(row)) return;} (an F_SAME
     * frame at the method start, like {@link SkipDuringFilm}). Null for other methods.
     */
    private static MethodVisitor economyRows(MethodVisitor mv, String m, String desc) {
        if (m.equals("buildUpgradesRows") && desc.equals("()Ljava/util/List;")) {
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.ARETURN) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, ECONOMY_ROWS, "rows", "(Ljava/util/List;)Ljava/util/List;", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
        if (m.equals("update") && desc.equals("(F)V")) {
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, ECONOMY_ROWS, "afterUpdate", "(Ljava/lang/Object;)V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
        if (m.equals("openUpgradeDetail") && desc.equals("(L" + UPGRADES + "$UpgradeRow;)V")) {
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    org.objectweb.asm.Label go = new org.objectweb.asm.Label();
                    super.visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, ECONOMY_ROWS, "opened", "(Ljava/lang/Object;)Z", false);
                    super.visitJumpInsn(Opcodes.IFEQ, go);
                    super.visitInsn(Opcodes.RETURN);
                    super.visitLabel(go);
                    super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                }
            };
        }
        return null;
    }

    /**
     * Sends Steam multiplayer calls through {@code Hooks}. Each replacement consumes the same
     * stack values as the original call, so frames stay valid.
     */
    private static final class SteamGate extends MethodVisitor {
        private final boolean inLobby;

        SteamGate(MethodVisitor mv, boolean inLobby) {
            super(Opcodes.ASM9, mv);
            this.inLobby = inLobby;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (inLobby && opcode == Opcodes.INVOKESTATIC && owner.equals("ru/ius/david/cgs/steam/SteamManager")
                    && name.equals("isAvailable") && desc.equals("()Z")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "lobbySteamAvailable", "()Z", false);
            } else if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(GAME) && name.equals("joinSteamLobbyDirectly")
                    && desc.equals("(" + STEAM_ID + ")V")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "joinSteamLobby", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            } else if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(LOBBY) && name.equals("joinLobbyDirectly")
                    && desc.equals("(" + STEAM_ID + ")V")) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "joinLobbyDirectly", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            } else {
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
        }
    }

    /** Replaces the game's fixed client ports with {@code Hooks.clientTcpPort()/clientUdpPort()}. */
    private static final class PortSwap extends MethodVisitor {
        PortSwap(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitLdcInsn(Object value) {
            if (Integer.valueOf(54555).equals(value)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "clientTcpPort", "()I", false);
            } else if (Integer.valueOf(54777).equals(value)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "clientUdpPort", "()I", false);
            } else if (Integer.valueOf(GAME_NET_BUFFER).equals(value)) {
                // World snapshots of big maps (Modern World ~2 MB) come close to the game's 4 MB.
                super.visitLdcInsn(CLIENT_NET_BUFFER);
            } else {
                super.visitLdcInsn(value);
            }
        }
    }

    /**
     * Prepends {@code if (Hooks.filmPass()) return;} to a void method. The jump target sits at the
     * method start with nothing on the stack, so an F_SAME frame is valid there and the method's own
     * frames (stored as deltas) stay correct.
     */
    private static final class SkipDuringFilm extends MethodVisitor {
        SkipDuringFilm(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();
            org.objectweb.asm.Label go = new org.objectweb.asm.Label();
            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "filmPass", "()Z", false);
            super.visitJumpInsn(Opcodes.IFEQ, go);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(go);
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
    }

    /** Calls a static hook right before each return instruction of the given opcode, optionally passing {@code this}. */
    private static class BeforeReturn extends MethodVisitor {
        private final int returnOpcode;
        private final String hook;
        private final String desc;
        private final boolean passThis;

        BeforeReturn(MethodVisitor mv, int returnOpcode, String hook, String desc, boolean passThis) {
            super(Opcodes.ASM9, mv);
            this.returnOpcode = returnOpcode;
            this.hook = hook;
            this.desc = desc;
            this.passThis = passThis;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == returnOpcode) {
                if (passThis) super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, hook, desc, false);
            }
            super.visitInsn(opcode);
        }
    }
}
