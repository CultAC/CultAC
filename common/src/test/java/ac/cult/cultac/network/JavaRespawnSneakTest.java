package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A client holding forward, shift and sprint through a respawn that keeps its entity data and attribute modifiers.
 * From 1.21.6 ClientPacketListener#handleRespawn gives the new LocalPlayer the old lastSentInput and a fresh
 * KeyboardInput, so it sends no PLAYER_INPUT for the keys still held. Until its first LocalPlayer#aiStep the shift
 * key reads Input.EMPTY, so that tick does not crouch and starts sprinting; every later tick crouches from the held
 * shift and keeps the sprint.
 */
class JavaRespawnSneakTest {
    private static final ServerboundPlayerInput SNEAK_SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, -4.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final float SNEAKING_SPEED = 0.3F;
    // Player#getFlyingSpeed and the minecraft:sprinting modifier on the 0.1 movement speed.
    private static final float SPRINT_AIR_SPEED = 0.025999999F;
    private static final float SPRINT_SPEED = 0.13F;
    private static final double AIR_FRICTION = 0.91F;
    private static final double FRICTION = 0.6F * 0.91F;

    @Test
    void sneakSprintingAfterARespawnKeepingTheKeysIsNotFlagged() throws Exception {
        try (var f = respawnedHoldingKeys()) {
            double velocity = firstTick(f);
            for (int tick = 2; tick <= 5; tick++) {
                double input = input(f, SNEAKING_SPEED, SPRINT_SPEED);
                PredictionResult result = f.tick(START.add(0, 0, f.player.z - START.z + velocity + input), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                velocity = (velocity + input) * FRICTION;
            }
        }
    }

    @Test
    void sprintingAtFullSpeedAfterARespawnKeepingShiftHeldIsRejected() throws Exception {
        try (var f = respawnedHoldingKeys()) {
            double velocity = firstTick(f);
            double input = input(f, 1.0F, SPRINT_SPEED);
            assertRejected(f.tick(START.add(0, 0, f.player.z - START.z + velocity + input), true), "NoSneakSlow");
        }
    }

    @Test
    void aUseOnBeforeTheFirstTickAfterTheRespawnIsNotASecondaryUse() throws Exception {
        try (var f = respawnedHoldingKeys()) {
            // Minecraft#tick runs handleKeybinds before the new LocalPlayer's first aiStep.
            var place = new BlockPlace(
                    f.player,
                    Hand.MAIN_HAND,
                    new BlockPos(0, FLOOR_Y - 1, -5),
                    Direction.UP,
                    OfflineCultTestBootstrap.item("minecraft:stone", 1),
                    null);
            assertFalse(place.isSecondaryUse());
            // KEEP_ENTITY_DATA copies the old player's crouching pose.
            assertEquals(Pose.CROUCHING, f.player.pose);

            double velocity = firstTick(f);
            double input = input(f, SNEAKING_SPEED, SPRINT_SPEED);
            PredictionResult result = f.tick(START.add(0, 0, f.player.z - START.z + velocity + input), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * Before 1.21.6 ClientPacketListener#handleRespawn passes only the old shift and sprint states, and the new
     * LocalPlayer starts lastSentInput at Input.EMPTY: a client that released shift while loading sends no
     * PLAYER_INPUT, and its first LocalPlayer#aiStep leaves the next tick uncrouched.
     */
    @Test
    void walkingAfterReleasingShiftWhileLoadingAt1214IsNotFlagged() throws Exception {
        var sneak = new ServerboundPlayerInput(false, false, false, false, false, true, false);
        var forward = new ServerboundPlayerInput(true, false, false, false, false, false, false);
        try (var f = respawnedHolding(ClientVersion.V_1_21_4, sneak)) {
            PredictionResult still = f.tick(START, true);
            assertTrue(exact(still), "tick 1 " + describe(still));
            f.input(forward);
            PredictionResult walk = f.tick(START.add(ClientMoveInput.of(f, forward, 1.0F, 0.1F, 0.0F)), true);
            assertTrue(exact(walk), "tick 2 " + describe(walk));
        }
    }

    private static JavaTickFixture respawnedHoldingKeys() throws Exception {
        return respawnedHolding(ClientVersion.V_26_3, SNEAK_SPRINT_FORWARD);
    }

    /** A grounded {@code version} client holding {@code keys}, respawned in place, its first position not yet sent. */
    private static JavaTickFixture respawnedHolding(ClientVersion version, ServerboundPlayerInput keys)
            throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(START, GROUNDED);
        f.input(keys);
        // The old player's last Player#updatePlayerPose already crouched for the held shift.
        f.player.refreshPlayerPose();
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
        // A respawn into the same dimension replaces the LocalPlayer the same way and keeps the floor.
        DeadPlayerSetbackTest.respawn(
                f,
                ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA,
                f.player.compensatedWorld.getLastClientboundDimension().dimension());
        f.pongThrough(f.player.lastTransactionSent.get());
        f.playerPosition(START, Vec3.ZERO, Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z));
        f.player.packetStateData.playerLoadedIntoLevel = true;
        return f;
    }

    /** The new player's first tick: unslowed sprinting input at the air speed of a player not yet on the ground. */
    private static double firstTick(JavaTickFixture f) {
        var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
        double input = input(f, 1.0F, SPRINT_AIR_SPEED);
        PredictionResult result = f.tick(START.add(0, 0, input), true);
        assertTrue(exact(result), "tick 1 " + describe(result));
        return input * AIR_FRICTION;
    }

    private static double input(JavaTickFixture f, float movingSlowlyFactor, float speed) {
        return ClientMoveInput.of(f, SNEAK_SPRINT_FORWARD, movingSlowlyFactor, speed, 0.0F).z;
    }
}
