package aoc.server;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;

/**
 * Patches the game's network code for dedicated matches ({@code -javaagent:aoc-server.jar}):
 * <ul>
 *   <li>Ports: the game has TCP 54555 / UDP 54777 compiled in; with {@code -Daoc.port=N} the server
 *       bind and client connect use port N for both, so several matches share one machine.</li>
 *   <li>Buffers: the game's 4 MB KryoNet buffers become {@link NetGuard#bufferSize()}.</li>
 *   <li>Sends: every send in {@code GameServer} goes through {@link NetGuard}, so a slow player
 *       gets fewer snapshots (or is dropped) instead of crashing the match.</li>
 * </ul>
 */
public final class PortAgent {
    private static final String SERVER = "ru/ius/david/cgs/multiplayer/GameServer";
    private static final Set<String> PATCHED = Set.of(SERVER, "ru/ius/david/cgs/multiplayer/GameClient",
            "ru/ius/david/cgs/multiplayer/GameClient$2");
    private static final String KRYO_SERVER = "com/esotericsoftware/kryonet/Server";
    private static final String KRYO_CONNECTION = "com/esotericsoftware/kryonet/Connection";
    private static final int GAME_BUFFER = 4 * 1024 * 1024;

    private PortAgent() {}

    /** Port for this process: {@code aoc.port}, or the game's own defaults. */
    public static int tcp() { return Integer.getInteger("aoc.port", 54555); }

    public static int udp() { return Integer.getInteger("aoc.port", 54777); }

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader l, String name, Class<?> c, ProtectionDomain d, byte[] bytes) {
                if (!PATCHED.contains(name)) return null;
                boolean server = SERVER.equals(name);
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int acc, String m, String desc, String sig, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, m, desc, sig, ex)) {
                            @Override
                            public void visitLdcInsn(Object value) {
                                if (Integer.valueOf(54555).equals(value)) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "aoc/server/PortAgent", "tcp", "()I", false);
                                } else if (Integer.valueOf(54777).equals(value)) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "aoc/server/PortAgent", "udp", "()I", false);
                                } else if (Integer.valueOf(GAME_BUFFER).equals(value)) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "aoc/server/NetGuard", "bufferSize", "()I", false);
                                } else {
                                    super.visitLdcInsn(value);
                                }
                            }

                            @Override
                            public void visitMethodInsn(int op, String owner, String mName, String mDesc, boolean itf) {
                                if (server && op == Opcodes.INVOKEVIRTUAL && KRYO_SERVER.equals(owner)
                                        && (mName.equals("sendToAllTCP") || mName.equals("sendToAllExceptTCP") || mName.equals("sendToTCP"))) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "aoc/server/NetGuard", mName,
                                            "(L" + KRYO_SERVER + ";" + mDesc.substring(1), false);
                                } else if (server && op == Opcodes.INVOKEVIRTUAL && KRYO_CONNECTION.equals(owner) && mName.equals("sendTCP")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "aoc/server/NetGuard", "sendTCP",
                                            "(L" + KRYO_CONNECTION + ";" + mDesc.substring(1), false);
                                } else {
                                    super.visitMethodInsn(op, owner, mName, mDesc, itf);
                                }
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
