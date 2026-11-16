package aoc.server;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import ru.ius.david.cgs.game.GameManager;
import ru.ius.david.cgs.multiplayer.NetworkProtocol;
import ru.ius.david.cgs.multiplayer.WorldStateSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one slow connection from crashing a match, without letting it fall out of sync. The game's
 * server sends a world snapshot to every player each tick (about 2 MB on Modern World), and KryoNet
 * throws (killing the server) once a player's send buffer is full. {@link PortAgent} routes every
 * send in {@code GameServer} through here.
 *
 * <p>Game clients simulate nothing themselves ({@code GameManager.tickInner} returns at once on a
 * network client): gold, days, names and borders all come from these snapshots. Most of a snapshot
 * is the full current state, but renames, new countries and border changes are only in the
 * snapshot of the tick they happened. A skipped snapshot therefore loses them for good, and since
 * clients find countries by name, a lost rename stops every later update of that country (its
 * gold too). So:
 *
 * <ul>
 *   <li>Every snapshot goes out, except while that player's connection is still sending older data
 *       (or within {@code aoc.snapshot_seconds}, default 0, of the last one).</li>
 *   <li>For a skipped snapshot the one-time parts are kept per player ({@link Missed}) and go out
 *       with that player's next snapshot: renames combined in order, new countries and border
 *       changes appended. When the border backlog grows too big it is replaced by one full border
 *       sync.</li>
 *   <li>A send that fails closes that one connection instead of the whole server.</li>
 *   <li>Buffers are {@code aoc.net_buffer_mb} (default 16) instead of the game's 4 MB.</li>
 * </ul>
 */
public final class NetGuard {
    /** Border changes kept for one player before a full border sync replaces them. */
    private static final int MAX_MISSED_DELTAS = 300_000;

    private static final Map<Integer, Long> lastSnapshot = new ConcurrentHashMap<>();
    private static final Map<Integer, Missed> missed = new ConcurrentHashMap<>();
    private static final Map<Integer, int[]> stats = new ConcurrentHashMap<>();
    private static final Map<Integer, long[]> bytes = new ConcurrentHashMap<>();
    private static long nextReport;

    private NetGuard() {}

    public static int bufferSize() { return Integer.getInteger("aoc.net_buffer_mb", 16) * 1024 * 1024; }

    private static long snapshotMillis() {
        return (long) (Double.parseDouble(System.getProperty("aoc.snapshot_seconds", "0")) * 1000);
    }

    public static void sendToAllTCP(Server server, Object message) { sendToAllExceptTCP(server, -1, message); }

    public static void sendToAllExceptTCP(Server server, int exceptId, Object message) {
        if (!(message instanceof NetworkProtocol.WorldStateSyncMessage sync) || sync.worldState == null) {
            for (Connection c : server.getConnections()) if (c.getID() != exceptId) sendTCP(c, message);
            return;
        }
        long now = System.currentTimeMillis();
        for (Connection c : server.getConnections()) {
            if (c.getID() == exceptId || !c.isConnected()) continue;
            int id = c.getID();
            int[] st = stats.computeIfAbsent(id, k -> new int[2]);
            Long last = lastSnapshot.get(id);
            boolean tooSoon = last != null && now - last < snapshotMillis();
            if (tooSoon || c.getTcpWriteBufferSize() > bufferSize() / 8) {
                missed.computeIfAbsent(id, k -> new Missed()).add(sync.worldState);
                st[1]++;
                continue;
            }
            lastSnapshot.put(id, now);
            st[0]++;
            Missed m = missed.remove(id);
            int n = m == null ? sendTCP(c, message) : m.sendWith(c, sync);
            long[] b = bytes.computeIfAbsent(id, k -> new long[1]);
            b[0] += n;
        }
        if (now >= nextReport) report(server, now);
    }

