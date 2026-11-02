package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.4 player crouches on a bottom slab under water with the sprint key held. Its feet block holds no water, so
 * Entity#updateSwimming never sets swimming and LocalPlayer#aiStep keeps crouching. Every sprinting tick
 * shouldStopSprinting's setSprinting(false) runs before the key start, which canStartSprinting allows while moving
 * slowly under water, so setSprinting(true) restores the flag and vanilla's minecraft:sprinting modifier without
 * announcing anything: a modifier the server installed in between does not survive the next aiStep.
 * water_movement_efficiency 1 makes LivingEntity#travelInWater move at getSpeed().
 */
class JavaSprintRestartTest {
    private static final int STONE_SLAB =
            DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
    private static final ServerboundPlayerInput CROUCH =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput CROUCH_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final float CANONICAL = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    // KeyboardInput's forward impulse slowed by sneaking_speed in aiStep, then LivingEntity#aiStep's 0.98F.
    private static final float FORWARD = 0.3F * 0.98F;

    @Test
    void aServerModifierDoesNotSurviveTheSilentRestart() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_1_21_4)) {
            Vec3 position = crouchOnSlabUnderWater(f);
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(CROUCH_SPRINT);
            // LocalPlayer#goDownInWater's -0.04 keeps every crouching tick on the slab: the efficiency is never halved.
            double z = input(CANONICAL);
            position = position.add(0, 0, z);
            PredictionResult start = f.tick(position, true);
            assertTrue(exact(start), "start: " + describe(start));
            double velocity = z * waterFriction();

            serverModifier(f, 3.0D);
            z = velocity + input(CANONICAL);
            position = position.add(0, 0, z);
            PredictionResult restarted = f.tick(position, true);
            assertTrue(exact(restarted), "restart: " + describe(restarted));
            assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, f.player.sprintState.heldModifier());
            velocity = z * waterFriction();

            // Vanilla's modifier carries on to the next restart.
            z = velocity + input(CANONICAL);
            PredictionResult next = f.tick(position.add(0, 0, z), true);
            assertTrue(exact(next), "after: " + describe(next));
        }
    }

    @Test
    void theServersModifierSpeedIsNotTheRestartedTravel() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_1_21_4)) {
            Vec3 position = crouchOnSlabUnderWater(f);
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(CROUCH_SPRINT);
            double z = input(CANONICAL);
            position = position.add(0, 0, z);
            assertTrue(exact(f.tick(position, true)));
            double velocity = z * waterFriction();
            serverModifier(f, 3.0D);
            float served = (float) ((double) 0.1F * 4.0D);
            PredictionResult result = f.tick(position.add(0, 0, velocity + input(served)), true);
            assertTrue(result.hasEffectiveFlags(), describe(result));
        }
    }

    /** One idle crouching tick on the slab, so the next aiStep reads crouching from the previous keys. */
    private static Vec3 crouchOnSlabUnderWater(JavaTickFixture f) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), STONE_SLAB);
        f.water(new BlockPos(0, FLOOR_Y + 1, 0), new BlockPos(0, FLOOR_Y + 2, 0));
        attributes(f, new AttributeSnapshot("minecraft:water_movement_efficiency", 1.0D, List.of()));
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        Vec3 start = new Vec3(0.5, FLOOR_Y + 0.5, 0.5);
        f.place(start, new Vec3(0, -0.005D, 0));
        f.player.onGround = true;
        f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.input(CROUCH);
        PredictionResult idle = f.tick(start, true);
        assertTrue(exact(idle), "idle: " + describe(idle));
        return start;
    }

    /** Entity#moveRelative's forward input at LivingEntity#travelInWater's speed for getSpeed() {@code speed}. */
    private static double input(float speed) {
        float g = 0.02F;
        g += (speed - g) * 1.0F;
        return (double) FORWARD * g;
    }

    /** LivingEntity#travelInWater's horizontal friction for a sprinting player at full efficiency. */
    private static double waterFriction() {
        float friction = 0.9F;
        friction += (0.54600006F - friction) * 1.0F;
        return friction;
    }

    private static void serverModifier(JavaTickFixture f, double amount) {
        attributes(
                f,
                new AttributeSnapshot(
                        "minecraft:movement_speed",
                        0.1F,
                        List.of(new AttributeModifier(
                                "minecraft:sprinting", amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))));
    }

    private static void attributes(JavaTickFixture f, AttributeSnapshot snapshot) {
        var packet = new ClientboundUpdateAttributes(f.player.entityID, List.of(snapshot));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
