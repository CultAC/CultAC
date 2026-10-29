package ac.cult.cultac.network.routing;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enough of a Redis server for pub/sub over RESP2: SUBSCRIBE, UNSUBSCRIBE, PUBLISH and PING; +OK to the rest. A stalled
 * server reads each PUBLISH and neither delivers nor answers it, as a broker that stopped responding would.
 */
final class FakeRedisServer implements AutoCloseable {
    private final ServerSocket server;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger publishes = new AtomicInteger();
    volatile boolean stalled;

    FakeRedisServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::accept, "fake redis");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    int subscribers(String channel) {
        int count = 0;
        for (Client client : clients) if (client.channels.contains(channel)) count++;
        return count;
    }

    /** The open connections. */
    int connections() {
        return clients.size();
    }

    /** Every PUBLISH read so far, stalled ones included. */
    int publishes() {
        return publishes.get();
    }

    int pings() {
        int count = 0;
        for (Client client : clients) count += client.pings;
        return count;
    }

    /** Drops every connection, as a restarting server would. */
    void dropClients() {
        for (Client client : clients) client.close();
    }

    @Override
    public void close() throws IOException {
        server.close();
        dropClients();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Client client = new Client(server.accept());
                clients.add(client);
                Thread thread = new Thread(client, "fake redis client");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private final class Client implements Runnable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        final Set<String> channels = ConcurrentHashMap.newKeySet();
        volatile int pings;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        @Override
        public void run() {
            try {
                List<String> command;
                while ((command = read()) != null) handle(command);
            } catch (IOException gone) {
                // The client or the test closed the connection.
            } finally {
                close();
            }
        }

        private void handle(List<String> command) throws IOException {
            String name = command.getFirst().toUpperCase(Locale.ROOT);
            switch (name) {
                case "SUBSCRIBE" -> {
                    for (String channel : command.subList(1, command.size())) {
                        channels.add(channel);
                        write("*3\r\n" + bulk("subscribe") + bulk(channel) + ":" + channels.size() + "\r\n");
                    }
                }
                case "UNSUBSCRIBE" -> {
                    List<String> leaving =
                            command.size() > 1 ? command.subList(1, command.size()) : new ArrayList<>(channels);
                    for (String channel : leaving) {
                        channels.remove(channel);
                        write("*3\r\n" + bulk("unsubscribe") + bulk(channel) + ":" + channels.size() + "\r\n");
                    }
                }
                case "PUBLISH" -> {
                    publishes.incrementAndGet();
                    if (stalled) return;
                    int delivered = 0;
                    for (Client client : clients) {
                        if (!client.channels.contains(command.get(1))) continue;
                        client.write("*3\r\n" + bulk("message") + bulk(command.get(1)) + bulk(command.get(2)));
                        delivered++;
                    }
                    write(":" + delivered + "\r\n");
                }
                case "PING" -> {
                    pings++;
                    write(channels.isEmpty() ? "+PONG\r\n" : "*2\r\n" + bulk("pong") + bulk(""));
                }
                default -> write("+OK\r\n");
            }
        }

        private List<String> read() throws IOException {
            String header = line();
            if (header == null) return null;
            if (!header.startsWith("*")) throw new IOException("not an array: " + header);
            int count = Integer.parseInt(header.substring(1));
            List<String> parts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String length = line();
                if (length == null || !length.startsWith("$")) throw new IOException("not a bulk string");
                byte[] bytes = in.readNBytes(Integer.parseInt(length.substring(1)) + 2);
                parts.add(new String(bytes, 0, bytes.length - 2, StandardCharsets.UTF_8));
            }
            return parts;
        }

        private String line() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int next = in.read();
                if (next < 0) return null;
                if (previous == '\r' && next == '\n') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                }
                line.write(next);
                previous = next;
            }
        }

        private static String bulk(String value) {
            return "$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n";
        }

        synchronized void write(String reply) throws IOException {
            out.write(reply.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void close() {
            clients.remove(this);
            channels.clear();
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
