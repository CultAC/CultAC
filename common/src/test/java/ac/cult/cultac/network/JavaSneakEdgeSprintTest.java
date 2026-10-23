package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A 26.3 client sprinting toward the end of a stone floor, pressing shift so Player#maybeBackOffFromEdge stops it at
 * the drop while deltaMovement keeps the crouched sprint input, then releasing shift and walking off. The moves are a
 * real client's, translated onto the fixture floor; the drop starts at z = 1.
 */
class JavaSneakEdgeSprintTest {
    private static final ServerboundPlayerInput SPRINT_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    /** LivingEntity#checkAutoSpinAttack finds nothing to hit here, so a spin leaves the edge's velocity alone. */
    @ParameterizedTest
    @ValueSource(ints = {0, 20})
    void theVelocityTheEdgeHidesIsReleasedWithShift(int spinTicks) throws Exception {
        try (var f = new JavaTickFixture()) {
            drop(f);
            f.player.riptideSpinAttackTicks = spinTicks;
            // LivingEntity#travelInAir's ground friction, 0.6F * 0.91F, applied to the previous move.
            double friction = 0.6F * 0.91F;
            f.place(
                    recorded(272.3055689053061, -2.0, -48.99925107598291),
                    new Vec3(0.004762975409164483 * friction, 0.0, 0.1436450745273774 * friction));
            f.input(SPRINT);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            assertExact(f, 717.6444702148438F, 55.13953399658203F, 272.3134079575507, -2.0, -48.793528589822714, true);

            f.input(SPRINT_SNEAK);
            assertExact(f, 717.3444213867188F, 54.989532470703125F, 272.3235976916909, -2.0, -48.70394122033181, true);
            assertExact(f, 717.1943969726562F, 54.989532470703125F, 272.3310329941979, -2.0, -48.70394122033181, true);
            assertExact(f, 715.5443725585938F, 54.08953094482422F, 272.3380650745933, -2.0, -48.70394122033181, true);

            f.input(SPRINT);
            assertExact(f, 713.29443359375F, 52.58952331542969F, 272.34636848651104, -2.0, -48.59479956952958, true);
            assertExact(f, 712.5443115234375F, 51.53951644897461F, 272.36744237810774, -2.078400001525879, -48.408886474298384, false);
        }
    }

