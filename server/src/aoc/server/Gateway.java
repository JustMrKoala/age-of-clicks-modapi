package aoc.server;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.function.IntPredicate;

/**
 * The lobby service's public port. Ordinary requests are passed on to the HTTP API (which listens
 * on the loopback interface only); a WebSocket request for {@code /ws/<match port>} becomes a relay
 * to that match's game server on this machine.
 *
 * <p>The relay is what lets matches run through a web tunnel (Cloudflare Tunnel and the like),
 * which carries web traffic only while the game speaks plain TCP and UDP: the player's loader wraps
 * the game's traffic in WebSocket frames, and this side unwraps it. Each binary frame starts with
 * one byte, {@code 'T'} for a piece of the TCP stream or {@code 'U'} for one UDP datagram. Every
 * player gets a TCP connection and a UDP socket of their own towards the game server, so the game
 * tells them apart as usual.
 */
final class Gateway {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER = 16 * 1024, MAX_FRAME = 1 << 20, CHUNK = 16 * 1024;

    private final int publicPort, apiPort;
    private final IntPredicate matchPort;

    /** {@code matchPort}: true for ports of matches that run now (nothing else can be reached). */
    Gateway(int publicPort, int apiPort, IntPredicate matchPort) {
        this.publicPort = publicPort;
        this.apiPort = apiPort;
        this.matchPort = matchPort;
    }