    /** Once a minute: snapshots sent and skipped per player, so a slow line shows in the match log. */
    private static void report(Server server, long now) {
        if (nextReport != 0 && !stats.isEmpty()) {
            StringBuilder sb = new StringBuilder("Snapshots last minute:");
            for (Connection c : server.getConnections()) {
                int[] st = stats.get(c.getID());
                long[] b = bytes.get(c.getID());
                if (st == null) continue;
                sb.append(" #").append(c.getID()).append(' ').append(st[0]).append(" sent");
                if (st[1] > 0) sb.append(", ").append(st[1]).append(" skipped");
                if (b != null && st[0] > 0) sb.append(" (").append(b[0] / st[0] / 1024).append(" KB each)");
                sb.append(';');
            }
            DedicatedServer.log(sb.toString());
        }
        stats.clear();
        bytes.clear();
        nextReport = now + 60_000;
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
            missed.remove(c.getID());
            c.close();
            return 0;
        }
    }

    /** The one-time parts of the snapshots one player did not get. */
    static final class Missed {
        final HashMap<String, String> renames = new HashMap<>();
        final ArrayList<WorldStateSnapshot.NewCountryInfo> newCountries = new ArrayList<>();
        final ArrayList<WorldStateSnapshot.TerritoryDelta> land = new ArrayList<>();
        final ArrayList<WorldStateSnapshot.TerritoryDelta> naval = new ArrayList<>();
        final ArrayList<WorldStateSnapshot.BulkTransfer> bulk = new ArrayList<>();
        /** Too many border changes: send one full border sync instead of {@link #land}. */
        boolean fullBorders;

        void add(WorldStateSnapshot s) {
            if (s.renamedCountries != null) {
                for (Map.Entry<String, String> r : s.renamedCountries.entrySet()) rename(r.getKey(), r.getValue());
            }
            if (s.newCountries != null) for (WorldStateSnapshot.NewCountryInfo n : s.newCountries) newCountries.add(copy(n));
            if (!fullBorders && s.territoryDeltas != null) {
                if (land.size() + s.territoryDeltas.size() > MAX_MISSED_DELTAS) {
                    fullBorders = true;
                    land.clear();
                } else {
                    land.addAll(s.territoryDeltas);
                }
            }
            if (s.navalTerritoryDeltas != null && naval.size() < MAX_MISSED_DELTAS) naval.addAll(s.navalTerritoryDeltas);
            if (s.bulkTransfers != null) bulk.addAll(s.bulkTransfers);
        }

        /** Adds rename from -> to after the earlier ones, so a chain A -> B -> C becomes A -> C. */
        private void rename(String from, String to) {
            if (from == null || to == null || from.equals(to)) return;
            // A country that only exists in this backlog is renamed in place.
            boolean fresh = false;
            for (WorldStateSnapshot.NewCountryInfo n : newCountries) {
                if (from.equals(n.name)) {
                    n.name = to;
                    fresh = true;
                }
            }
            if (fresh) return;
            boolean chained = false;
            for (Iterator<Map.Entry<String, String>> it = renames.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, String> e = it.next();
                if (!from.equals(e.getValue())) continue;
                chained = true;
                if (e.getKey().equals(to)) it.remove();
                else e.setValue(to);
            }
            if (!chained) renames.put(from, to);
        }

        private static WorldStateSnapshot.NewCountryInfo copy(WorldStateSnapshot.NewCountryInfo n) {
            return new WorldStateSnapshot.NewCountryInfo(n.colorKey, n.r, n.g, n.b, n.name, n.customName, n.strength, n.tag);
        }

        /**
         * Sends this tick's snapshot with everything missed before it. KryoNet serializes during
         * {@code sendTCP}, so the shared snapshot is changed for that call only.
         */
        int sendWith(Connection c, NetworkProtocol.WorldStateSyncMessage sync) {
            WorldStateSnapshot s = sync.worldState;
            HashMap<String, String> renamesBefore = s.renamedCountries;
            ArrayList<WorldStateSnapshot.NewCountryInfo> newBefore = s.newCountries;
            ArrayList<WorldStateSnapshot.TerritoryDelta> landBefore = s.territoryDeltas, navalBefore = s.navalTerritoryDeltas;
            ArrayList<WorldStateSnapshot.BulkTransfer> bulkBefore = s.bulkTransfers;
            add(s);
            int n = 0;
            try {
                s.renamedCountries = renames;
                s.newCountries = newCountries;
                s.territoryDeltas = fullBorders ? landBefore : land;
                s.navalTerritoryDeltas = naval;
                s.bulkTransfers = bulk;
                n = sendTCP(c, sync);
            } finally {
                s.renamedCountries = renamesBefore;
                s.newCountries = newBefore;
                s.territoryDeltas = landBefore;
                s.navalTerritoryDeltas = navalBefore;
                s.bulkTransfers = bulkBefore;
            }
            if (fullBorders) {
                try {
                    NetworkProtocol.FullTerritorySyncMessage full = GameManager.getInstance().getWarManager().buildFullTerritorySync(s.tickNumber);
                    if (full != null) n += sendTCP(c, full);
                } catch (RuntimeException e) {
                    DedicatedServer.log("Full border sync for connection " + c.getID() + " failed: " + e);
                }
            }
            return n;
        }
    }
}
