package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ClientTickEndTimeoutTest {
    private static final long MINUTE = TimeUnit.SECONDS.toNanos(60);
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void closesOneTickAtSixtySecondsAndReleasesConnectionTasksAndSelectionSnapshots() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.tick(STANDING, true);
            var timeout = f.player.clientTickEndTimeout;
            long ticks = f.player.packetStateData.acceptedClientTick;
            AtomicInteger tasks = new AtomicInteger();
            f.fixture.connection.afterClientTick(tasks::incrementAndGet);
            var move = f.move(STANDING, true);
            f.fixture.connection.forwarded(ServerboundPackets.MOVE_PLAYER, move.getPacket());
            f.player.serverOpenedInventoryThisTick = true;

            // Block prediction retains the pre-action selection geometry until the tick is closed.
            f.player.compensatedWorld.beginSelectionSnapshot();
            f.player.compensatedWorld.startPredicting();
            f.player.compensatedWorld.updateBlock(6, FLOOR_Y - 1, 6, BlockIds.AIR.defaultState());
            f.player.compensatedWorld.stopPredicting(0);
            Field originals = f.player.compensatedWorld.getClass().getDeclaredField("selectionOriginalStates");
            originals.setAccessible(true);
            var snapshot = (it.unimi.dsi.fastutil.longs.Long2IntMap) originals.get(f.player.compensatedWorld);
            assertFalse(snapshot.isEmpty());
            timeout.onTickEnd(0);

            timeout.poll(MINUTE - 1);
            assertEquals(ticks, f.player.packetStateData.acceptedClientTick);
            assertEquals(0, tasks.get());
            assertTrue(f.fixture.connection.midClientTick());

            timeout.poll(MINUTE);
            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
            assertEquals(1, tasks.get());
            assertFalse(f.fixture.connection.midClientTick());
            assertTrue(snapshot.isEmpty());
            assertFalse(f.player.serverOpenedInventoryThisTick);
            assertFalse(f.player.packetStateData.receivedMovementThisClientTick);
            assertFalse(timeout.needsPoll(MINUTE));

            timeout.poll(MINUTE);
            timeout.poll(2 * MINUTE - 1);
            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
            timeout.poll(2 * MINUTE);
            assertEquals(ticks + 2, f.player.packetStateData.acceptedClientTick);
            assertEquals(1, tasks.get());
            assertTrue(f.fixture.channel.isActive());
        }
    }

    @Test
    void aLongStallClosesOnlyOneTickAndDoesNotCatchUp() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.tick(STANDING, true);
            f.player.clientTickEndTimeout.onTickEnd(0);
            long ticks = f.player.packetStateData.acceptedClientTick;

            f.player.clientTickEndTimeout.poll(100 * MINUTE);
            f.player.clientTickEndTimeout.poll(100 * MINUTE);

            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
            assertFalse(f.player.clientTickEndTimeout.needsPoll(101 * MINUTE - 1));
        }
    }

    @Test
    void aRealTickEndRestartsTheDeadline() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.player.clientTickEndTimeout.onTickEnd(System.nanoTime() - MINUTE);
            f.tickEnd();
            long now = System.nanoTime();
            long ticks = f.player.packetStateData.acceptedClientTick;

            f.player.clientTickEndTimeout.poll(now + TimeUnit.SECONDS.toNanos(59));
            assertEquals(ticks, f.player.packetStateData.acceptedClientTick);
            f.player.clientTickEndTimeout.poll(now + MINUTE);
            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
        }
    }

    @Test
    void answeringTransactionsDoesNotPostponeRecovery() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.tick(STANDING, true);
            f.player.clientTickEndTimeout.onTickEnd(0);
            long ticks = f.player.packetStateData.acceptedClientTick;

            f.pongThrough(f.player.sendTrailingTransaction());
            f.player.clientTickEndTimeout.poll(MINUTE);

            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
        }
    }

    @Test
    void recoveryAlsoRunsInObserveOnlyMode() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = RecordReceiveFixture.observeOnly(true)) {
            f.place(STANDING, Vec3.ZERO);
            f.player.clientTickEndTimeout.onTickEnd(0);
            long ticks = f.player.packetStateData.acceptedClientTick;

            f.player.clientTickEndTimeout.poll(MINUTE);

            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
            assertTrue(f.fixture.channel.isActive());
        }
    }

    @Test
    void configurationPausesRecoveryAndReturningToPlayStartsAFreshDeadline() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.player.clientTickEndTimeout.onTickEnd(0);
            long ticks = f.player.packetStateData.acceptedClientTick;
            f.fixture.phase(ConnectionPhase.CONFIGURATION);
            assertTrue(f.player.clientTickEndTimeout.needsPoll(1));
            f.player.clientTickEndTimeout.poll(1);
            assertFalse(f.player.clientTickEndTimeout.needsPoll(5 * MINUTE));
            f.player.clientTickEndTimeout.poll(5 * MINUTE);
            assertEquals(ticks, f.player.packetStateData.acceptedClientTick);

            f.fixture.phase(ConnectionPhase.PLAY);
            assertTrue(f.player.clientTickEndTimeout.needsPoll(5 * MINUTE));
            f.player.clientTickEndTimeout.poll(5 * MINUTE);
            f.player.clientTickEndTimeout.poll(6 * MINUTE - 1);
            assertEquals(ticks, f.player.packetStateData.acceptedClientTick);
            f.player.clientTickEndTimeout.poll(6 * MINUTE);
            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
        }
    }

    @Test
    void bedrockAndClosedConnectionsDoNotReceiveSyntheticJavaTicks() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.clientTickEndTimeout.onTickEnd(0);
            long ticks = f.player.packetStateData.acceptedClientTick;
            f.player.movementPlatform = MovementPlatform.BEDROCK;
            f.player.clientTickEndTimeout.poll(MINUTE);
            assertEquals(ticks, f.player.packetStateData.acceptedClientTick);

            f.player.movementPlatform = MovementPlatform.JAVA;
            f.fixture.channel.close();
            f.player.clientTickEndTimeout.poll(2 * MINUTE);
            assertEquals(ticks, f.player.packetStateData.acceptedClientTick);
        }
    }

    @Test
    void theExistingWatchdogRunsRecoveryEvenWithoutIncomingPackets() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.nettyScheduler.removeScheduler();
            f.place(STANDING, Vec3.ZERO);
            f.tick(STANDING, true);
            f.player.clientTickEndTimeout.onTickEnd(System.nanoTime() - MINUTE);
            long ticks = f.player.packetStateData.acceptedClientTick;
            AtomicInteger tasks = new AtomicInteger();
            f.fixture.connection.afterClientTick(() -> {
                assertTrue(f.fixture.user.getPacketExecutor().inEventLoop());
                tasks.incrementAndGet();
            });
            var watchdog = NettyScheduler.class.getDeclaredMethod("watchdog");
            watchdog.setAccessible(true);
            watchdog.invoke(f.player.nettyScheduler);
            f.fixture.channel.runPendingTasks();

            assertEquals(ticks + 1, f.player.packetStateData.acceptedClientTick);
            assertEquals(1, tasks.get());
        }
    }

    @Test
    void syntheticRecoveryDoesNotReportAClientPacketOrCutARecordedFrame() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.tick(STANDING, true);
            PlayerObserver observer = Mockito.mock(PlayerObserver.class);
            f.player.observer = observer;
            long received = f.player.clientTickEnds.get();
            f.player.clientTickEndTimeout.onTickEnd(0);

            f.player.clientTickEndTimeout.poll(MINUTE);

            assertEquals(received, f.player.clientTickEnds.get());
            Mockito.verify(observer, Mockito.never()).tickEnded();
            Mockito.verify(observer, Mockito.never()).received(Mockito.any(), Mockito.anyBoolean());
            assertFalse(f.player.checkManager.isDispatchingTickEnd());

            f.tickEnd();
            Mockito.verify(observer).tickEnded();
        }
    }
}
