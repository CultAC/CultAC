package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.host.ScopedResources;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link CultConnection#execute} and {@link CultConnection#executeLater} against the task shape they had when every
 * task was wrapped in a {@code runInModel} call: same runs, order, context, leases, failures and close behaviour.
 */
class ConnectionExecuteEquivalenceTest {
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));

    enum Platform {
        NONE,
        DEFAULT_MODEL,
        OWN_MODEL
    }

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

    @ParameterizedTest
    @EnumSource(Platform.class)
    void onTheOwnerTasksRunAlikeBeforeDuringAndAfterClose(Platform platform) throws Exception {
        var wrapped = inLoop(
                platform,
                ConnectionExecuteEquivalenceTest::wrappedExecute,
                ConnectionExecuteEquivalenceTest::wrappedExecuteLater);
        var current = inLoop(platform, CultConnection::execute, CultConnection::executeLater);
        assertEquals(wrapped, current);
        assertTrue(current.contains("a ran context=true leases=1"), current::toString);
        assertTrue(current.contains("d threw StateFailure: Anticheat session has ended"), current::toString);
    }

    @ParameterizedTest
    @EnumSource(Platform.class)
    void queuedTasksRunAlikeAcrossClose(Platform platform) throws Exception {
        var wrapped = queued(
                platform,
                ConnectionExecuteEquivalenceTest::wrappedExecute,
                ConnectionExecuteEquivalenceTest::wrappedExecuteLater);
        var current = queued(platform, CultConnection::execute, CultConnection::executeLater);
        assertEquals(wrapped, current);
        assertTrue(current.contains("closed before queued work=false"), current::toString);
        assertTrue(current.contains("a ran context=true leases=3"), current::toString);
    }

    /** Owner-thread work: plain, failing and null tasks, a later task, then submissions after close. */
    private List<String> inLoop(
            Platform kind,
            BiConsumer<CultConnection, Runnable> execute,
            BiConsumer<CultConnection, Runnable> executeLater)
            throws Exception {
        var log = new ArrayList<String>();
        var loader = new ClassLoader(getClass().getClassLoader()) {};
        var scope = new ScopedResources(loader);
        bindHost(scope);
        var channel = new EmbeddedChannel();
        var connection = connection(kind, channel, null, log);
        try {
            submit(log, "a", () -> execute.accept(connection, task(log, "a", scope, loader)));
            submit(
                    log,
                    "b",
                    () -> execute.accept(connection, () -> {
                        log.add("b running leases=" + scope.inFlight());
                        throw new IllegalArgumentException("b failed");
                    }));
            log.add("after b leases=" + scope.inFlight());
            submit(log, "null", () -> execute.accept(connection, null));
            log.add("after null leases=" + scope.inFlight());
            submit(log, "c", () -> executeLater.accept(connection, task(log, "c", scope, loader)));
            log.add("c pending leases=" + scope.inFlight());
            var closed = scope.close().toCompletableFuture();
            log.add("closed with later work=" + closed.isDone());
            channel.runPendingTasks();
            log.add("closed after later work=" + closed.isDone());
            submit(log, "d", () -> execute.accept(connection, task(log, "d", scope, loader)));
            submit(log, "e", () -> executeLater.accept(connection, task(log, "e", scope, loader)));
            channel.runPendingTasks();
            closed.get(5, TimeUnit.SECONDS);
            log.add("leases=" + scope.inFlight());
        } finally {
            channel.finishAndReleaseAll();
        }
        return log;
    }

    /** Work from another thread: queued tasks that the owner reaches only after close begins. */
    private List<String> queued(
            Platform kind,
            BiConsumer<CultConnection, Runnable> execute,
            BiConsumer<CultConnection, Runnable> executeLater)
            throws Exception {
        var log = Collections.synchronizedList(new ArrayList<String>());
        var loader = new ClassLoader(getClass().getClassLoader()) {};
        var scope = new ScopedResources(loader);
        bindHost(scope);
        // A transport loop, which logs a task's failure and keeps running.
        var group = new NioEventLoopGroup(1);
        var owner = group.next();
        var channel = new EmbeddedChannel();
        try {
            var connection = connection(kind, channel, owner, log);
            var gate = new CountDownLatch(1);
            owner.execute(() -> await(gate));
            submit(log, "a", () -> execute.accept(connection, task(log, "a", scope, loader)));
            submit(
                    log,
                    "b",
                    () -> execute.accept(connection, () -> {
                        log.add("b running");
                        throw new IllegalStateException("b failed");
                    }));
            submit(log, "c", () -> executeLater.accept(connection, task(log, "c", scope, loader)));
            log.add("queued leases=" + scope.inFlight());
            var closed = scope.close().toCompletableFuture();
            log.add("closed before queued work=" + closed.isDone());
            submit(log, "d", () -> execute.accept(connection, task(log, "d", scope, loader)));
            gate.countDown();
            closed.get(5, TimeUnit.SECONDS);
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            log.add("leases=" + scope.inFlight());
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            channel.finishAndReleaseAll();
        }
        return new ArrayList<>(log);
    }

    private void bindHost(ScopedResources scope) throws Exception {
        var host = mock(HostContext.class);
        when(host.resources()).thenReturn(scope);
        context.set(null, host);
    }

    private static CultConnection connection(
            Platform kind, EmbeddedChannel channel, EventExecutor owner, List<String> log) {
        PlatformConnection platform = switch (kind) {
            case NONE -> null;
            case DEFAULT_MODEL -> new DefaultModelPlatform();
            case OWN_MODEL ->
                new DefaultModelPlatform() {
                    @Override
                    public void runInModel(Runnable task) {
                        log.add("model enter");
                        task.run();
                        log.add("model exit");
                    }
                };
        };
        var connection = new CultConnection(
                platform,
                channel,
                new TestTransportRoutes(RUNTIME).dispatcher,
                ignored -> owner == null ? null : new PacketOwner(owner, null));
        connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
        connection.resolveOwner();
        return connection;
    }

    private static Runnable task(List<String> log, String name, ScopedResources scope, ClassLoader loader) {
        return () -> log.add(name + " ran context=" + (Thread.currentThread().getContextClassLoader() == loader)
                + " leases=" + scope.inFlight());
    }

    private static void submit(List<String> log, String name, Runnable submission) {
        try {
            submission.run();
            log.add(name + " submitted");
        } catch (RuntimeException | Error failure) {
            log.add(name + " threw " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@link CultConnection#execute} as it was with every task wrapped. */
    private static void wrappedExecute(CultConnection connection, Runnable task) {
        var host = RuntimeEnvironment.host();
        var accepted = host.resources().once(() -> connection.runInModel(task));
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

    /** {@link CultConnection#executeLater} as it was with every task wrapped. */
    private static void wrappedExecuteLater(CultConnection connection, Runnable task) {
        Runnable accepted = RuntimeEnvironment.once(() -> connection.runInModel(task));
        try {
            connection.owner().execute(accepted);
        } catch (RuntimeException | Error failure) {
            if (accepted instanceof AutoCloseable cancellable) {
                try {
                    cancellable.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static class DefaultModelPlatform implements PlatformConnection {
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
}
