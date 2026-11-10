package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.combat.KeepSprint;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick runs handleKeybinds, whose sprinting attack reaches Player#causeExtraKnockback's 0.6 X/Z damping,
 * before ClientLevel#tickEntities. An entity ticking before the local player then pushes the damped velocity:
 * {@code 0.6 * v + push}, not {@code 0.6 * (v + push)}.
 */
class JavaAttackSlowEntityPushTest {
    private static final int BOAT = 100;
    private static final int COW = 101;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.98F;
    private static final double AIR_FRICTION = 0.91F;
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    /** Entity#push from a pusher 0.4 along Z inside the player's box. */
    private static final double PUSH = -0.05 * 0.4 / Math.sqrt(0.4);

    @Test
    void anEarlyEntityPushAddsToTheAttackDampedVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, START.add(1.2, -1.0, 2.0), 0, 0, 0);
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            // Player#getAttackStrengthScale passes 0.9 once the ticker reaches the empty hand's 5-tick attack delay.
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
            for (int tick = 0; tick < 10; tick++) f.tickEnd();
            double firstFall = (0.0D - GRAVITY) * AIR_DRAG;
            double secondFall = (firstFall - GRAVITY) * AIR_DRAG;
            double thirdFall = (secondFall - GRAVITY) * AIR_DRAG;
            f.place(START, new Vec3(0.0D, firstFall, 0.0D));
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            f.input(SPRINT);
            Vec3 input = ClientMoveInput.of(f, SPRINT, 1.0F, 0.025999999F, yaw);

            Vec3 delta = input;
            Vec3 at = START.add(delta.x, firstFall, delta.z);
            PredictionResult falling = f.tick(at, false);
            assertTrue(exact(falling), describe(falling));

            delta = new Vec3(delta.x * AIR_FRICTION, 0.0D, delta.z * AIR_FRICTION).add(input);
            Vec3 before = at;
            at = at.add(delta.x, secondFall, delta.z);
            PredictionResult second = f.tick(at, false);
            assertTrue(exact(second), describe(second));

            // A cow 0.4 along Z from the player's start, ticking before the player.
            f.player.compensatedEntities.addEntity(
                    COW, EntityTypes.defaults().byKey("cow").id(), at.add(0, -0.5, 0.4), 0, 0, 0);
            f.player.compensatedEntities.getEntity(COW).setClientTickOrder(-1L);
            var attack = new ServerboundInteract(BOAT, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            new PacketPlayerAttack()
                    .onInteract(
                            new PacketReceiveEvent<>(
                                    f.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack),
                            f.player,
                            attack);
            delta = new Vec3(delta.x * AIR_FRICTION * 0.6D, 0.0D, delta.z * AIR_FRICTION * 0.6D + PUSH).add(input);
            at = at.add(delta.x, thirdFall, delta.z);
            PredictionResult attacked = f.tick(at, false);
            assertEquals(1, attacked.getSimulationContext().getMinAttackSlow(), describe(attacked));
            assertEquals(
                    0.0D, f.player.checkManager.getListener(KeepSprint.class).getViolations(), describe(attacked));
            assertTrue(exact(attacked), describe(attacked) + " from " + before);
        }
    }
}
