package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsN;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.manager.player.setback.SetbackReason;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Where a setback returns the player, and when that position may change. It changes only to a position the server sent
 * or one the client was proven to reach, never while a correction is unanswered, and a setback hands out no tick or
 * velocity the safe state does not owe.
 */
class SetbackStateRulesTest {
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Set<Relative> RELATIVE_POSITION = Set.of(Relative.X, Relative.Y, Relative.Z);

    /** The client keeps sending moves before it handles a correction: none of them becomes safe. */
    @Test
    void movementWhileACorrectionIsUnansweredNeverBecomesSafe() throws Exception {
        try (var f = fixture()) {
            Vec3 start = new Vec3(.5, FLOOR_Y + 6, .5);
            f.place(start, new Vec3(0, -.3, 0));
            proveChunk(f);
            f.input(NONE);
            Vec3 moved = start.add(0, -.3, 0);
            PredictionResult accepted = f.tick(moved, false);
            assertTrue(exact(accepted), describe(accepted));
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();

            f.move(moved.add(0, -1, 0), false);
            f.tickEnd();
            assertTrue(teleports.isPendingSetback(), "the rejected movement was not set back");
            SetBackData setback = teleports.getRequiredSetBack();
            SetbackPosWithVector anchor = teleports.safePosition();

            for (int tick = 0; tick < 2; tick++) {
                var event = f.move(moved, false);
                f.tickEnd();
                assertTrue(event.isCancelled(), "a move before the correction's answer reached the server");
                assertSame(anchor, teleports.safePosition(), "a move before the correction's answer became safe");
            }

            acceptCorrection(f, setback);
            assertFalse(teleports.isPendingSetback());
            Vec3 at = setback.getTeleportData().getLocation();
            Vec3 next = at.add(setback.getVelocity());
            PredictionResult after = f.tick(next, false);
            assertTrue(exact(after), describe(after));
            assertNotSame(anchor, teleports.safePosition());
            assertEquals(next, teleports.safePosition().getPos(), "the first move after the answer is not safe");
        }
    }

    /** A server teleport is where a setback returns the player from the moment it is sent. */
    @Test
    void aServerTeleportIsSafeFromTheMomentItIsSent() throws Exception {
        try (var f = standing()) {
            Vec3 target = STANDING.add(3, 0, 0);
            send(f, 50, target, Set.of(), 0);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertEquals(target, teleports.safePosition().getPos());
            assertTrue(teleports.getRequiredSetBack().isPlugin());
            assertFalse(teleports.isPendingSetback(), "a server teleport counts as a pending setback");
        }
    }

    /** A relative teleport resolves against the safe position, never against where the client says it is. */
    @Test
    void aRelativeServerTeleportResolvesAgainstTheSafePosition() throws Exception {
        try (var f = standing()) {
            Vec3 safe = STANDING.add(0, 0, 2);
            SetbackFixtures.seedSafePosition(f.player, new SetbackPosWithVector(safe, Vec3.ZERO));
            assertNotEquals(safe.z, f.player.z);
            send(f, 51, new Vec3(1, 0, 0), RELATIVE_POSITION, 0);
            assertEquals(
                    safe.add(1, 0, 0),
                    f.player.getSetbackTeleportUtil().safePosition().getPos());
        }
    }

    /** With the latest movement already safe, a setback stands in for nothing: no tick is added. */
    @Test
    void aSetbackAfterASafeMovementAddsNoTick() throws Exception {
        try (var f = fixture()) {
            Vec3 start = new Vec3(.5, FLOOR_Y + 6, .5);
            f.place(start, new Vec3(0, -.3, 0));
            proveChunk(f);
            f.input(NONE);
            PredictionResult accepted = f.tick(start.add(0, -.3, 0), false);
            assertTrue(exact(accepted), describe(accepted));
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            SetbackPosWithVector safe = teleports.safePosition();

            assertTrue(teleports.executeViolationSetback());
            SetBackData setback = teleports.getRequiredSetBack();
            assertEquals(safe.getPos(), setback.getTeleportData().getLocation(), "the setback added a tick");
            assertEquals(safe.getVector(), setback.getVelocity());
        }
    }

    /** A knockback the safe tick already took is part of the safe velocity; a setback must not send it again. */
    @Test
    void aSetbackDoesNotResendKnockbackTheSafeTickTook() throws Exception {
        try (var f = standing()) {
            f.pongThrough(f.velocity(new Vec3(0, 0.5, 0)));
            PredictionResult took = f.tick(STANDING.add(0, 0.5, 0), false);
            assertTrue(exact(took), describe(took));
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            SetbackPosWithVector safe = teleports.safePosition();
            assertTrue(safe.carries(f.player.checkManager.getKnockbackHandler().getLastSent()));

            teleports.executeNonSimulatingSetback();
            SetBackData setback = teleports.getRequiredSetBack();
            assertNotNull(setback);
            assertEquals(safe.getVector(), setback.getVelocity(), "the setback sent the knockback again");
        }
    }