    void start() throws IOException {
        ServerSocket server = new ServerSocket(publicPort, 64);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    daemon("aoc-gateway-conn", () -> serve(s));
                } catch (IOException e) {
                    DedicatedServer.log("Gateway: accept failed: " + e);
                }
            }
        }, "aoc-gateway");
        t.setDaemon(true);
        t.start();
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket s) {
        try (s) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(30_000);
            InputStream in = s.getInputStream();
            byte[] head = readHeader(in);
            if (head == null) return;
            String text = new String(head, StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 3) return;
            String upgrade = header(lines, "Upgrade"), key = header(lines, "Sec-WebSocket-Key");
            if (upgrade != null && upgrade.equalsIgnoreCase("websocket") && key != null && request[1].startsWith("/ws/")) {
                relay(s, in, request[1], key, clientOf(s, lines));
            } else {
                api(s, in, lines, clientOf(s, lines));
            }
        } catch (IOException ignored) {
            // the other side went away
        }
    }

    /** The player's address: the tunnel's header when the request came through a tunnel on this machine. */
    private static String clientOf(Socket s, String[] lines) {
        if (s.getInetAddress().isLoopbackAddress()) {
            String cf = header(lines, "CF-Connecting-IP");
            if (cf != null && cf.matches("[0-9A-Fa-f:.]{3,45}")) return cf;
        }
        return s.getInetAddress().getHostAddress();
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        int state = 0;
        while (out.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            out.write(b);
            state = b == '\r' ? (state == 2 ? 3 : 1) : b == '\n' ? (state == 1 ? 2 : state == 3 ? 4 : 0) : 0;
            if (state == 4) return out.toByteArray();
        }
        return null;
    }

    private static String header(String[] lines, String name) {
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase(name)) return lines[i].substring(colon + 1).trim();
        }
        return null;
    }

    // ---- the HTTP API behind it ----

    /** One request per connection: the header is rewritten (who asks, close afterwards), the rest is piped. */
    private void api(Socket s, InputStream in, String[] lines, String client) throws IOException {
        StringBuilder head = new StringBuilder(lines[0]).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            if (lines[i].isEmpty() || lower.startsWith("connection:") || lower.startsWith("x-aoc-client:")) continue;
            head.append(lines[i]).append("\r\n");
        }
        head.append("X-Aoc-Client: ").append(client).append("\r\nConnection: close\r\n\r\n");
        try (Socket api = new Socket(InetAddress.getLoopbackAddress(), apiPort)) {
            api.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            api.getOutputStream().flush();
            daemon("aoc-gateway-body", () -> {
                try {
                    pipe(in, api.getOutputStream());
                } catch (IOException ignored) {
                    // request body ended
                }
            });
            pipe(api.getInputStream(), s.getOutputStream());
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[CHUNK];
        for (int n; (n = in.read(buf)) >= 0; ) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    // ---- the relay to a match ----

    private void relay(Socket s, InputStream rawIn, String path, String key, String client) throws IOException {
        OutputStream out = s.getOutputStream();
        int port;
        try {
            port = Integer.parseInt(path.substring("/ws/".length()).replaceAll("[/?].*", ""));
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (!matchPort.test(port)) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        s.setSoTimeout(120_000);
        int match = port;
        DedicatedServer.log("Relay: " + client + " joined match port " + match);
        try (Socket tcp = new Socket(InetAddress.getLoopbackAddress(), match); DatagramSocket udp = new DatagramSocket()) {
            tcp.setTcpNoDelay(true);
            udp.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), match));
            Object lock = new Object();
            // Game server -> player.
            daemon("aoc-relay-tcp", () -> {
                byte[] buf = new byte[CHUNK];
                try {
                    InputStream in = tcp.getInputStream();
                    for (int n; (n = in.read(buf)) >= 0; ) frame(out, lock, 2, 'T', buf, n);
                } catch (IOException ignored) {
                    // closed below
                }
                close(s);
            });
            daemon("aoc-relay-udp", () -> {
                byte[] buf = new byte[65536];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    while (!s.isClosed()) {
                        p.setLength(buf.length);
                        udp.receive(p);
                        frame(out, lock, 2, 'U', buf, p.getLength());
                    }
                } catch (IOException ignored) {
                    // socket closed
                }
            });
            // Player -> game server.
            DataInputStream in = new DataInputStream(rawIn);
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            OutputStream toGame = tcp.getOutputStream();
            while (true) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0, masked = (b1 & 0x80) != 0;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len < 0 || len > MAX_FRAME) throw new IOException("frame too large");
                byte[] mask = new byte[4];
                if (masked) in.readFully(mask);
                byte[] data = new byte[(int) len];
                in.readFully(data);
                if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 8) break;
                if (op == 9) {
                    frame(out, lock, 10, -1, data, data.length);
                    continue;
                }
                if (op == 10) continue;
                message.write(data);
                if (!fin) {
                    if (message.size() > MAX_FRAME) throw new IOException("message too large");
                    continue;
                }
                byte[] m = message.toByteArray();
                message.reset();
                if (m.length < 1) continue;
                if (m[0] == 'T') {
                    toGame.write(m, 1, m.length - 1);
                    toGame.flush();
                } else if (m[0] == 'U') {
                    udp.send(new DatagramPacket(m, 1, m.length - 1));
                }
            }
        } catch (EOFException ignored) {
            // the player left
        } finally {
            DedicatedServer.log("Relay: " + client + " left match port " + match);
        }
    }

    /** Writes one unmasked frame; {@code tag} (when >= 0) goes in front of the payload. */
    private static void frame(OutputStream out, Object lock, int opcode, int tag, byte[] data, int n) throws IOException {
        int len = n + (tag >= 0 ? 1 : 0);
        byte[] head = new byte[10];
        int h = 0;
        head[h++] = (byte) (0x80 | opcode);
        if (len < 126) {
            head[h++] = (byte) len;
        } else if (len < 65536) {
            head[h++] = 126;
            head[h++] = (byte) (len >> 8);
            head[h++] = (byte) len;
        } else {
            head[h++] = 127;
            for (int i = 7; i >= 0; i--) head[h++] = (byte) ((long) len >> (8 * i));
        }
        synchronized (lock) {
            out.write(head, 0, h);
            if (tag >= 0) out.write(tag);
            out.write(data, 0, n);
            out.flush();
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
