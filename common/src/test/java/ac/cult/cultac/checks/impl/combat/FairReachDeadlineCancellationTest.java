package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.value.Vec3d;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FairReachDeadlineCancellationTest {
    private static final int TARGET = 3000;

    @Test
    void transactionWinCancelsTheDeadlineBeforeItCanEnterTheModel() {
        try (Fixture f = new Fixture()) {
            f.fairReach.max_ping_compensation = 0;
            f.move(1);
            f.acknowledge();
            assertEquals(1, f.target().targetPosition.x);
            f.target().lerpTicks = 0;
            f.platform.modelEntries = 0;

            f.channel.runScheduledPendingTasks();

            assertEquals(0, f.platform.modelEntries, "an acknowledged move leaves no timeout model callback");
            assertEquals(0, f.target().lerpTicks, "cancelling does not restart interpolation");
        }
    }

    @Test
    void deadlineWinStillAppliesExactlyOnce() {
        try (Fixture f = new Fixture()) {
            f.fairReach.max_ping_compensation = 0;
            f.move(1);
            f.platform.modelEntries = 0;
            f.channel.runScheduledPendingTasks();
            assertEquals(1, f.platform.modelEntries);
            assertEquals(1, f.target().targetPosition.x);
            assertEquals(3, f.target().lerpTicks);

            f.target().lerpTicks = 0;
            f.acknowledge();
            f.channel.runScheduledPendingTasks();
            assertEquals(0, f.target().lerpTicks, "the later receipt must not restart interpolation");
        }
    }

    @Test
    void alreadyAnsweredProofDoesNotScheduleADeadline() {
        try (Fixture f = new Fixture()) {
            CapturedDeadline timer = f.captureDeadline(null);
            f.player.lastTransactionReceived.set(1);
            f.move(1);

            assertEquals(1, f.target().targetPosition.x);
            verify(timer.executor, never()).schedule(any(Callable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void aDequeuedDeadlineAfterTheReceiptDoesNotEnterTheModel() throws Exception {
        try (Fixture f = new Fixture()) {
            CapturedDeadline timer = f.captureDeadline(null);
            f.move(1);
            f.acknowledge();
            verify(timer.future).cancel(false);
            f.target().lerpTicks = 0;
            f.platform.modelEntries = 0;

            timer.callbacks.getFirst().call();

            assertEquals(0, f.platform.modelEntries, "a dequeued callback checks whether its proof already won");
            assertEquals(0, f.target().lerpTicks);
        }
    }

    @Test
    void receiptBeforeScheduleReturnsCancelsTheReturnedFuture() throws Exception {
        try (Fixture f = new Fixture()) {
            CapturedDeadline timer = f.captureDeadline(ignored -> f.acknowledge());
            f.move(1);
            verify(timer.future).cancel(false);
            assertEquals(1, f.target().targetPosition.x);
            f.target().lerpTicks = 0;
            timer.callbacks.getFirst().call();
            assertEquals(0, f.target().lerpTicks);
        }
    }

    @Test
    void immediateDeadlineBeforeScheduleReturnsStillAppliesAndCancelsTheFuture() throws Exception {
        try (Fixture f = new Fixture()) {
            CapturedDeadline timer = f.captureDeadline(task -> {
                try {
                    task.call();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            f.move(1);
            verify(timer.future).cancel(false);
            assertEquals(1, f.target().targetPosition.x);
            f.target().lerpTicks = 0;
            f.acknowledge();
            timer.callbacks.getFirst().call();
            assertEquals(0, f.target().lerpTicks);
        }
    }

    @Test
    void rejectedDeadlineSchedulingDoesNotLoseTheRegisteredTransaction() {
        try (Fixture f = new Fixture()) {
            f.captureDeadline(ignored -> {
                throw new RejectedExecutionException("executor stopped");
            });
            assertThrows(RejectedExecutionException.class, () -> f.move(1));
            assertEquals(0, f.target().targetPosition.x);
            f.acknowledge();
            assertEquals(1, f.target().targetPosition.x);
        }
    }

    @Test
    void overdueDeadlinesKeepTheirOrderRelativeToAnotherExecutorTask() {
        try (Fixture f = new Fixture()) {
            f.fairReach.max_ping_compensation = 10;
            f.move(1);
            List<Double> observed = new ArrayList<>();
            f.channel.eventLoop().schedule(() -> observed.add(f.target().targetPosition.x), 15, TimeUnit.MILLISECONDS);
            f.fairReach.max_ping_compensation = 20;
            f.move(2);
            f.channel.advanceTimeBy(30, TimeUnit.MILLISECONDS);
            f.channel.runScheduledPendingTasks();

            assertEquals(List.of(1.0), observed, "a delayed executor must not batch a later move ahead of a reader");
            assertEquals(2, f.target().targetPosition.x);
            f.target().lerpTicks = 0;
            f.acknowledge();
            assertEquals(0, f.target().lerpTicks);
        }
    }

    private static final class CapturedDeadline {
        final EventExecutor executor = Mockito.mock(EventExecutor.class);
        final ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
        final List<Callable<?>> callbacks = new ArrayList<>();
    }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final CountingPlatform platform = new CountingPlatform();
        final CultConnection connection;
        final CultPlayer player;
        final FairReach fairReach;
        EventExecutor owner;

        Fixture() {
            OfflineCultTestBootstrap.installConfig();
            channel.freezeTime();
            connection = new CultConnection(
                    platform,
                    channel,
                    CultAPI.INSTANCE.getNetworkManager().dispatcher(),
                    ignored -> new PacketOwner(owner, null));
            for (PacketDirection direction : PacketDirection.values())
                connection.phase(direction, ConnectionPhase.PLAY);
            User user = new User(new User.Profile(UUID.randomUUID(), "FairReach_Deadline"), connection);
            player = new CultPlayer(user);
            user.bind(Mockito.mock(PlatformPlayer.class));
            fairReach = player.checkManager.getListener(FairReach.class);
            fairReach.onAddEntity(
                    null, player, new ClientboundAddEntity(TARGET, "minecraft:player", new Vec3d(0, 64, 0), 0, 0, 0));
        }

        void move(double x) {
            fairReach.handleEntityMove(TARGET, x, 64, 0, FairReach.PositionUpdate.RELATIVE);
        }

        void acknowledge() {
            player.lastTransactionReceived.set(player.lastTransactionSent.get() + 1);
            player.latencyUtils.handleNettySyncTransaction(player.lastTransactionReceived.get());
        }

        FairReach.FairReachEntity target() {
            return fairReach.targetPlayers.get(TARGET);
        }

        @SuppressWarnings("unchecked")
        CapturedDeadline captureDeadline(Consumer<Callable<?>> whileScheduling) {
            CapturedDeadline timer = new CapturedDeadline();
            Mockito.when(timer.executor.inEventLoop()).thenReturn(true);
            Mockito.doAnswer(invocation -> {
                        Callable<?> task = invocation.getArgument(0);
                        timer.callbacks.add(task);
                        if (whileScheduling != null) whileScheduling.accept(task);
                        return timer.future;
                    })
                    .when(timer.executor)
                    .schedule(any(Callable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
            owner = timer.executor;
            connection.resolveOwner();
            return timer;
        }

        @Override
        public void close() {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static final class CountingPlatform implements PlatformConnection {
        int modelEntries;

        @Override
        public User.Profile authenticatedProfile() {
            return null;
        }

        @Override
        public PlayerBinding playerBinding() {
            return null;
        }

        @Override
        public ProtocolVersion getObservedProtocol() {
            return ProtocolVersion.V1_21_11;
        }

        @Override
        public ProtocolVersion getClientProtocol() {
            return ProtocolVersion.V1_21_11;
        }

        @Override
        public void runInModel(Runnable task) {
            modelEntries++;
            task.run();
        }

        @Override
        public void disconnect(Component reason) {}

        @Override
        public void sendMessage(Component message) {}
    }
}