    /** Only a correction's own answer completes it: the answer to a server teleport sent before it does not. */
    @Test
    void anAnswerToAServerTeleportDoesNotCompleteACorrection() throws Exception {
        try (var f = standing()) {
            Vec3 target = STANDING.add(3, 0, 0);
            int leading = send(f, 52, target, Set.of(), 0);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.executeViolationSetback());
            SetBackData correction = teleports.getRequiredSetBack();
            assertFalse(correction.isPlugin());

            answer(f, 52, leading, target, 0);
            assertTrue(teleports.isPendingSetback(), "the server teleport's answer completed the correction");
            assertSame(correction, teleports.getRequiredSetBack());

            acceptCorrection(f, correction);
            assertFalse(teleports.isPendingSetback());
        }
    }

    /**
     * Gun plugins move the camera with a server teleport per shot. Each relative teleport, and the moves the client sent
     * before handling it, must pass without a setback.
     */
    @Test
    void aBurstOfRecoilTeleportsCausesNoSetback() throws Exception {
        try (var f = standing()) {
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            float pitch = 0;
            for (int shot = 0; shot < 20; shot++) {
                int leading = send(
                        f,
                        60 + shot,
                        Vec3.ZERO,
                        Set.of(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT),
                        -1.5F);
                assertFalse(teleports.shouldBlockMovement(), "a recoil teleport blocks movement");
                PredictionResult before = f.tick(STANDING, true);
                assertTrue(exact(before), "shot " + shot + " before the answer: " + describe(before));
                pitch -= 1.5F;
                answer(f, 60 + shot, leading, STANDING, pitch);
                PredictionResult after = f.tick(STANDING, true);
                assertTrue(exact(after), "shot " + shot + " after the answer: " + describe(after));
                assertTrue(teleports.getRequiredSetBack().isPlugin(), "shot " + shot + " caused a setback");
            }
            assertEquals(0, f.player.checkManager.getCheck(BadPacketsN.class).violations);
        }
    }

    /**
     * Movement in a chunk the client has not proven loaded cannot be simulated: the setback resyncs to the safe
     * position without a tick and blocks offsets, and movement far from the target stays blocked.
     */
    @Test
    void aSetbackInAnUnloadedChunkResyncsWithoutATick() throws Exception {
        try (var f = fixture()) {
            // The fixture loads chunks -1..1 only.
            Vec3 far = new Vec3(100.5, FLOOR_Y + 1, 0.5);
            f.place(far, Vec3.ZERO);
            f.playerPosition(far, Vec3.ZERO, Set.of());
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.insideUnloadedChunk());

            assertTrue(teleports.executeViolationSetback());
            SetBackData setback = teleports.getRequiredSetBack();
            assertEquals(far, setback.getTeleportData().getLocation(), "the unloaded-chunk setback ticked");
            assertTrue(teleports.isBlockingOffsets(), "an unloaded-chunk setback is a full resync");

            var away = f.move(far.add(5, 0, 0), false);
            f.tickEnd();
            assertTrue(away.isCancelled(), "a move more than 4 blocks from the target reached the server");
            var above = f.move(far.add(0, 1, 0), false);
            f.tickEnd();
            assertTrue(above.isCancelled(), "a move above the target reached the server");
        }
    }

    /** One correction at a time: further setbacks while it is unanswered send nothing new. */
    @Test
    void aSecondSetbackWhileOneIsPendingSendsNothing() throws Exception {
        try (var f = standing()) {
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.executeViolationSetback());
            SetBackData first = teleports.getRequiredSetBack();
            int sent = f.player.lastTransactionSent.get();

            assertFalse(teleports.executeViolationSetback());
            teleports.executeForceResync("test");
            teleports.executeNonSimulatingSetback();
            assertSame(first, teleports.getRequiredSetBack());
            assertEquals(sent, f.player.lastTransactionSent.get(), "a second setback was sent");
        }
    }

    /** Each setback path tells the player's observer once, with its reason, cause and the check its caller named. */
    @Test
    void everySetbackPathReportsItsTeleportOnce() throws Exception {
        try (var f = standing()) {
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            Setbacks reports = new Setbacks();
            f.player.observer = reports;
            OffsetHandler simulation = f.player.checkManager.getCheck(OffsetHandler.class);

            assertTrue(simulation.executeViolationSetback());
            reported(f, reports, "VIOLATION null Simulation");
            assertTrue(teleports.executeViolationSetback());
            reported(f, reports, "VIOLATION null null");
            teleports.executeNonSimulatingSetback();
            reported(f, reports, "NON_SIMULATING null null");
            teleports.executeForceResync("test");
            reported(f, reports, "RESYNC test null");

            assertTrue(teleports.executeViolationSetback());
            SetBackData pending = teleports.getRequiredSetBack();
            reports.sent.clear();
            teleports.resendIgnored("ignored");
            assertEquals(List.of("RESEND ignored null"), reports.sent);
            reports.sent.clear();
            teleports.sendDismounted(pending);
            reported(f, reports, "RESEND dismounted null");
        }
    }

    /** A movement Simulation rejects is set back in Simulation's name. */
    @Test
    void aSetbackOfARejectedMovementNamesSimulation() throws Exception {
        try (var f = standing()) {
            Setbacks reports = new Setbacks();
            f.player.observer = reports;
            OffsetHandler simulation = f.player.checkManager.getCheck(OffsetHandler.class);

            // A block in one tick from standing, past the immediate-setback threshold.
            f.tick(STANDING.add(1, 0, 0), true);

            assertTrue(simulation.violations > 0);
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), "the movement was not set back");
            assertEquals(List.of("VIOLATION null Simulation"), reports.sent);
        }
    }

    /** The report of the one setback sent since the last, which the client then accepts. */
    private static void reported(JavaTickFixture f, Setbacks reports, String expected) {
        assertEquals(List.of(expected), reports.sent);
        reports.sent.clear();
        acceptCorrection(f, f.player.getSetbackTeleportUtil().getRequiredSetBack());
        assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
    }

    private static final class Setbacks implements PlayerObserver {
        final List<String> sent = new ArrayList<>();

        @Override
        public void setbackSent(SetbackReason reason, String cause, Check check) {
            sent.add(reason + " " + cause + " " + (check == null ? null : check.getCheckName()));
        }
    }

    /** Blocked offsets wait for the resync's own answer, not for a server teleport sent before it. */
    @Test
    void blockedOffsetsClearOnlyWithTheResyncsOwnAnswer() throws Exception {
        try (var f = standing()) {
            Vec3 target = STANDING.add(3, 0, 0);
            int leading = send(f, 53, target, Set.of(), 0);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            teleports.executeForceResync("test");
            SetBackData resync = teleports.getRequiredSetBack();
            assertTrue(teleports.isBlockingOffsets());

            answer(f, 53, leading, target, 0);
            assertTrue(teleports.isBlockingOffsets(), "the server teleport's answer cleared blocked offsets");

            acceptCorrection(f, resync);
            assertFalse(teleports.isBlockingOffsets());
        }
    }

    /** A Java correction carries the knockback it owes in its player_position and sends no separate motion. */
    @Test
    void aCorrectionCarriesItsVelocityInItsTeleport() throws Exception {
        try (var f = standing()) {
            // The client may not have taken this knockback yet, so the correction owes it.
            f.velocity(new Vec3(0, 0.5, 0));
            written(f);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.executeViolationSetback());

            List<String> written = written(f);
            assertTrue(written.contains("minecraft:player_position"), written.toString());
            assertFalse(written.contains("minecraft:set_entity_motion"), written.toString());
            SetBackData setback = teleports.getRequiredSetBack();
            assertTrue(setback.getVelocity().y > 0.4, "the correction dropped the knockback: " + setback.getVelocity());
            assertEquals(
                    setback.getVelocity(), teleports.pendingTeleports.peekLast().getDeltaMovement());
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        return f;
    }

    /** A player placed by an answered teleport, standing on the floor in a proven chunk. */
    private static JavaTickFixture standing() throws Exception {
        JavaTickFixture f = fixture();
        f.place(STANDING, GROUNDED);
        f.playerPosition(STANDING, Vec3.ZERO, Set.of());
        proveChunk(f);
        f.input(NONE);
        f.tick(STANDING, true);
        PredictionResult settled = f.tick(STANDING, true);
        assertTrue(exact(settled), describe(settled));
        return f;
    }

    /** The names of the packets written to the client since the last call. */
    private static List<String> written(JavaTickFixture f) {
        f.fixture.channel.flush();
        f.fixture.channel.runPendingTasks();
        var packets = f.fixture.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
        var names = new ArrayList<String>();
        ByteBuf frame;
        while ((frame = f.fixture.channel.readOutbound()) != null) {
            try {
                names.add(packets.name(Wire.readVarInt(frame)));
            } finally {
                frame.release();
            }
        }
        return names;
    }

    private static void proveChunk(JavaTickFixture f) {
        f.pongThrough(f.player.sendTrailingTransaction());
    }

    /** A server player_position with its leading and trailing pings; returns the leading ping. */
    private static int send(JavaTickFixture f, int id, Vec3 target, Set<Relative> relatives, float pitch) {
        var packet = new ClientboundPlayerPosition(
                id, JavaTickFixture.vec3d(target), new Vec3d(0, 0, 0), 0, pitch, relatives);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, f.player, packet);
        int leading = f.player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        return leading;
    }

    /** The 26.3 acceptance after the client answers the teleport's leading ping. */
    private static void answer(JavaTickFixture f, int id, int leading, Vec3 at, float pitch) {
        f.pongThrough(leading);
        var packet = new ServerboundAcceptTeleportation(id, JavaTickFixture.vec3d(at), f.player.xRot, pitch);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the teleport acceptance was rejected");
    }

    /** The client handles a correction: it answers the correction's ping and accepts its exact target. */
    private static void acceptCorrection(JavaTickFixture f, SetBackData correction) {
        var teleport = correction.getTeleportData();
        answer(f, teleport.getTeleportId(), teleport.getTransaction(), teleport.getLocation(), f.player.yRot);
    }
}
