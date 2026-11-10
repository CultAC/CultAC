package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.manager.tick.BackendTickEstimator;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.api.ResourceScope;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every backend burst end reaches the estimator once, in order, with its own flag and read time, at the read that
 * ended it; the estimator ends in the state the same burst ends give it when each is its own task.
 */
class VelocityBackendBurstDeliveryTest {
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
    /** Complete frames, a read that stops inside a frame, and the read that completes it. */
    private static final byte[][] READS = {
        {2, 0, 0}, {2, 0, 0, 1, 0}, {3, 0}, {0, 0}, {(byte) 0xAC}, {0x02}, new byte[300], {1, 7}, {4, 1, 2, 3, 4}
    };

    private Field context;
    private Object previous;

    @BeforeEach
    void saveContext() throws Exception {
        context = RuntimeEnvironment.class.getDeclaredField("context");
        context.setAccessible(true);
        previous = context.get(null);
    }

    @AfterEach
    void restoreContext() throws Exception {
        context.set(null, previous);
    }

    @Test
    void onTheOwnerEachBurstEndIsDeliveredAtItsRead() throws Exception {
        var scope = new CountingScope();
        var host = mock(HostContext.class);
        when(host.resources()).thenReturn(scope);
        context.set(null, host);
        try (var f = new Fixture(null)) {
            var log = new ArrayList<String>();
            List<Boolean> partial = new ArrayList<>();
            List<Long> reads = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (byte[] read : READS) {
                    int before = scope.accepted;
                    long start = advance();
                    f.backend.writeInbound(Unpooled.wrappedBuffer(read));
                    long end = System.nanoTime();
                    assertEquals(before + 1, scope.accepted, "one task per burst end");
                    boolean inside = f.observer().insideFrame();
                    partial.add(inside);
                    if (!inside) {
                        long sample = f.newestSample();
                        assertTrue(sample >= start && sample <= end, "recorded at its own read");
                        reads.add(sample);
                    }
                    f.connection.execute(() ->
                            log.add("model emitted=" + f.ticks.tickEndStats().emitted()));
                }
            }
            assertEquals(scope.accepted, scope.ran);
            assertEquals(reads.size(), f.ticks.tickEndStats().emitted());
            assertEquals(log, expectedLog(partial));
            assertSameState(replay(partial, reads), f.ticks);

            when(f.nativePlayer.getCurrentServer()).thenReturn(Optional.of(mock(ServerConnection.class)));
            int before = scope.accepted;
            f.backend.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 0}));
            assertEquals(before, scope.accepted, "another server's bursts are not this backend's");
        }
    }

    @Test
    void queuedBurstEndsKeepTheirOwnFlagAndTime() throws Exception {
        var owner = new DefaultEventExecutor();
        try (var f = new Fixture(owner)) {
            var gate = new CountDownLatch(1);
            owner.execute(() -> {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            List<Boolean> partial = new ArrayList<>();
            List<long[]> windows = new ArrayList<>();
            for (byte[] read : READS) {
                long start = advance();
                f.backend.writeInbound(Unpooled.wrappedBuffer(read));
                boolean inside = f.observer().insideFrame();
                partial.add(inside);
                if (!inside) windows.add(new long[] {start, System.nanoTime()});
            }
            gate.countDown();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            long[] samples = owner.submit(f::samples).get(5, TimeUnit.SECONDS);
            assertEquals(windows.size(), samples.length);
            List<Long> reads = new ArrayList<>();
            for (int i = 0; i < samples.length; i++) {
                assertTrue(samples[i] >= windows.get(i)[0] && samples[i] <= windows.get(i)[1], "burst " + i);
                reads.add(samples[i]);
            }
            var expected = replay(partial, reads);
            owner.submit(() -> assertSameState(expected, f.ticks)).get(5, TimeUnit.SECONDS);
        } finally {
            owner.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    /** The other model work sees every earlier burst end applied and no later one. */
    private static List<String> expectedLog(List<Boolean> partial) {
        var log = new ArrayList<String>();
        int emitted = 0;
        for (boolean inside : partial) {
            if (!inside) emitted++;
            log.add("model emitted=" + emitted);
        }
        return log;
    }

    /** The same burst ends, each as its own task. */
    private static BackendTickEstimator replay(List<Boolean> partial, List<Long> reads) {
        var estimator = new BackendTickEstimator(() -> {}, (task, delay) -> () -> {}, () -> 50_000_000L, () -> {});
        estimator.onGameTime(0, 0);
        var times = reads.iterator();
        for (boolean inside : partial) {
            long now = inside ? Long.MIN_VALUE : times.next();
            Runnable burst = () -> estimator.onBurstEnd(inside, now);
            burst.run();
        }
        return estimator;
    }

    private static void assertSameState(BackendTickEstimator expected, BackendTickEstimator actual) {
        for (Field field : BackendTickEstimator.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!field.getType().isPrimitive()
                    && !field.getType().isArray()
                    && !field.getType().isEnum()) continue;
            field.setAccessible(true);
            try {
                Object a = field.get(expected);
                Object b = field.get(actual);
                if (a instanceof long[] left) assertArrayEquals(left, (long[]) b, field.getName());
                else assertEquals(a, b, field.getName());
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        assertEquals(expected.tickEndStats(), actual.tickEndStats());
        assertEquals(expected.mode(), actual.mode());
    }

    /** Waits for the clock to move so consecutive reads have distinct times. */
    private static long advance() {
        long start = System.nanoTime();
        long now;
        while ((now = System.nanoTime()) == start) Thread.onSpinWait();
        return now;
    }

    private static final class Fixture implements AutoCloseable {
        final Player nativePlayer = mock(Player.class);
        final EmbeddedChannel client = new EmbeddedChannel();
        final EmbeddedChannel backend = new EmbeddedChannel();
        final CultConnection connection;
        final BackendTickEstimator ticks;

        Fixture(EventExecutor owner) {
            var server = mock(BackendServer.class);
            when(server.getConnection()).thenReturn(new BackendConnection(backend));
            when(nativePlayer.getCurrentServer()).thenReturn(Optional.of(server));
            var player = new VelocityPlayer(nativePlayer, mock(VelocitySenders.class));
            var adapter = new VelocityConnectionAdapter(
                    mock(ProxyServer.class), nativePlayer, player, owner == null ? client.eventLoop() : owner);
            connection = new CultConnection(
                    adapter,
                    client,
                    new PacketDispatcher(RUNTIME),
                    ignored -> owner == null ? null : new PacketOwner(owner, null));
            adapter.attach(connection);
            connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
            connection.resolveOwner();
            ticks = adapter.backendTicks();
            if (owner == null) ticks.onGameTime(0, 0);
            else owner.submit(() -> ticks.onGameTime(0, 0)).syncUninterruptibly();
            backend.runPendingTasks();
            assertNotNull(observer());
        }

        VelocityBackendTickObserver observer() {
            return (VelocityBackendTickObserver) backend.pipeline().get(VelocityBackendTickObserver.NAME);
        }

        long[] samples() throws Exception {
            int count = intField("phaseSampleCount");
            long[] all = (long[]) field("phaseSamples").get(ticks);
            return Arrays.copyOf(all, count);
        }

        long newestSample() throws Exception {
            int next = intField("phaseSampleNext");
            long[] all = (long[]) field("phaseSamples").get(ticks);
            return all[Math.floorMod(next - 1, all.length)];
        }

        private int intField(String name) throws Exception {
            return field(name).getInt(ticks);
        }

        private static Field field(String name) throws Exception {
            Field field = BackendTickEstimator.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        @Override
        public void close() {
            client.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    /** Velocity's server connection shape, as {@link VelocityBackendChannels} reads it. */
    public abstract static class BackendServer implements ServerConnection {
        public abstract BackendConnection getConnection();
    }

    public static final class BackendConnection {
        private final Channel channel;

        BackendConnection(Channel channel) {
            this.channel = channel;
        }

        public Channel getChannel() {
            return channel;
        }
    }

    /** Accepts work until closed and runs each accepted task once, as the host's scope does. */
    private static final class CountingScope implements ResourceScope {
        int accepted;
        int ran;

        @Override
        public Lease acquire() {
            return () -> {};
        }

        @Override
        public <T> T track(T value, Runnable cleanup) {
            return value;
        }

        @Override
        public Runnable guard(Runnable callback) {
            return callback;
        }

        @Override
        public Runnable once(Runnable callback) {
            accepted++;
            return new Once(callback);
        }

        @Override
        public void run(Runnable callback) {
            callback.run();
        }

        @Override
        public CompletionStage<Void> close() {
            throw new UnsupportedOperationException();
        }

        private final class Once implements Runnable, AutoCloseable {
            private Runnable callback;

            Once(Runnable callback) {
                this.callback = callback;
            }

            @Override
            public void run() {
                var action = callback;
                callback = null;
                if (action == null) return;
                ran++;
                action.run();
            }

            @Override
            public void close() {
                callback = null;
            }
        }
    }
}
