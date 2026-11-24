package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * NegativeTimer charges a client for the time its pongs prove it lived through and its tick ends did not cover. The
 * fixtures run in no wall-clock time, so a test takes the lost time out of the balance itself.
 */
class NegativeTimerTest {
    private static final Vec3 GROUND = new Vec3(.5, JavaTickFixture.FLOOR_Y, .5);
    private static final long SECOND = 1_000_000_000L;
    private static final long ONE_CLIENT_TICK = 50_000_000L;

    /** A client at half speed answers a ping every frame, so no run of its ticks is long enough to forgive. */
    @Test
    void aClientAtHalfSpeedThatAnswersItsPingsFlags() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            tickAfterEachPong(f, check, 2 * ONE_CLIENT_TICK);
            assertTrue(check.getViolations() > 0, "half speed never flagged");
        }
    }

    @Test
    void aClientAtFullSpeedNeverFlags() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            tickAfterEachPong(f, check, ONE_CLIENT_TICK);
            assertEquals(0, check.getViolations());
        }
    }

    /** Minecraft#runTick runs at most ten ticks a frame, with no pong between them, and drops the rest it owed. */
    @Test
    void aFrameOfTicksWithNoPongForgivesTheLostTime() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            answerOwnPing(f);
            behind(check, 5 * SECOND);
            for (int tick = 0; tick < 8; tick++) f.tickEnd();
            assertTrue(balance(check) < System.nanoTime(), "eight ticks forgave the lost time");
            f.tickEnd();
            assertTrue(balance(check) > System.nanoTime(), "the ninth tick kept the lost time");
        }
    }

    @Test
    void aPongBetweenTheTicksKeepsTheLostTime() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            behind(check, 5 * SECOND);
            for (int tick = 0; tick < 20; tick++) {
                if (tick % 8 == 0) answerOwnPing(f);
                f.tickEnd();
            }
            assertTrue(balance(check) < System.nanoTime(), "ticks around a pong forgave the lost time");
        }
    }

    /** Minecraft#getTickTargetMillis is max(50, 1000 / rate) while the level ticks normally. */
    @Test
    void aServerBelowTwentyTpsNeverFlags() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            f.player.packetStateData.serverTickRate = 10.0F;
            assertRestartsWithoutAFlag(f, check);
        }
    }

    @Test
    void anAcceptedTeleportRestartsTheBalance() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            behind(check, 5 * SECOND);
            var packet = new ServerboundAcceptTeleportation(1, new Vec3d(GROUND.x, GROUND.y, GROUND.z), 0, 0);

            var rejected = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            rejected.setCancelled(true);
            check.onAcceptTeleportation(rejected, f.player, packet);
            assertTrue(balance(check) < System.nanoTime(), "an acceptance of no teleport restarted the balance");

            check.onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
            assertTrue(balance(check) > System.nanoTime(), "the accepted teleport kept the lost time");
        }
    }

    @Test
    void aClientProvenBehindFlagsOnItsNextPrediction() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            provenBehind(check);
            f.tick(GROUND, true);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void theCheckIsSilentUntilExperimentalChecksAreOn() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            f.player.setExperimentalChecks(false);
            provenBehind(check);
            f.tick(GROUND, true);
            assertEquals(0, check.getViolations());
        }
    }

    /** A client proven far behind is not judged: the balance restarts instead. */
    private static void assertRestartsWithoutAFlag(JavaTickFixture f, NegativeTimerCheck check) throws Exception {
        provenBehind(check);
        f.tick(GROUND, true);
        assertEquals(0, check.getViolations());
        assertTrue(balance(check) > System.nanoTime(), "the balance did not restart");
    }

    /**
     * Eight client ticks, each after a frame that answered a ping, with {@code wallTime} passing for each. The client
     * starts most of its drift behind, so the ticks decide whether it falls out of it.
     */
    private static void tickAfterEachPong(JavaTickFixture f, NegativeTimerCheck check, long wallTime) throws Exception {
        behind(check, 900_000_000L);
        for (int tick = 0; tick < 8; tick++) {
            answerOwnPing(f);
            loseTime(check, wallTime);
            f.tick(GROUND, true);
        }
    }

    private static JavaTickFixture connect() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.fixture.registrar.registerOwn(PacketManager::registerListeners);
        return f;
    }

    private static NegativeTimerCheck experimental(JavaTickFixture f) throws Exception {
        f.player.setExperimentalChecks(true);
        f.place(GROUND, Vec3.ZERO);
        NegativeTimerCheck check = f.player.checkManager.getCheck(NegativeTimerCheck.class);
        assertFalse(check.isSwitchedOff(), "NegativeTimer is switched off");
        return check;
    }

    /** Writes one CultAC ping and answers it through the channel, as the client's next frame would. */
    private static void answerOwnPing(JavaTickFixture f) {
        f.player.sendTransaction();
        f.fixture.channel.flush();
        f.fixture.channel.runPendingTasks();
        int pingType = f.fixture
                .data
                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                .id("minecraft:ping");
        ByteBuf frame;
        while ((frame = f.fixture.channel.readOutbound()) != null) {
            try {
                if (Wire.readVarInt(frame) != pingType) continue;
                int id = frame.readInt();
                if (id >= 0) continue;
                f.fixture.channel.writeInbound(f.fixture.pong(id));
                ByteBuf forwarded = f.fixture.channel.readInbound();
                if (forwarded != null) forwarded.release();
            } finally {
                frame.release();
            }
        }
    }

    /** The client's clock is proven to now, and its ticks cover none of the last minute. */
    private static void provenBehind(NegativeTimerCheck check) throws Exception {
        JavaTickFixture.setField(AbstractTimerCheck.class, check, "knownPlayerClockTime", System.nanoTime());
        behind(check, 60 * SECOND);
    }

    private static void behind(NegativeTimerCheck check, long nanos) throws Exception {
        JavaTickFixture.setField(AbstractTimerCheck.class, check, "timerBalanceRealTime", System.nanoTime() - nanos);
    }

    private static void loseTime(NegativeTimerCheck check, long nanos) throws Exception {
        JavaTickFixture.setField(AbstractTimerCheck.class, check, "timerBalanceRealTime", balance(check) - nanos);
    }

    private static long balance(NegativeTimerCheck check) throws Exception {
        Field field = AbstractTimerCheck.class.getDeclaredField("timerBalanceRealTime");
        field.setAccessible(true);
        return field.getLong(check);
    }
}
