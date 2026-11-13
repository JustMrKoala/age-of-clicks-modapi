package aoc.server;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import ru.ius.david.cgs.multiplayer.NetworkProtocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one slow connection from crashing a match. The game's server sends a full world snapshot
 * to every player every second (about 2 MB on Modern World), and KryoNet throws - killing the
 * server - once a player's send buffer is full. {@link PortAgent} routes every send in
 * {@code GameServer} through here.
 *
 * <ul>
 *   <li>Broadcast snapshots go to each player at most every {@code aoc.snapshot_seconds} (default 5),
 *       and are skipped while that player's connection is still sending older data. Ticks with the
 *       players' actions still go out every second; snapshots only correct drift.</li>
 *   <li>A send that fails closes that one connection instead of the whole server.</li>
 *   <li>Buffers are {@code aoc.net_buffer_mb} (default 16) instead of the game's 4 MB.</li>
 * </ul>
 */
public final class NetGuard {
    private static final Map<Integer, Long> lastSnapshot = new ConcurrentHashMap<>();

    private NetGuard() {}

    public static int bufferSize() { return Integer.getInteger("aoc.net_buffer_mb", 16) * 1024 * 1024; }

    private static long snapshotMillis() {
        return (long) (Double.parseDouble(System.getProperty("aoc.snapshot_seconds", "5")) * 1000);
    }

    public static void sendToAllTCP(Server server, Object message) { sendToAllExceptTCP(server, -1, message); }

    public static void sendToAllExceptTCP(Server server, int exceptId, Object message) {
        boolean snapshot = message instanceof NetworkProtocol.WorldStateSyncMessage;
        long now = System.currentTimeMillis();
        for (Connection c : server.getConnections()) {
            if (c.getID() == exceptId) continue;
            if (snapshot) {
                Long last = lastSnapshot.get(c.getID());
                if (last != null && now - last < snapshotMillis()) continue;
                if (c.getTcpWriteBufferSize() > bufferSize() / 8) continue;
                lastSnapshot.put(c.getID(), now);
            }
            sendTCP(c, message);
        }
    }

    public static void sendToTCP(Server server, int connectionId, Object message) {
        for (Connection c : server.getConnections()) {
            if (c.getID() == connectionId) {
                sendTCP(c, message);
                return;
            }
        }
    }

    public static int sendTCP(Connection c, Object message) {
        if (!c.isConnected()) return 0;
        try {
            return c.sendTCP(message);
        } catch (RuntimeException e) {
            // The buffer is full or half-written now; this player cannot catch up.
            DedicatedServer.log("Dropping connection " + c.getID() + ": it cannot keep up (" + e.getMessage() + ")");
            lastSnapshot.remove(c.getID());
            c.close();
            return 0;
        }
    }
}
