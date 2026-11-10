package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client sprint-swimming in deep water with water_movement_efficiency 1, whose movement_speed the server gave a
 * minecraft:sprinting modifier that quadruples it. LivingEntity#travelInWater lerps moveRelative's 0.02 toward
 * Player#getSpeed() by the efficiency, halved off the ground, so the modifier the client holds sets the acceleration.
 * Player#causeExtraKnockback damps deltaMovement by 0.6 and calls setSprinting(false), and aiStep's re-sprint puts
 * vanilla's modifier back before travel: a damped travel accelerates at vanilla's speed, never at the server's.
 */
class JavaSprintModifierWaterTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final float YAW = 30.0F;
    private static final float CANONICAL = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final float QUADRUPLED = (float) ((double) 0.1F * 4.0D);
    private static final AttributeModifier QUADRUPLED_MODIFIER =
            new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    // LivingEntity#travelInWater halves water_movement_efficiency off the ground.
    private static final float EFFICIENCY = 1.0F * 0.5F;
    // A sprinting player's 0.9F slowdown lerped toward 0.54600006F by the same efficiency.
    private static final float SLOW_DOWN = 0.9F + (0.54600006F - 0.9F) * EFFICIENCY;
    // Player#getAttackStrengthScale passes 0.9 after five ticks at the default attack speed of 4.
    private static final int SERVER_MODIFIER_TICKS = 5;
    private static final double SWIM_Y = FLOOR_Y + 1;

    private Vec3 position;

    @Test
    void aPossibleAttackResetSwimsOnAtVanillasSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 velocity = swimAtTheServersModifier(f);
            attack(f);
            Vec3 delta = damped(velocity).add(input(f, CANONICAL));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), false);
            assertTrue(exact(result), describe(result));
            // Only the damped candidate at vanilla's speed reproduces the move, so it commits vanilla's modifier.
            assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, f.player.sprintState.heldModifier());
            assertFalse(f.player.sprintState.outcomeOpen());
        }
    }

    @Test
    void aDampedSwimAtTheServersSpeedFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 velocity = swimAtTheServersModifier(f);
            attack(f);
            Vec3 delta = damped(velocity).add(input(f, QUADRUPLED));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), false);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    /**
     * A first sprinting tick from rest at vanilla's modifier, then ticks at the server's quadrupled one; the velocity
     * carried into the next tick. A sprinting player's fluid falling adjustment and level swimming keep Y still.
     */
    private Vec3 swimAtTheServersModifier(JavaTickFixture f) {
        f.water(new BlockPos(-8, FLOOR_Y, -4), new BlockPos(8, FLOOR_Y + 3, 8));
        attributes(f, new AttributeSnapshot("minecraft:water_movement_efficiency", 1.0D, List.of()));
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        position = new Vec3(0.5, SWIM_Y, 0.5);
        f.place(position, Vec3.ZERO);
        f.command(PlayerCommandAction.START_SPRINTING);
        f.input(SPRINT);
        Vec3 delta = input(f, CANONICAL);
        position = position.add(delta.x, 0.0, delta.z);
        PredictionResult result = f.tick(position, false);
        assertTrue(exact(result), "start: " + describe(result));
        Vec3 velocity = slowed(delta);

        holdQuadrupledModifier(f);
        for (int tick = 0; tick < SERVER_MODIFIER_TICKS; tick++) {
            delta = velocity.add(input(f, QUADRUPLED));
            position = position.add(delta.x, 0.0, delta.z);
            result = f.tick(position, false);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            velocity = slowed(delta);
        }
        return velocity;
    }

    /** Entity#moveRelative's sprint input at LivingEntity#travelInWater's speed for getSpeed() {@code speed}. */
    private static Vec3 input(JavaTickFixture f, float speed) {
        float water = 0.02F;
        water += (speed - water) * EFFICIENCY;
        return ClientMoveInput.of(f, SPRINT, 1.0F, water, YAW);
    }

    private static Vec3 slowed(Vec3 delta) {
        return new Vec3(delta.x * SLOW_DOWN, 0.0, delta.z * SLOW_DOWN);
    }

    private static Vec3 damped(Vec3 velocity) {
        return new Vec3(velocity.x * 0.6, 0.0, velocity.z * 0.6);
    }

    /** MultiPlayerGameMode#attack on an entity the server never sent: the reset is only possible. */
    private static void attack(JavaTickFixture f) {
        var attack = new ServerboundInteract(99, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        new PacketPlayerAttack().onInteract(f.event(ServerboundPackets.INTERACT, attack), f.player, attack);
    }

    private static void holdQuadrupledModifier(JavaTickFixture f) {
        attributes(f, new AttributeSnapshot("minecraft:movement_speed", 0.1F, List.of(QUADRUPLED_MODIFIER)));
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
