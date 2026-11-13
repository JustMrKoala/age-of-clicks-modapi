package aoc.replay;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A recorded match: what the {@link Recorder} wrote, read back for the player and the exporter.
 *
 * <p>File layout (gzip, written with sync flushes so a file cut short by a crash still reads up
 * to its last complete frame):
 * <pre>
 * "AOCRPL" int version  UTF scenario  UTF title  int mapW  int mapH  int scale  int playerKey  long created
 * records: byte type
 *   COUNTRY  int key  UTF name  UTF tag  int rgba
 *   NAME     int id  UTF text                        (string table, for alliance names)
 *   FRAME    int day  UTF date
 *            int runs   { varint gap, varint length, int owner }   cells that changed since the last frame
 *            int n      { int key, double income, long population, int allianceNameId }
 *            int wars   { int id, int attacker, int defender, float progress, 4 x float focus }
 *            int cities { int id, UTF name, int x, int y, int level, int owner, bool capital (v2) }   new since the last frame
 *            int player (v3)   the player's country now
 * </pre>
 * The ownership grid is the game's, sampled every {@code scale} map pixels; -1 is sea or no owner.
 */
final class Replay {
    static final String MAGIC = "AOCRPL";
    static final int VERSION = 3;
    static final byte COUNTRY = 1, NAME = 2, FRAME = 3;

    record Country(int key, String name, String tag, int rgba) {}

    record Stats(int key, double income, long population, int alliance) {}

    record War(int id, int attacker, int defender, float progress, float ax, float ay, float dx, float dy) {}

    record City(int id, String name, int x, int y, int level, int owner, boolean capital) {}

    /** One sampled moment: grid changes (runs of gap/length/owner) and everything else in full. */
    /** {@code player}: the player's country at this frame (it changes when a nation is formed); -1 in old files. */
    record Frame(int day, String date, int[] runs, Stats[] stats, War[] wars, City[] newCities, int player) {}

    String scenario, title;
    int mapW, mapH, scale, playerKey;
    long created;
    final Map<Integer, Country> countries = new HashMap<>();
    final Map<Integer, String> names = new HashMap<>();
    final List<Frame> frames = new ArrayList<>();

    int gridW() { return (mapW + scale - 1) / scale; }

    int gridH() { return (mapH + scale - 1) / scale; }

    String countryName(int key) {
        Country c = countries.get(key);
        return c == null ? "?" : c.name();
    }

    String allianceName(int id) { return id < 0 ? null : names.get(id); }

    /** Years of game time covered. */
    float years() {
        if (frames.size() < 2) return 0f;
        return (frames.get(frames.size() - 1).day() - frames.get(0).day()) / 365f;
    }

    static Replay read(File file) throws IOException {
        Replay r = new Replay();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 1 << 16)))) {
            byte[] magic = new byte[MAGIC.length()];
            in.readFully(magic);
            if (!MAGIC.equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) throw new IOException("Not a replay file");
            int version = in.readInt();
            if (version > VERSION) throw new IOException("Replay from a newer version of the mod");
            r.scenario = in.readUTF();
            r.title = in.readUTF();
            r.mapW = in.readInt();
            r.mapH = in.readInt();
            r.scale = in.readInt();
            r.playerKey = in.readInt();
            r.created = in.readLong();
            try {
                while (true) {
                    int type = in.read();
                    if (type < 0) break;
                    if (type == COUNTRY) {
                        Country c = new Country(in.readInt(), in.readUTF(), in.readUTF(), in.readInt());
                        r.countries.put(c.key(), c);
                    } else if (type == NAME) {
                        r.names.put(in.readInt(), in.readUTF());
                    } else if (type == FRAME) {
                        r.frames.add(readFrame(in, version));
                    } else {
                        throw new IOException("Damaged replay (record " + type + ")");
                    }
                }
            } catch (EOFException cutShort) {
                // the game closed mid-write: keep every complete frame
            }
        }
        if (r.frames.isEmpty()) throw new IOException("The replay has no frames yet");
        return r;
    }

    private static Frame readFrame(DataInputStream in, int version) throws IOException {
        int day = in.readInt();
        String date = in.readUTF();
        int runCount = in.readInt();
        int[] runs = new int[runCount * 3];
        for (int i = 0; i < runCount; i++) {
            runs[i * 3] = readVarint(in);
            runs[i * 3 + 1] = readVarint(in);
            runs[i * 3 + 2] = in.readInt();
        }
        Stats[] stats = new Stats[in.readInt()];
        for (int i = 0; i < stats.length; i++) stats[i] = new Stats(in.readInt(), in.readDouble(), in.readLong(), in.readInt());
        War[] wars = new War[in.readInt()];
        for (int i = 0; i < wars.length; i++) {
            wars[i] = new War(in.readInt(), in.readInt(), in.readInt(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
        }
        City[] cities = new City[in.readInt()];
        for (int i = 0; i < cities.length; i++) {
            cities[i] = new City(in.readInt(), in.readUTF(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                    version >= 2 && in.readBoolean());
        }
        int player = version >= 3 ? in.readInt() : -1;
        return new Frame(day, date, runs, stats, wars, cities, player);
    }

    static void writeVarint(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7f) != 0) {
            out.writeByte((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarint(DataInputStream in) throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
        }
    }
}
