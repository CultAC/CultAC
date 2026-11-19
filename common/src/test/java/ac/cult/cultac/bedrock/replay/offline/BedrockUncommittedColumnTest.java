package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.protocol.*;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * Teleports into a column the client may not have committed yet. The client holds the actor in place, its velocity
 * kept, until it commits the column; from the first tick after the one that answered the column's marker it may move
 * instead, and nothing bounds how long it may keep holding.
 */
public class BedrockUncommittedColumnTest {
    private static final double[] FALL_STEPS = {0, -0.0784, -0.155232, -0.23052736};
    private static final double[] FALL_VELOCITIES = {-0.0784, -0.155232, -0.23052736, -0.30431682};

    @Test
    public void anActorFreeTheTickAfterItsTeleportIsNotFlagged() {
        assertClean(teleportThenHold(0));
    }

    // The client held until the fifth tick after the one that answered the column's marker.
    @Test
    public void anActorHeldAtItsTeleportTargetUntilItsColumnCommitsIsNotFlagged() {
        for (int held = 1; held <= 5; held++) assertClean(teleportThenHold(held));
    }

    // A move commits the column: the client does not hold the actor in it again.
    @Test
    public void anActorHeldAfterItMovedInItsColumnIsFlagged() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = teleportIntoReceivedColumn(player, BedrockCoordinateFrame.IDENTITY, 1675);
            assertNotNull(process(player, frame(player, 1676, target, 0, false, true)));
            assertNotNull(process(player, frame(player, 1677, target, -0.0784, false, false)));
            assertNotNull(process(player, frame(player, 1678, target, -0.0784, false, false)));
            assertTrue(player.checkManager.getListener(BedrockMovement.class).violations > 0);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    // A shifted origin moves none of the client's columns: teleported 40000 blocks into the column of its own frame it
    // stood in before the shift, the client moves from the tick after the teleport, though the server's column there
    // arrives only behind it.
    @Test
    public void aShiftedOriginKeepsTheColumnTheClientHoldsInItsFrame() {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            Vec3 start = new Vec3(17.5, 80, 17.5);
            place(player, start, false);
            var chunks = player.bedrockState.clientChunks;
            chunks.publish(17, 17, 80);
            assertTrue(chunks.receive(1, 1, 600));
            double[] fall = {0, -0.0784};
            double[] fallVelocities = {-0.0784, -0.155232};
            Vec3 position = start;
            for (int i = 0; i < fall.length; i++) {
                position = position.add(0, fall[i], 0);
                assertNotNull(process(player, frame(player, 1600 + i, position, fallVelocities[i], false, false)));
            }

            var shifted = new BedrockCoordinateFrame(39984, 39984, 1);
            Vec3 target = new Vec3(40000.5, 200, 40000.5);
            Vec3 local = shifted.toLocal(target);
            chunks.publish(shifted, 40000, 40000, 80);
            // The server's column at the target, sent in the shifted frame, lands on a column the client holds.
            assertFalse(chunks.receive(shifted, 40000 >> 4, 40000 >> 4, 1676));
            var teleports = player.getSetbackTeleportUtil().bedrockTeleports();
            var operation = new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12);
            long revision =
                    teleports.addImmediateBedrockTransportTeleport(target, true, shifted, eye(local), operation, 12);
            teleports.confirmBedrockOrigin(revision, operation, shifted, eye(local));
            player.lastTransactionReceived.set(100);
            var teleport = BedrockAuthInputFrame.builder(player.playerUUID)
                    .protocolVersion(2193)
                    .clientTick(1676)
                    .position(local)
                    .packetPosition(eye(local))
                    .rotation(0, 0, 0)
                    .moveVector(0, 0)
                    .reportedEndOfTickVelocity(Vec3.ZERO)
                    .rawInputFlags(1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal())
                    .build();
            assertNotNull(process(player, teleport));
            assertEquals(target.x, entry(player).state().physicalFeetPosition().x(), 1.0E-9);
            double max = 0;
            position = local;
            for (int i = 0; i < FALL_STEPS.length; i++) {
                position = position.add(0, FALL_STEPS[i], 0);
                assertNotNull(process(player, frame(player, 1677 + i, position, FALL_VELOCITIES[i], false, false)));
                max = Math.max(max, offset(player));
            }
            assertClean(new double[] {player.checkManager.getListener(BedrockMovement.class).violations, max});
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static Vec3 eye(Vec3 feet) {
        return feet.add(0, 1.6200103759765625, 0);
    }

