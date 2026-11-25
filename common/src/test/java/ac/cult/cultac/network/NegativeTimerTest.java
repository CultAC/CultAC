package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * NegativeTimer charges a client for the time its pongs prove it lived through and its tick ends did not cover. The
 * fixtures run in no wall-clock time, so a test takes the lost time out of the balance itself.
 */
class NegativeTimerTest {
    private static final Vec3 GROUND = new Vec3(.5, JavaTickFixture.FLOOR_Y, .5);
    private static final long SECOND = 1_000_000_000L;
    private static final long ONE_CLIENT_TICK = 50_000_000L;
    private static final long MILLI = 1_000_000L;
    // LivingEntity#travel's resting fall on the ground.
    private static final Vec3 RESTING = new Vec3(0, -0.0784000015258789, 0);
    private static final int VEHICLE = 100;

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
            for (int tick = 0; tick < 9; tick++) f.tickEnd();
            assertTrue(balance(check) < System.nanoTime(), "nine ticks forgave the lost time");
            f.tickEnd();
            assertTrue(balance(check) > System.nanoTime(), "the tenth tick kept the lost time");
        }
    }

    /**
     * A tick end a timer rejects reaches no other check, yet the client ran that tick: it still counts toward the
     * frame. When the frame's last tick end is the rejected one, the next frame's pong ends the run.
     */
    @Test
    void aFrameCountsTheTickEndsATimerRejected() throws Exception {
        for (int[] rejected : new int[][] {{3, 7}, {7, 9}}) {
            try (var f = connect()) {
                NegativeTimerCheck check = experimental(f);
                answerOwnPing(f);
                behind(check, 5 * SECOND);
                for (int tick = 0; tick < 10; tick++) {
                    var end = f.event(
                            ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
                    if (tick == rejected[0] || tick == rejected[1]) end.setCancelled(true);
                    f.listener.processClientTickEndReceive(end, f.player);
                }
                if (rejected[1] == 9) {
                    assertTrue(balance(check) < System.nanoTime(), "the run ended before the next pong");
                    answerOwnPing(f);
                }
                assertTrue(balance(check) > System.nanoTime(), "a frame with rejected tick ends kept the lost time");
            }
        }
    }

    @Test
    void aPongBetweenTheTicksKeepsTheLostTime() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck check = experimental(f);
            behind(check, 5 * SECOND);
            // A longer run of tick ends with no position makes the fixture's predictions exempt.
            for (int tick = 0; tick < 16; tick++) {
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

    /**
     * Minecraft#runTick answers the stalled frame's pings, then runs ten of the ticks it owes. The pong proves only the
     * clock of the ping before it, so the stall is unproven until the tenth tick end restarts the balance.
     */
    @ParameterizedTest(name = "{0} stall {2} ms")
    @MethodSource("releasesAndStalls")
    void aStalledFrameThatRunsTenTicksNeverFlags(String release, ClientVersion version, long stallMs) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.tick(GROUND, true);
            elapse(f, check, stallMs * MILLI);
            answerOwnPing(f);
            for (int tick = 0; tick < 10; tick++) f.tick(GROUND, true);
            for (int tick = 0; tick < 20; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release);
        }
    }

    /** At 1.9 frames a second every frame owes 10.5 ticks, runs ten and drops the rest. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aClientAtTwoFramesASecondNeverFlags(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            for (int frame = 0; frame < 570; frame++) {
                answerOwnPing(f);
                elapse(f, check, 526 * MILLI);
                for (int tick = 0; tick < 10; tick++) f.tick(GROUND, true);
            }
            assertEquals(0, check.getViolations(), release);
        }
    }

    /**
     * A client clock that stopped (a suspended machine, a paused VM) owes no ticks when it resumes, so the lost time is
     * never forgiven. Accepted by decision for this check: once it exceeds both drifts it flags.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aClientClockThatStoppedFlagsOnlyPastBothDrifts(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.tick(GROUND, true);
            elapse(f, check, SECOND);
            for (int tick = 0; tick < 100; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release + ": one second");
        }
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.tick(GROUND, true);
            elapse(f, check, 5 * SECOND);
            for (int tick = 0; tick < 100; tick++) fullSpeedTick(f, check);
            assertTrue(check.getViolations() > 0, release + ": five seconds never flagged");
        }
    }

    /** A client clock running 2% slow: accepted by decision, it flags once the lost time exceeds both drifts. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aSlowClientClockFlagsOnceBehindBothDrifts(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            for (int tick = 0; tick < 4000; tick++) {
                answerOwnPing(f);
                elapse(f, check, ONE_CLIENT_TICK + MILLI);
                f.tick(GROUND, true);
            }
            assertTrue(check.getViolations() > 0, release);
        }
    }

    /** The balance a new check starts from carries the fields' 120 ms drift: a join losing less is not judged. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aJoinThatLosesLessThanItsStartingMarginDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = experimental(f);
            JavaTickFixture.setField(
                    AbstractTimerCheck.class, check, "timerBalanceRealTime", System.nanoTime() + 120 * MILLI);
            elapse(f, check, 1250 * MILLI);
            for (int tick = 0; tick < 3; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release);
        }
    }

    /** Minecraft#getTickTargetMillis is 50 ms while frozen and at any rate above 20. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aFrozenServerAndARateAboveTwentyNeverFlag(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.player.packetStateData.serverTicksFrozen = true;
            for (int tick = 0; tick < 200; tick++) fullSpeedTick(f, check);
            f.player.packetStateData.serverTicksFrozen = false;
            f.player.packetStateData.serverTickRate = 40.0F;
            for (int tick = 0; tick < 200; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aSlotStateChangeBetweenATickEndAndThePingNeverFlags(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            var packet = ServerboundPackets.CONTAINER_SLOT_STATE_CHANGED.opaqueValue();
            for (int tick = 0; tick < 400; tick++) {
                check.onContainerSlotStateChanged(
                        f.event(ServerboundPackets.CONTAINER_SLOT_STATE_CHANGED, packet), f.player, packet);
                fullSpeedTick(f, check);
            }
            assertEquals(0, check.getViolations(), release);
        }
    }

    /**
     * A timer rejecting two of a stalled frame's ten tick ends: they still count toward the frame. When the last ones
     * are rejected, the next frame's pong ends the run.
     */
    @ParameterizedTest(name = "{0} rejected {2},{3}")
    @MethodSource("releasesAndRejectedTicks")
    void aStalledFrameWithTwoRejectedTickEndsNeverFlags(String release, ClientVersion version, int first, int second)
            throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.tick(GROUND, true);
            elapse(f, check, 5 * SECOND);
            answerOwnPing(f);
            for (int tick = 0; tick < 10; tick++) {
                f.move(GROUND, true);
                var end = f.event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
                if (tick == first || tick == second) end.setCancelled(true);
                f.listener.processClientTickEndReceive(end, f.player);
            }
            // The next frame's pong; its first accepted tick end closes the tick the rejected one left open.
            answerOwnPing(f);
            f.tickEnd();
            for (int tick = 0; tick < 100; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release);
        }
    }

    /** A steady 0.9x client loses 5.5 ms a tick and flags once that exceeds both drifts, after about 22 seconds. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aSteadyNinetyPercentClientFlags(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            for (int tick = 0; tick < 300; tick++) {
                answerOwnPing(f);
                elapse(f, check, ONE_CLIENT_TICK * 10 / 9);
                f.tick(GROUND, true);
            }
            assertEquals(0, check.getViolations(), release + ": flagged before both drifts were spent");
            for (int tick = 0; tick < 300; tick++) {
                answerOwnPing(f);
                elapse(f, check, ONE_CLIENT_TICK * 10 / 9);
                f.tick(GROUND, true);
            }
            assertTrue(check.getViolations() > 0, release);
        }
    }

    /** Pings answered but no ticks for three seconds, then nine ticks per 500 ms ping batch: never a capped frame. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aClientThatStopsTickingButAnswersPingsFlags(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            for (int ping = 0; ping < 60; ping++) {
                answerOwnPing(f);
                elapse(f, check, ONE_CLIENT_TICK);
            }
            for (int batch = 0; batch < 50; batch++) {
                answerOwnPing(f);
                elapse(f, check, 500 * MILLI);
                for (int tick = 0; tick < 9; tick++) f.tick(GROUND, true);
            }
            assertTrue(check.getViolations() > 0, release);
        }
    }

    /**
     * The documented blind spots, asserted so a change to them is noticed: ten tick ends between ping batches,
     * creative flight, a passive passenger and any accepted teleport each restart the balance of a half-speed client.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void theDocumentedRestartsHideAHalfSpeedClient(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            for (int batch = 0; batch < 100; batch++) {
                answerOwnPing(f);
                elapse(f, check, 20 * ONE_CLIENT_TICK);
                for (int tick = 0; tick < 10; tick++) f.tick(GROUND, true);
            }
            assertEquals(0, check.getViolations(), release + ": ten ticks per ping batch");
        }
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.player.gamemode = GameMode.CREATIVE;
            f.player.canFly = true;
            f.player.isFlying = true;
            for (int tick = 0; tick < 400; tick++) {
                answerOwnPing(f);
                elapse(f, check, 2 * ONE_CLIENT_TICK);
                f.tick(GROUND, false);
            }
            assertEquals(0, check.getViolations(), release + ": creative flight");
        }
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            ride(f);
            for (int tick = 0; tick < 400; tick++) {
                answerOwnPing(f);
                elapse(f, check, 2 * ONE_CLIENT_TICK);
                f.rideTick();
            }
            assertEquals(0, check.getViolations(), release + ": passive passenger");
        }
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            for (int tick = 0; tick < 400; tick++) {
                answerOwnPing(f);
                elapse(f, check, 2 * ONE_CLIENT_TICK);
                f.tick(GROUND, true);
                if (tick % 40 == 0) accept(f, check);
            }
            assertEquals(0, check.getViolations(), release + ": accepted teleports");
        }
    }

    /**
     * A passive passenger while the server ticks at 10 TPS is never predicted, so the slow ticks are not restarted;
     * the teleport accepted after the dismount restarts the balance before the first walking tick.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aSlowRideEndedByAnAcceptedTeleportDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            ride(f);
            f.player.packetStateData.serverTickRate = 10.0F;
            for (int tick = 0; tick < 100; tick++) {
                answerOwnPing(f);
                elapse(f, check, 2 * ONE_CLIENT_TICK);
                f.rideTick();
            }
            f.player.packetStateData.serverTickRate = 20.0F;
            answerOwnPing(f);
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            accept(f, check);
            for (int tick = 0; tick < 5; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release);
        }
    }

    /**
     * A passive minecart passenger while the server ticks at 5 TPS sends a tick end every 200 ms and is never predicted.
     * Its tick ends restart the balance, so the first walking tick after a dismount with no teleport does not flag.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aSlowRideEndedWithoutATeleportDoesNotFlag(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            ride(f);
            f.player.packetStateData.serverTickRate = 5.0F;
            for (int tick = 0; tick < 150; tick++) {
                answerOwnPing(f);
                elapse(f, check, 4 * ONE_CLIENT_TICK);
                f.rideTick();
            }
            f.player.packetStateData.serverTickRate = 20.0F;
            answerOwnPing(f);
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 40; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release);
        }
    }

    /**
     * /tick rate {@code rate}, a frame stalled {@code pauseMs}, and rate 20 handled in that frame's packets: the frame
     * advanced its game time at the old tick length first, so it runs min(10, pause x rate) ticks.
     */
    @ParameterizedTest(name = "{0} rate {2} pause {3} ms")
    @MethodSource("releasesRatesAndPauses")
    void aRateRestoredDuringALongFrameNeverFlags(String release, ClientVersion version, int rate, long pauseMs)
            throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.player.packetStateData.serverTickRate = rate;
            for (int tick = 0; tick < 10; tick++) {
                answerOwnPing(f);
                elapse(f, check, SECOND / rate);
                f.tick(GROUND, true);
            }
            elapse(f, check, pauseMs * MILLI);
            f.player.packetStateData.serverTickRate = 20.0F;
            answerOwnPing(f);
            long owed = pauseMs * rate / 1000;
            for (int tick = 0; tick < Math.min(10, owed); tick++) f.tick(GROUND, true);
            for (int tick = 0; tick < 60; tick++) fullSpeedTick(f, check);
            assertEquals(0, check.getViolations(), release);
        }
    }

    /** A client at half speed once the full rate is proven again after a slow server. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourReleases")
    void aHalfSpeedClientAfterTheRateIsRestoredFlags(String release, ClientVersion version) throws Exception {
        try (var f = connect(version)) {
            NegativeTimerCheck check = afterJoin(f);
            f.player.packetStateData.serverTickRate = 5.0F;
            for (int tick = 0; tick < 20; tick++) {
                answerOwnPing(f);
                elapse(f, check, 4 * ONE_CLIENT_TICK);
                f.tick(GROUND, true);
            }
            f.player.packetStateData.serverTickRate = 20.0F;
            for (int tick = 0; tick < 200; tick++) {
                answerOwnPing(f);
                elapse(f, check, 2 * ONE_CLIENT_TICK);
                f.tick(GROUND, true);
            }
            assertTrue(check.getViolations() > 0, release);
        }
    }

    static Stream<Arguments> releasesAndRejectedTicks() {
        return fourReleases()
                .flatMap(release -> Stream.of(
                        Arguments.of(release.get()[0], release.get()[1], 3, 7),
                        Arguments.of(release.get()[0], release.get()[1], 7, 9)));
    }

    static Stream<Arguments> releasesRatesAndPauses() {
        return fourReleases()
                .flatMap(release -> Stream.of(1, 2, 3)
                        .flatMap(rate -> LongStream.of(2500, 2700, 2900, 3500, 4200, 5000, 8000, 9500)
                                .mapToObj(pause -> Arguments.of(release.get()[0], release.get()[1], rate, pause))));
    }

    static Stream<Arguments> fourReleases() {
        return Stream.of(
                Arguments.of("1.21.2", ClientVersion.V_1_21_2),
                Arguments.of("1.21.11", ClientVersion.V_1_21_11),
                Arguments.of("26.2", ClientVersion.V_26_2),
                Arguments.of("26.3", ClientVersion.V_26_3));
    }

    static Stream<Arguments> releasesAndStalls() {
        return fourReleases()
                .flatMap(release -> LongStream.of(1000, 2500, 3000, 5000, 10000, 20000)
                        .mapToObj(stall -> Arguments.of(release.get()[0], release.get()[1], stall)));
    }

    private static JavaTickFixture connect(ClientVersion version) throws Exception {
        JavaTickFixture f = connect();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        return f;
    }

    /** The join's accepted teleport restarts the balance; forty ticks at full speed follow. */
    private static NegativeTimerCheck afterJoin(JavaTickFixture f) throws Exception {
        f.player.setExperimentalChecks(true);
        f.place(GROUND, RESTING);
        NegativeTimerCheck check = f.player.checkManager.getCheck(NegativeTimerCheck.class);
        accept(f, check);
        for (int tick = 0; tick < 40; tick++) fullSpeedTick(f, check);
        assertEquals(0, check.getViolations(), "full speed flagged");
        assertFalse(f.player.getSetbackTeleportUtil().shouldBlockMovement(), "full speed was set back");
        return check;
    }

    /** One frame at full speed: its ping answered, one tick of wall time and one client tick. */
    private static void fullSpeedTick(JavaTickFixture f, NegativeTimerCheck check) throws Exception {
        answerOwnPing(f);
        elapse(f, check, ONE_CLIENT_TICK);
        f.tick(GROUND, true);
    }

    private static void accept(JavaTickFixture f, NegativeTimerCheck check) {
        var packet = new ServerboundAcceptTeleportation(1, new Vec3d(GROUND.x, GROUND.y, GROUND.z), 0, 0);
        check.onAcceptTeleportation(f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), f.player, packet);
    }

    private static void ride(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(
                VEHICLE, EntityTypes.defaults().byKey("minecraft:minecart").id(), GROUND, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * {@code nanos} of wall time pass. The fixture's clock stands still, so every instant both timer checks hold moves
     * back instead: the balance and the clocks earlier pongs proved.
     */
    private static void elapse(JavaTickFixture f, NegativeTimerCheck check, long nanos) throws Exception {
        TimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
        for (String name : new String[] {"timerBalanceRealTime", "knownPlayerClockTime", "lastMovementPlayerClock"}) {
            Field field = AbstractTimerCheck.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(check, field.getLong(check) - nanos);
            field.setLong(timer, field.getLong(timer) - nanos);
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
        // A pong, a tick end and a pong prove the client's frames tick at the full rate.
        answerOwnPing(f);
        f.tickEnd();
        answerOwnPing(f);
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
