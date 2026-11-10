package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU and allocation of route dispatch over scanned handlers of distinct listener classes, interleaved: a loop
 * over gating wrappers (the base), {@link PacketReceiveRoute} and {@link PacketSendRoute} over the same wrappers
 * ("chain"), and the routes over gated scanned handlers, the way per-player check routes hold them ("invoker chain").
 * Runs only with {@code CULT_ROUTE_BENCH} set; results go to stdout and {@code build/route-chain-bench.txt}.
 */
class RouteChainCpuBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    /** Long enough that Windows' 15.6 ms thread CPU granularity stays under 3% of a round. */
    private static final long ROUND_NANOS = 600_000_000L;

    private static final PacketHandlerScanner SCANNER =
            new PacketHandlerScanner(TestProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3)));

    interface Workload {
        void run(int i);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void routeDispatchCpu() throws IOException {
        Assumptions.assumeTrue(System.getenv("CULT_ROUTE_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_ROUTE_BENCH_REPS", "9"));

        Listener[] moveListeners = {
            new M00(), new M01(), new M02(), new M03(), new M04(), new M05(), new M06(), new M07(), new M08(),
            new M09(), new M10(), new M11(), new M12(), new M13(), new M14(), new M15(), new M16(), new M17(),
            new M18(), new M19(), new M20(), new M21(), new M22(), new M23(), new M24(), new M25(), new M26(),
            new M27(), new M28(), new M29(), new M30(), new M31(), new M32(), new M33(), new M34(), new M35(),
            new M36(), new M37(), new M38(), new M39(), new M40(), new M41(), new M42(), new M43()
        };
        Listener[] pongListeners = {
            new P0(), new P1(), new P2(), new P3(), new P4(), new P5(), new P6(), new P7(), new P8(), new P9()
        };
        Listener[] pingListeners = {
            new S00(), new S01(), new S02(), new S03(), new S04(), new S05(), new S06(), new S07(), new S08(),
            new S09(), new S10(), new S11()
        };
        PacketReceiveHandler[] moves = gated(moveListeners);
        PacketReceiveHandler[] pongs = gated(pongListeners);
        PacketSendHandler[] pings = gatedSend(pingListeners);
        PacketReceiveHandler[] small = Arrays.copyOf(pongs, 3);
        PacketReceiveHandler[] movesDirect = direct(moveListeners);
        PacketReceiveHandler[] pongsDirect = direct(pongListeners);
        PacketSendHandler[] pingsDirect = directSend(pingListeners);
        PacketReceiveHandler[] smallDirect = Arrays.copyOf(pongsDirect, 3);
        PacketReceiveHandler[] plain = {
            (event, player, packet) -> sink++,
            (event, player, packet) -> sink += 2,
            (event, player, packet) -> sink ^= 3,
            (event, player, packet) -> sink -= 4
        };
        var base = new Routes(
                new LoopReceive(moves),
                new LoopReceive(pongs),
                new LoopReceive(small),
                new LoopReceive(plain),
                new LoopSend(pings));
        var chained = new Routes(
                PacketReceiveRoute.of(moves)::dispatch,
                PacketReceiveRoute.of(pongs)::dispatch,
                PacketReceiveRoute.of(small)::dispatch,
                PacketReceiveRoute.of(plain)::dispatch,
                PacketSendRoute.of(pings, new PacketSendHandler[0])::dispatch);
        var invoking = new Routes(
                PacketReceiveRoute.of(movesDirect)::dispatch,
                PacketReceiveRoute.of(pongsDirect)::dispatch,
                PacketReceiveRoute.of(smallDirect)::dispatch,
                PacketReceiveRoute.of(plain)::dispatch,
                PacketSendRoute.of(pingsDirect, new PacketSendHandler[0])::dispatch);

        int n = 64;
        var moveEvents = new PacketReceiveEvent[n];
        var pongEvents = new PacketReceiveEvent[n];
        var pingEvents = new PacketSendEvent[n];
        for (int i = 0; i < n; i++) {
            var move = new ServerboundMovePlayer(
                    i * 0.3,
                    64 + (i % 7) * 0.1,
                    -i * 0.2,
                    i * 11f,
                    (i * 7) % 180 - 90f,
                    i % 3 == 0,
                    i % 5 == 0,
                    i % 4 != 3,
                    i % 2 == 0);
            moveEvents[i] = new PacketReceiveEvent<>(null, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, move);
            pongEvents[i] = new PacketReceiveEvent<>(
                    null, ConnectionPhase.PLAY, ServerboundPackets.PONG, new ServerboundPong(i));
            pingEvents[i] = new PacketSendEvent<>(
                    null, ConnectionPhase.PLAY, ClientboundPackets.PING, new ClientboundPing(i), false);
        }
        String[] names = {"move 44", "pong 10", "pong 3", "send 12", "mixed tick"};
        String[] sides = {"loop", "chain", "invoker chain"};
        Workload[][] workloads = new Workload[3][];
        Routes[] all = {base, chained, invoking};
        for (int side = 0; side < 3; side++) {
            Routes routes = all[side];
            workloads[side] = new Workload[] {
                i -> receive(routes.move, moveEvents[i & 63]),
                i -> receive(routes.pongs, pongEvents[i & 63]),
                i -> receive(routes.small, pongEvents[i & 63]),
                i -> send(routes.pings, pingEvents[i & 63]),
                i -> {
                    receive(routes.move, moveEvents[i & 63]);
                    receive(routes.small, pongEvents[i & 63]);
                    receive(routes.plain, pongEvents[i & 63]);
                    send(routes.pings, pingEvents[i & 63]);
                    receive(routes.pongs, pongEvents[(i + 1) & 63]);
                }
            };
        }
        var lines = new StringBuilder();
        for (int w = 0; w < names.length; w++) {
            double[][] cpu = new double[3][repetitions];
            double[][] alloc = new double[3][repetitions];
            for (int r = -3; r < repetitions; r++) {
                for (int k = 0; k < 3; k++) {
                    // Every rotation of the three, forwards and backwards, so no side always runs first.
                    int turn = Math.floorMod(r, 6);
                    int side = turn < 3 ? (turn + k) % 3 : (turn + 3 - k) % 3;
                    var workload = workloads[side][w];
                    long calls = 0;
                    long wallStart = System.nanoTime();
                    long bytes = THREADS.getCurrentThreadAllocatedBytes();
                    long start = THREADS.getCurrentThreadCpuTime();
                    while (System.nanoTime() - wallStart < ROUND_NANOS) {
                        for (int i = 0; i < 10_000; i++) workload.run(i);
                        calls += 10_000;
                    }
                    long used = THREADS.getCurrentThreadCpuTime() - start;
                    long allocated = THREADS.getCurrentThreadAllocatedBytes() - bytes;
                    if (r >= 0) {
                        cpu[side][r] = used / (double) calls;
                        alloc[side][r] = allocated / (double) calls;
                    }
                }
            }
            for (int side = 0; side < 3; side++)
                lines.append(line(names[w] + " " + sides[side], cpu[side], alloc[side]))
                        .append('\n');
            int[][] pairs = {{1, 0}, {2, 0}, {2, 1}};
            for (int[] pair : pairs) {
                double[] ratio = new double[repetitions];
                for (int r = 0; r < repetitions; r++) ratio[r] = cpu[pair[0]][r] / cpu[pair[1]][r];
                Arrays.sort(ratio);
                lines.append(String.format(
                                Locale.ROOT,
                                "%-36s median %.3f  min %.3f  max %.3f",
                                names[w] + " " + sides[pair[0]] + "/" + sides[pair[1]],
                                ratio[ratio.length / 2],
                                ratio[0],
                                ratio[ratio.length - 1]))
                        .append('\n');
            }
        }
        lines.append("sink ").append(sink).append('\n');
        System.out.print(lines);
        Path out = Path.of("build", "route-chain-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, lines);
    }

    static long sink;

    private static String line(String name, double[] nanos, double[] bytes) {
        double[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double[] allocated = bytes.clone();
        Arrays.sort(allocated);
        return String.format(
                Locale.ROOT,
                "%-22s median %8.1f ns  min %8.1f  max %8.1f  alloc %.2f B  (%d reps)",
                name,
                sorted[sorted.length / 2],
                sorted[0],
                sorted[sorted.length - 1],
                allocated[allocated.length / 2],
                sorted.length);
    }

    interface ReceivePath {
        void dispatch(PacketReceiveEvent event, CultPlayer player, Object packet);
    }

    interface SendPath {
        void dispatch(PacketSendEvent event, CultPlayer player, Object packet);
    }

    record Routes(ReceivePath move, ReceivePath pongs, ReceivePath small, ReceivePath plain, SendPath pings) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void receive(ReceivePath path, PacketReceiveEvent event) {
        path.dispatch(event, null, event.getPacket());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void send(SendPath path, PacketSendEvent event) {
        path.dispatch(event, null, event.getPacket());
    }

    /** The base: the loop the routes ran before chains. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    record LoopReceive(PacketReceiveHandler[] handlers) implements ReceivePath {
        @Override
        public void dispatch(PacketReceiveEvent event, CultPlayer player, Object packet) {
            for (PacketReceiveHandler handler : handlers) handler.handle(event, player, packet);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    record LoopSend(PacketSendHandler[] handlers) implements SendPath {
        @Override
        public void dispatch(PacketSendEvent event, CultPlayer player, Object packet) {
            for (PacketSendHandler handler : handlers) handler.handle(event, player, packet);
        }
    }

    /** One wrapper class around every scanned handler, gating as check routes did before gated handlers. */
    private static PacketReceiveHandler[] gated(Listener... listeners) {
        var handlers = new PacketReceiveHandler[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            Listener listener = listeners[i];
            PacketReceiveHandler<Object> scanned =
                    SCANNER.receiveHandlers(listener).getFirst().handler();
            handlers[i] = (event, player, packet) -> {
                if (shouldDispatch(listener)) scanned.handle(event, player, packet);
            };
        }
        return handlers;
    }

    private static PacketSendHandler[] gatedSend(Listener... listeners) {
        var handlers = new PacketSendHandler[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            Listener listener = listeners[i];
            PacketSendHandler<Object> scanned =
                    SCANNER.sendHandlers(listener).getFirst().handler();
            handlers[i] = (event, player, packet) -> {
                if (shouldDispatch(listener)) scanned.handle(event, player, packet);
            };
        }
        return handlers;
    }

    /** The same gate handed to the scanner, as check routes do now. */
    private static PacketReceiveHandler[] direct(Listener... listeners) {
        var handlers = new PacketReceiveHandler[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            Listener listener = listeners[i];
            handlers[i] = SCANNER.receiveHandlers(listener, () -> shouldDispatch(listener))
                    .getFirst()
                    .handler();
        }
        return handlers;
    }

    private static PacketSendHandler[] directSend(Listener... listeners) {
        var handlers = new PacketSendHandler[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            Listener listener = listeners[i];
            handlers[i] = SCANNER.sendHandlers(listener, () -> shouldDispatch(listener))
                    .getFirst()
                    .handler();
        }
        return handlers;
    }

    /** The shape of the check gate: a type test, then a player field read that is false for Java players. */
    private static boolean shouldDispatch(Object listener) {
        return !(listener instanceof Listener gated) || !gated.player.bedrock || gated.supported();
    }

    /** A player's movement platform, mutable as the real one is. */
    static final class BenchPlayer {
        boolean bedrock;
    }

    static final BenchPlayer PLAYER = new BenchPlayer();

    abstract static class Listener {
        final BenchPlayer player = PLAYER;
        long acc;
        boolean last;
        double prev;
        int count;

        boolean supported() {
            return getClass().isAnnotationPresent(Deprecated.class);
        }
    }

    static final class M00 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 3);
        }
    }

    static final class M01 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 4;
        }
    }

    static final class M02 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.onGround() != last) {
                last = m.onGround();
                acc += 5;
            }
        }
    }

    static final class M03 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            double d = m.y() - prev;
            prev = m.y();
            if (d > 0.42 + 6 * 1e-3) acc++;
        }
    }

    static final class M04 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (!m.hasRotation()) return;
            acc += Math.abs(m.pitch()) > 90f - 7 % 7 ? -1 : 7;
        }
    }

    static final class M05 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if ((++count & 15) == 8 % 16) acc += m.horizontalCollision() ? 1 : 8;
        }
    }

    static final class M06 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 9);
        }
    }

    static final class M07 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 10;
        }
    }

    static final class M08 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.onGround() != last) {
                last = m.onGround();
                acc += 11;
            }
        }
    }

    static final class M09 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            double d = m.y() - prev;
            prev = m.y();
            if (d > 0.42 + 12 * 1e-3) acc++;
        }
    }

    static final class M10 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (!m.hasRotation()) return;
            acc += Math.abs(m.pitch()) > 90f - 13 % 7 ? -1 : 13;
        }
    }

    static final class M11 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if ((++count & 15) == 14 % 16) acc += m.horizontalCollision() ? 1 : 14;
        }
    }

    static final class M12 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 15);
        }
    }

    static final class M13 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 16;
        }
    }

    static final class M14 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.onGround() != last) {
                last = m.onGround();
                acc += 17;
            }
        }
    }

    static final class M15 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            double d = m.y() - prev;
            prev = m.y();
            if (d > 0.42 + 18 * 1e-3) acc++;
        }
    }

    static final class M16 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (!m.hasRotation()) return;
            acc += Math.abs(m.pitch()) > 90f - 19 % 7 ? -1 : 19;
        }
    }

    static final class M17 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if ((++count & 15) == 20 % 16) acc += m.horizontalCollision() ? 1 : 20;
        }
    }

    static final class M18 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 21);
        }
    }

    static final class M19 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 22;
        }
    }

    static final class M20 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.onGround() != last) {
                last = m.onGround();
                acc += 23;
            }
        }
    }

    static final class M21 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            double d = m.y() - prev;
            prev = m.y();
            if (d > 0.42 + 24 * 1e-3) acc++;
        }
    }

    static final class M22 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (!m.hasRotation()) return;
            acc += Math.abs(m.pitch()) > 90f - 25 % 7 ? -1 : 25;
        }
    }

    static final class M23 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if ((++count & 15) == 26 % 16) acc += m.horizontalCollision() ? 1 : 26;
        }
    }

    static final class M24 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 27);
        }
    }

    static final class M25 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 28;
        }
    }

    static final class M26 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.onGround() != last) {
                last = m.onGround();
                acc += 29;
            }
        }
    }

    static final class M27 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            double d = m.y() - prev;
            prev = m.y();
            if (d > 0.42 + 30 * 1e-3) acc++;
        }
    }

    static final class M28 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (!m.hasRotation()) return;
            acc += Math.abs(m.pitch()) > 90f - 31 % 7 ? -1 : 31;
        }
    }

    static final class M29 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if ((++count & 15) == 32 % 16) acc += m.horizontalCollision() ? 1 : 32;
        }
    }

    static final class M30 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 33);
        }
    }

    static final class M31 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 34;
        }
    }

    static final class M32 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.onGround() != last) {
                last = m.onGround();
                acc += 35;
            }
        }
    }

    static final class M33 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            double d = m.y() - prev;
            prev = m.y();
            if (d > 0.42 + 36 * 1e-3) acc++;
        }
    }

    static final class M34 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (!m.hasRotation()) return;
            acc += Math.abs(m.pitch()) > 90f - 37 % 7 ? -1 : 37;
        }
    }

    static final class M35 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if ((++count & 15) == 38 % 16) acc += m.horizontalCollision() ? 1 : 38;
        }
    }

    static final class M36 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 39);
        }
    }

    static final class M37 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 40;
        }
    }

    static final class M38 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.onGround() != last) {
                last = m.onGround();
                acc += 41;
            }
        }
    }

    static final class M39 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            double d = m.y() - prev;
            prev = m.y();
            if (d > 0.42 + 42 * 1e-3) acc++;
        }
    }

    static final class M40 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (!m.hasRotation()) return;
            acc += Math.abs(m.pitch()) > 90f - 43 % 7 ? -1 : 43;
        }
    }

    static final class M41 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if ((++count & 15) == 44 % 16) acc += m.horizontalCollision() ? 1 : 44;
        }
    }

    static final class M42 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (m.hasPosition()) acc += (long) (m.x() * 45);
        }
    }

    static final class M43 extends Listener {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> e, CultPlayer player, ServerboundMovePlayer m) {
            if (e.isCancelled()) return;
            acc ^= Float.floatToRawIntBits(m.yaw()) + 46;
        }
    }

    static final class P0 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 5;
        }
    }

    static final class P1 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 6;
        }
    }

    static final class P2 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 7;
        }
    }

    static final class P3 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 8;
        }
    }

    static final class P4 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 9;
        }
    }

    static final class P5 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 10;
        }
    }

    static final class P6 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 11;
        }
    }

    static final class P7 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 12;
        }
    }

    static final class P8 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 13;
        }
    }

    static final class P9 extends Listener {
        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> e, CultPlayer player, ServerboundPong m) {
            acc += m.id() * 14;
        }
    }

    static final class S00 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 7;
        }
    }

    static final class S01 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 8;
        }
    }

    static final class S02 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 9;
        }
    }

    static final class S03 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 10;
        }
    }

    static final class S04 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 11;
        }
    }

    static final class S05 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 12;
        }
    }

    static final class S06 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 13;
        }
    }

    static final class S07 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 14;
        }
    }

    static final class S08 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 15;
        }
    }

    static final class S09 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 16;
        }
    }

    static final class S10 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 17;
        }
    }

    static final class S11 extends Listener {
        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> e, CultPlayer player, ClientboundPing m) {
            if (!e.isCancelled()) acc += m.id() ^ 18;
        }
    }
}