    /**
     * A sprinting client whose movement_speed the server replaced without minecraft:sprinting: the flag stays set,
     * LivingEntity#getSpeed is the walking speed, and the edge hides the walking input.
     */
    @Test
    void aSprintFlagWithoutTheModifierCarriesTheWalkingInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            drop(f);
            float yaw = 717.3444213867188F;
            rotate(f, yaw, 54.989532470703125F);
            // The box overhangs the drop by all but 1e-6, so the edge takes every move toward it.
            Vec3 position = new Vec3(0.3, FLOOR_Y, 1.299999);
            Vec3 velocity = new Vec3(0.002, 0.0, 0.05);
            f.place(position, velocity);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            f.input(SPRINT_SNEAK);
            double friction = 0.6F * 0.91F;
            // AttributeInstance#calculateValue with the sprinting modifier on the 0.1F base.
            float sprintSpeed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
            // Shift is new this tick, so LocalPlayer#aiStep does not crouch yet.
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT_SNEAK, 1.0F, sprintSpeed, yaw));
            position = position.add(delta.x, 0.0, 0.0);
            assertExact(f.tick(position, true));
            velocity = new Vec3(delta.x * friction, 0.0, delta.z * friction);

            var attributes = new ClientboundUpdateAttributes(
                    f.player.entityID,
                    List.of(new AttributeSnapshot("minecraft:movement_speed", 0.1F, List.of())));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, attributes, false);
            f.player.packetEntityReplication.onUpdateAttributes(event, f.player, attributes);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());

            for (int tick = 0; tick < 2; tick++) {
                delta = velocity.add(ClientMoveInput.of(f, SPRINT_SNEAK, 0.3F, 0.1F, yaw));
                position = position.add(delta.x, 0.0, 0.0);
                assertExact(f.tick(position, true));
                velocity = new Vec3(delta.x * friction, 0.0, delta.z * friction);
            }

            // Released, still crouched from the previous keys and past the edge: the hidden walking velocity moves.
            f.input(SPRINT);
            delta = velocity.add(ClientMoveInput.of(f, SPRINT, 0.3F, 0.1F, yaw));
            assertExact(f.tick(position.add(delta.x, -0.0784000015258789, delta.z), false));
        }
    }

    /**
     * A sprinting client whose movement_speed the server replaced with a minecraft:sprinting modifier that quadruples
     * it. ClientPacketListener#handleUpdateAttributes keeps that modifier while the client keeps sprinting, so the
     * edge hides the quadrupled input; LivingEntity#setSprinting puts vanilla's modifier back only when sprinting
     * restarts.
     */
    @Test
    void aServerSprintModifierHoldsUntilSprintingRestarts() throws Exception {
        try (var f = new JavaTickFixture()) {
            drop(f);
            // Facing the drop, the edge backs off the whole input, so the moves alone do not show its speed.
            float yaw = 0.0F;
            rotate(f, yaw, 0.0F);
            Vec3 position = new Vec3(0.3, FLOOR_Y, 1.299999);
            Vec3 velocity = new Vec3(0.002, 0.0, 0.05);
            f.place(position, velocity);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(SPRINT_SNEAK);
            double friction = 0.6F * 0.91F;
            float sprintSpeed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT_SNEAK, 1.0F, sprintSpeed, yaw));
            position = position.add(delta.x, 0.0, 0.0);
            assertExact(f.tick(position, true));
            velocity = new Vec3(delta.x * friction, 0.0, delta.z * friction);

            var quadrupled =
                    new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            var attributes = new ClientboundUpdateAttributes(
                    f.player.entityID,
                    List.of(new AttributeSnapshot("minecraft:movement_speed", 0.1F, List.of(quadrupled))));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, attributes, false);
            f.player.packetEntityReplication.onUpdateAttributes(event, f.player, attributes);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());

            float quadrupledSpeed = (float) ((double) 0.1F * 4.0D);
            for (int tick = 0; tick < 2; tick++) {
                delta = velocity.add(ClientMoveInput.of(f, SPRINT_SNEAK, 0.3F, quadrupledSpeed, yaw));
                position = position.add(delta.x, 0.0, 0.0);
                assertExact(f.tick(position, true));
                velocity = new Vec3(delta.x * friction, 0.0, delta.z * friction);
            }
            f.input(SPRINT);
            delta = velocity.add(ClientMoveInput.of(f, SPRINT, 0.3F, quadrupledSpeed, yaw));
            position = position.add(delta.x, -0.0784000015258789, delta.z);
            assertExact(f.tick(position, false));
            velocity = new Vec3(delta.x * friction, 0.0, delta.z * friction);
            // LivingEntity#travelInAir off the edge: Player#getFlyingSpeed from the sprint flag, 0.91F, gravity, 0.98F.
            double fall = (-0.0784000015258789 - 0.08D) * (double) 0.98F;
            for (int tick = 0; tick < 3; tick++) {
                delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, 0.025999999F, yaw));
                position = position.add(delta.x, fall, delta.z);
                assertExact(f.tick(position, false));
                velocity = new Vec3(delta.x * (double) 0.91F, 0.0, delta.z * (double) 0.91F);
                fall = (fall - 0.08D) * (double) 0.98F;
            }

            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.move(position, false);
            f.tickEnd();
            command(f, PlayerCommandAction.START_SPRINTING);
            f.move(position, false);
            f.tickEnd();
            assertEquals(
                    sprintSpeed,
                    (float) f.player.compensatedEntities.getMaxPlayerMovementSpeed(false, ClientSprintState.Outcome.ANY));
        }
    }

    /**
     * A riptide spin with a RemotePlayer in reach can reset sprinting after travel through Player#causeExtraKnockback,
     * so its tick carries no sprint input the edge hides. Player#attack reaches that reset only through a hit that
     * hurts, and no other LivingEntity's Entity#hurtClient returns true, so an armor stand leaves the input carried.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyARemotePlayerInReachDropsTheSprintInputTheEdgeHides(boolean player) throws Exception {
        try (var f = new JavaTickFixture()) {
            drop(f);
            float yaw = 717.3444213867188F;
            rotate(f, yaw, 54.989532470703125F);
            Vec3 position = new Vec3(0.3, FLOOR_Y, 1.299999);
            Vec3 velocity = new Vec3(0.002, 0.0, 0.05);
            f.place(position, velocity);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            f.input(SPRINT_SNEAK);
            double friction = 0.6F * 0.91F;
            float sprintSpeed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT_SNEAK, 1.0F, sprintSpeed, yaw));
            position = position.add(delta.x, 0.0, 0.0);
            assertExact(f.tick(position, true));
            velocity = new Vec3(delta.x * friction, 0.0, delta.z * friction);

            f.player.riptideSpinAttackTicks = 1;
            if (player) {
                f.addRemotePlayer(71, position.add(0, 0, 0.5), GameMode.SURVIVAL);
            } else {
                f.player.compensatedEntities.addEntity(71, EntityTypeIds.ARMOR_STAND, position.add(0, 0, 0.5), 0, 0, 0);
            }
            delta = velocity.add(ClientMoveInput.of(f, SPRINT_SNEAK, 0.3F, sprintSpeed, yaw));
            position = position.add(delta.x, 0.0, 0.0);
            PredictionResult result = f.tick(position, true);
            double carriedX = delta.x * friction;
            double carriedZ = delta.z * friction;
            assertEquals(
                    !player,
                    f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                            .anyMatch(next -> Math.abs(next.x - carriedX) < 1.0E-7
                                    && Math.abs(next.z - carriedZ) < 1.0E-7),
                    "the hidden sprint input: " + describe(result));
        }
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
    }

    /** The fixture floor with its blocks from z = 1 removed. */
    private static void drop(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int z = 1; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    private static void assertExact(
            JavaTickFixture f, float yaw, float pitch, double x, double y, double z, boolean onGround) {
        rotate(f, yaw, pitch);
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), describe(result));
    }

    private static void rotate(JavaTickFixture f, float yaw, float pitch) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
    }

    /** A recorded position moved onto the fixture: its floor top -2 becomes FLOOR_Y and its edge z = -49 becomes 1. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 272.0, y + 2.0 + FLOOR_Y, z + 50.0);
    }
}
