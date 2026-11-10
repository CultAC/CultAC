package ac.cult.cultac.network;

import ac.cult.cultac.manager.tick.BackendTickEstimator;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.api.ResourceScope;
import ac.cult.reload.host.ScopedResources;
import com.sun.management.ThreadMXBean;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Allocated bytes and thread CPU per owner-thread {@link CultConnection#execute} under a host scope, and per backend
 * burst end handed to the tick estimator: the wrapped task shape against the current one, interleaved. Runs only with
 * {@code CULT_EXECUTE_BENCH} set; results go to stdout and {@code build/execute-task-bench.txt} (or
 * {@code CULT_BENCH_OUT}).
 */
class ExecuteTaskBenchTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
    /** Large enough that a 15.6 ms thread-clock tick stays under 2 ns/call. */
    private static final int BATCH = 10_000_000;

    @Test
    void executeTaskBench() throws Exception {
        Assumptions.assumeTrue(System.getenv("CULT_EXECUTE_BENCH") != null);
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("CULT_EXECUTE_BENCH_REPS", "9"));
        Field context = RuntimeEnvironment.class.getDeclaredField("context");
        context.setAccessible(true);
        Object previous = context.get(null);
        var channel = new EmbeddedChannel();
        try {
            context.set(null, hostWith(new ScopedResources(ExecuteTaskBenchTest.class.getClassLoader())));
            var connection = new CultConnection(
                    new DefaultModelPlatform(), channel, new TestTransportRoutes(RUNTIME).dispatcher, ignored -> null);
            var ticks = new BackendTickEstimator(() -> {}, (task, delay) -> () -> {}, () -> 50_000_000L, () -> {});
            long[] sink = new long[1];
            Runnable task = () -> sink[0]++;
            var reused = new ReusedBurst(connection, ticks);

            var lines = new StringBuilder();
            lines.append(compare(
                    "execute (bound task)",
                    repetitions,
                    i -> wrappedExecute(connection, task),
                    i -> connection.execute(task)));
            lines.append(compare(
                    "burst end",
                    repetitions,
                    i -> {
                        boolean partial = (i & 7) == 7;
                        long now = System.nanoTime();
                        wrappedExecute(connection, () -> ticks.onBurstEnd(partial, now));
                    },
                    i -> reused.execute((i & 7) == 7, System.nanoTime())));
            System.out.print(lines);
            write(lines.toString());
            if (sink[0] == 0) throw new AssertionError();
        } finally {
            context.set(null, previous);
            channel.finishAndReleaseAll();
        }
    }

    private interface Call {
        void run(int i);
    }

    private static String compare(String name, int repetitions, Call wrapped, Call current) {
        double[][] base = new double[2][repetitions];
        double[][] next = new double[2][repetitions];
        for (int r = -5; r < repetitions; r++) {
            measure(wrapped, base, r);
            measure(current, next, r);
        }
        return line(name + " wrapped", base) + line(name + " current", next);
    }

    private static void measure(Call call, double[][] into, int round) {
        long allocated = THREADS.getCurrentThreadAllocatedBytes();
        long cpu = THREADS.getCurrentThreadCpuTime();
        for (int i = 0; i < BATCH; i++) call.run(i);
        long cpuUsed = THREADS.getCurrentThreadCpuTime() - cpu;
        long bytes = THREADS.getCurrentThreadAllocatedBytes() - allocated;
        if (round < 0) return;
        into[0][round] = bytes / (double) BATCH;
        into[1][round] = cpuUsed / (double) BATCH;
    }

    private static String line(String name, double[][] values) {
        double[] bytes = values[0].clone();
        double[] nanos = values[1].clone();
        Arrays.sort(bytes);
        Arrays.sort(nanos);
        return String.format(
                Locale.ROOT,
                "%-28s %7.1f B/call (min %7.1f max %7.1f)  %7.1f ns/call (min %7.1f max %7.1f)  %d reps%n",
                name,
                bytes[bytes.length / 2],
                bytes[0],
                bytes[bytes.length - 1],
                nanos[nanos.length / 2],
                nanos[0],
                nanos[nanos.length - 1],
                bytes.length);
    }

    /** {@link CultConnection#execute} as it was with every task wrapped. */
    private static void wrappedExecute(CultConnection connection, Runnable task) {
        var accepted = RuntimeEnvironment.host().resources().once(() -> connection.runInModel(task));
        try {
            if (connection.owner().inEventLoop()) accepted.run();
            else connection.owner().execute(accepted);
        } catch (RuntimeException | Error failure) {
            try {
                ((AutoCloseable) accepted).close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** The owner-thread burst end of the Velocity adapter: one instance handed to execute for every burst. */
    private static final class ReusedBurst implements Runnable {
        private final CultConnection connection;
        private final BackendTickEstimator ticks;
        private boolean partial;
        private long now;

        ReusedBurst(CultConnection connection, BackendTickEstimator ticks) {
            this.connection = connection;
            this.ticks = ticks;
        }

        void execute(boolean partial, long now) {
            this.partial = partial;
            this.now = now;
            connection.execute(this);
        }

        @Override
        public void run() {
            ticks.onBurstEnd(partial, now);
        }
    }

    private static final class DefaultModelPlatform implements PlatformConnection {
        @Override
        public User.Profile authenticatedProfile() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlayerBinding playerBinding() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disconnect(Component reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendMessage(Component message) {
            throw new UnsupportedOperationException();
        }
    }

    /** A host answering only {@code resources()}, without a mock's per-call interception. */
    private static HostContext hostWith(ResourceScope resources) {
        return (HostContext) Proxy.newProxyInstance(
                HostContext.class.getClassLoader(), new Class<?>[] {HostContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resources")) return resources;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void write(String text) throws IOException {
        String configured = System.getenv("CULT_BENCH_OUT");
        Path out = configured != null ? Path.of(configured) : Path.of("build", "execute-task-bench.txt");
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (var stream = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
            stream.print(text);
        }
    }
}
