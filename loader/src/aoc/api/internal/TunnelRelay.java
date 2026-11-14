package aoc.api.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * The player's end of a match played through a web tunnel. The game only speaks plain TCP and UDP,
 * a tunnel (Cloudflare Tunnel) only carries web traffic, so the game connects to this relay on its
 * own machine and the relay carries everything over one WebSocket to the central server's gateway
 * ({@code aoc.server.Gateway}), which unwraps it next to the game server. Each binary message
 * starts with one byte: {@code 'T'} for a piece of the TCP stream, {@code 'U'} for one UDP datagram.
 */
final class TunnelRelay implements AutoCloseable {
    private static final int CHUNK = 16 * 1024;

    private final String url;
    private final ServerSocket tcp;
    private final DatagramSocket udp;
    private final Object sendLock = new Object();
    private volatile WebSocket ws;
    private volatile Socket game;
    private volatile SocketAddress gameUdp;
    private volatile boolean closed;

    private TunnelRelay(String url, ServerSocket tcp, DatagramSocket udp) {
        this.url = url;
        this.tcp = tcp;
        this.udp = udp;
    }

    /**
     * Starts a relay to {@code url} (wss://server/ws/&lt;match port&gt;). The game must then connect to
     * 127.0.0.1 on {@link #port()} for both TCP and UDP.
     */
    static TunnelRelay open(String url) throws IOException {
        InetAddress local = InetAddress.getLoopbackAddress();
        IOException last = null;
        for (int tries = 0; tries < 20; tries++) {
            ServerSocket t = new ServerSocket(0, 4, local);
            try {
                // The game uses one port number for TCP and UDP here, so both must be free.
                DatagramSocket u = new DatagramSocket(t.getLocalPort(), local);
                TunnelRelay r = new TunnelRelay(url, t, u);
                r.daemon("aoc-tunnel-accept", r::accept);
                r.daemon("aoc-tunnel-udp", r::pumpUdp);
                return r;
            } catch (IOException e) {
                last = e;
                t.close();
            }
        }
        throw last;
    }

    int port() { return tcp.getLocalPort(); }

    private void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket s = tcp.accept();
                dropSession();
                game = s;
                daemon("aoc-tunnel-tcp", () -> session(s));
            } catch (IOException e) {
                if (!closed) Log.error("Tunnel relay stopped accepting", e);
                return;
            }
        }
    }

    /** One connection of the game: a WebSocket of its own, then the game's bytes go up as 'T' messages. */
    private void session(Socket s) {
        WebSocket socket = null;
        try {
            s.setTcpNoDelay(true);
            OutputStream toGame = s.getOutputStream();
            socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(url), new Downlink(toGame, s)).get(20, TimeUnit.SECONDS);
            ws = socket;
            Log.info("Tunnel relay: connected to " + url);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[CHUNK + 1];
            buf[0] = 'T';
            for (int n; (n = in.read(buf, 1, CHUNK)) >= 0; ) send(socket, buf, n + 1);
        } catch (Exception e) {
            if (!closed && !s.isClosed()) Log.info("Tunnel relay: connection ended (" + e + ")");
        } finally {
            closeQuietly(s);
            if (socket != null) socket.abort();
            if (ws == socket) ws = null;
        }
    }

    /** Datagrams of the game go up as 'U' messages; the answers come back to wherever the game sent from. */
    private void pumpUdp() {
        byte[] buf = new byte[65536];
        buf[0] = 'U';
        DatagramPacket p = new DatagramPacket(buf, 1, buf.length - 1);
        while (!closed) {
            try {
                p.setData(buf, 1, buf.length - 1);
                udp.receive(p);
                gameUdp = p.getSocketAddress();
                WebSocket socket = ws;
                if (socket != null) send(socket, buf, p.getLength() + 1);
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    /** Messages are sent one at a time: the WebSocket allows no overlapping sends. */
    private void send(WebSocket socket, byte[] data, int length) throws IOException {
        synchronized (sendLock) {
            try {
                socket.sendBinary(ByteBuffer.wrap(data.clone(), 0, length), true).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    /** What the server sends: TCP pieces to the game's connection, datagrams to the game's UDP port. */
    private final class Downlink implements WebSocket.Listener {
        private final OutputStream toGame;
        private final Socket socket;
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();

        Downlink(OutputStream toGame, Socket socket) {
            this.toGame = toGame;
            this.socket = socket;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] part = new byte[data.remaining()];
            data.get(part);
            message.write(part, 0, part.length);
            if (last) {
                byte[] m = message.toByteArray();
                message.reset();
                try {
                    if (m.length > 1 && m[0] == 'T') {
                        toGame.write(m, 1, m.length - 1);
                        toGame.flush();
                    } else if (m.length > 1 && m[0] == 'U') {
                        SocketAddress to = gameUdp;
                        if (to != null) udp.send(new DatagramPacket(m, 1, m.length - 1, to));
                    }
                } catch (IOException e) {
                    closeQuietly(socket);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeQuietly(socket);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!closed) Log.info("Tunnel relay: the connection to the server broke (" + error + ")");
            closeQuietly(socket);
        }
    }

    private void dropSession() {
        Socket s = game;
        if (s != null) closeQuietly(s);
        WebSocket socket = ws;
        if (socket != null) socket.abort();
        ws = null;
    }

    @Override
    public void close() {
        closed = true;
        dropSession();
        try {
            tcp.close();
        } catch (IOException ignored) {
            // already closed
        }
        udp.close();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /** Stand-alone, for testing with a headless player: {@code TunnelRelay wss://server/ws/54601}. */
    public static void main(String[] args) throws Exception {
        TunnelRelay r = open(args[0]);
        System.out.println("Relay to " + args[0] + " listening on 127.0.0.1:" + r.port() + " (TCP and UDP)");
        Thread.sleep(Long.MAX_VALUE);
    }
}