    private static double[] teleportThenHold(int heldTicks) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            Vec3 target = teleportIntoReceivedColumn(player, BedrockCoordinateFrame.IDENTITY, 1675);
            return fallFrom(player, target, 1676, heldTicks);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** A survival actor in the air far from the column, which a far publisher update dropped and which comes back. */
    private static Vec3 teleportIntoReceivedColumn(
            CultPlayer player, BedrockCoordinateFrame frame, long columnAnsweredAfter) {
        player.gamemode = GameMode.SURVIVAL;
        place(player, new Vec3(1.5, 80, 1.5), false);
        var chunks = player.bedrockState.clientChunks;
        chunks.receive(0, 0, 600);
        chunks.publish(1000, 1000, 256);
        chunks.publish(0, 0, 80);
        Vec3 target = new Vec3(1.5, 200, 1.5);
        player.getSetbackTeleportUtil()
                .bedrockTeleports()
                .addImmediateBedrockTransportTeleport(
                        target,
                        true,
                        frame,
                        null,
                        new BedrockTeleportOperation(12, BedrockTeleportProvenance.GEYSER, 12),
                        12);
        player.lastTransactionReceived.set(100);
        assertTrue(chunks.receive(0, 0, columnAnsweredAfter));
        return target;
    }

    /** The teleport tick, the held ticks, then a fall from rest; returns the violations and the largest offset. */
    private static double[] fallFrom(CultPlayer player, Vec3 target, long tick, int heldTicks) {
        double max = 0;
        assertNotNull(process(player, frame(player, tick++, target, 0, false, true)));
        for (int i = 0; i < heldTicks; i++) {
            assertNotNull(process(player, frame(player, tick++, target, 0, false, false)));
            max = Math.max(max, offset(player));
        }
        Vec3 position = target;
        for (int i = 0; i < FALL_STEPS.length; i++) {
            position = position.add(0, FALL_STEPS[i], 0);
            assertNotNull(process(player, frame(player, tick++, position, FALL_VELOCITIES[i], false, false)));
            max = Math.max(max, offset(player));
            assertEquals(
                    position.y, entry(player).state().physicalFeetPosition().y(), 1.0E-5);
        }
        return new double[] {player.checkManager.getListener(BedrockMovement.class).violations, max};
    }

    private static void assertClean(double[] result) {
        assertEquals(0, result[0], 0);
        assertTrue("offset " + result[1], result[1] <= 0.001);
    }

    private static void place(CultPlayer player, Vec3 position, boolean onGround) {
        player.x = player.lastX = position.x;
        player.y = player.lastY = position.y;
        player.z = player.lastZ = position.z;
        player.onGround = player.lastOnGround = onGround;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, position.x, position.y, position.z);
    }

    private static double offset(CultPlayer player) {
        return player.checkManager.getSimulationProcessor().getLastPrediction().getOffset();
    }

    private static BedrockProfileState.Entry entry(CultPlayer player) {
        return BedrockProfileState.profileEntries(player.checkManager
                        .getSimulationProcessor()
                        .getCurrentPredictionCommit()
                        .carry())
                .getFirst();
    }

    private static Object process(CultPlayer player, BedrockAuthInputFrame frame) {
        return BedrockFrameProcessor.process(player, frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
    }

    private static BedrockAuthInputFrame frame(
            CultPlayer player, long tick, Vec3 position, double velocity, boolean collided, boolean teleport) {
        long flags = teleport ? 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal() : 0;
        if (collided) flags |= 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(position)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .reportedEndOfTickVelocity(new Vec3(0, velocity, 0))
                .rawInputFlags(flags)
                .build();
    }
}
