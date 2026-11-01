package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A sprinting 26.3 client on open ground whose movement_speed the server replaced with a minecraft:sprinting modifier
 * that quadruples it. ClientPacketListener#handleUpdateAttributes keeps that modifier while the client keeps sprinting,
 * so LivingEntity#getFrictionInfluencedSpeed gives the quadrupled speed on the ground.
 */
class JavaSprintModifierGroundTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final float YAW = 30.0F;
    private static final float CANONICAL = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final float QUADRUPLED = (float) ((double) 0.1F * 4.0D);
    // Player#getAttackStrengthScale passes 0.9 after five ticks at the default attack speed of 4.
    private static final int CHARGE_TICKS = 5;
    private static final float INTO_CORNER = -45.0F;
    private static final float AWAY = 135.0F;
    private static final AttributeModifier QUADRUPLED_MODIFIER =
            new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    @Test
    void aSprintAtTheServersModifierSpeedIsExact() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = startSprint(f);
            Vec3 velocity = carried(f, CANONICAL);
            holdQuadrupledModifier(f);
            for (int tick = 0; tick < 4; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, YAW));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                velocity = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
            }
        }
    }

    @Test
    void aSprintPastTheServersModifierSpeedFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = startSprint(f);
            Vec3 velocity = carried(f, CANONICAL);
            holdQuadrupledModifier(f);
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED * 1.5F, YAW));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    /**
     * Player#causeExtraKnockback damps deltaMovement by 0.6 and calls setSprinting(false) together, and aiStep's
     * re-sprint puts vanilla's modifier back. A move only the damped velocity at vanilla's speed reproduces proves the
     * reset happened, so the next tick cannot travel at the server's modifier's speed.
     */
    @Test
    void aMoveThatProvesAPossibleAttackResetDropsTheServersModifier() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 velocity = provenReset(f);
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, YAW));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    @Test
    void aMoveThatProvesAPossibleAttackResetSprintsOnAtVanillasSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 velocity = provenReset(f);
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, YAW));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * A sprint into a wall that the attack tick's move reaches with or without the damping, at either speed:
     * Entity#collide ends both on the wall, so the move cannot tell the outcomes apart and both modifiers stay possible.
     */
    @Test
    void aWallThatHidesAPossibleAttackResetKeepsBothModifiers() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -2; x <= 2; x++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 1), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            position = startSprint(f, 0.0F, CHARGE_TICKS);
            holdQuadrupledModifier(f);
            attack(f);
            Vec3 atWall = new Vec3(position.x, FLOOR_Y, 1.0 - 0.6F / 2.0F);
            PredictionResult result = f.tick(atWall, true);
            assertTrue(exact(result), describe(result));
            assertSame(QUADRUPLED_MODIFIER, f.player.sprintState.heldModifier());
            assertTrue(f.player.sprintState.canonicalPossible());

            // LocalPlayer#shouldStopRunSprinting: the head-on collision stops the sprint before the next travel.
            var stop = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.STOP_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, stop), f.player, stop);
            result = f.tick(atWall, true);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * After a corner hid a possible attack reset, a move away from it that only vanilla's speed reproduces proves the
     * reset: the server's modifier no longer moves the next tick.
     */
    @Test
    void aMoveOnlyVanillasSpeedExplainsEndsATie() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 corner = cornerTie(f);
            Vec3 velocity = leaveCorner(f, corner, CANONICAL);
            assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, f.player.sprintState.heldModifier());
            assertFalse(f.player.sprintState.outcomeOpen());
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, AWAY));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    /** The mirror: a move only the server's modifier reproduces proves no reset happened. */
    @Test
    void aMoveOnlyTheServersSpeedExplainsEndsATie() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 corner = cornerTie(f);
            Vec3 velocity = leaveCorner(f, corner, QUADRUPLED);
            assertSame(QUADRUPLED_MODIFIER, f.player.sprintState.heldModifier());
            assertFalse(f.player.sprintState.outcomeOpen());
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, AWAY));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    /**
     * A move off the corner at vanilla's speed that no candidate reproduces exactly: prediction carries on with the
     * committed candidate's movement, so it carries on with that candidate's modifier too.
     */
    @Test
    void aRejectedMoveCommitsTheModifierOfTheCandidateItCarriesOn() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 corner = cornerTie(f);
            f.player.xRot = AWAY;
            Vec3 delta = ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, AWAY);
            position = corner.add(delta.x + 0.0008, 0.0, delta.z + 0.0008);
            PredictionResult result = f.tick(position, true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
            assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, f.player.sprintState.heldModifier());
            assertFalse(f.player.sprintState.outcomeOpen());
            Vec3 velocity = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
            delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, AWAY));
            result = f.tick(position.add(delta.x, 0.0, delta.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    /**
     * A setback to a position from before a possible attack reset: the teleport moves the client back but leaves the
     * modifier the attack and aiStep's re-sprint gave it (Player#causeExtraKnockback, LocalPlayer#aiStep,
     * ClientPacketListener#handlePlayerPosition), so the move after it may carry on at vanilla's speed.
     */
    @Test
    void aSetbackPastAPossibleAttackResetKeepsVanillasModifierPossible() throws Exception {
        try (var f = new JavaTickFixture()) {
            position = startSprint(f, YAW, CHARGE_TICKS);
            Vec3 velocity = carried(f, CANONICAL);
            holdQuadrupledModifier(f);
            for (int tick = 0; tick < 3; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, YAW));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                velocity = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
            }
            var anchor = f.player.getSetbackTeleportUtil().lastKnownGoodPosition;
            assertNotNull(anchor);
            assertNotNull(anchor.getSprintOutcomes());
            Vec3 anchorPosition = position;
            Vec3 anchorVelocity = velocity;

            attack(f);
            Vec3 damped = new Vec3(velocity.x * 0.6, 0.0, velocity.z * 0.6);
            Vec3 delta = damped.add(ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, YAW));
            PredictionResult result = f.tick(position.add(delta.x + 0.0008, 0.0, delta.z + 0.0008), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));

            // SetbackTeleportUtil#blockMovementsUntilResync returns to the anchor's modifier state.
            f.player.sprintState.restore(anchor.getSprintOutcomes());
            assertTrue(f.player.sprintState.canonicalPossible());
            f.place(anchorPosition, anchorVelocity);
            delta = anchorVelocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, YAW));
            result = f.tick(anchorPosition.add(delta.x, 0.0, delta.z), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * A possible attack reset on a move that is set back before it is simulated: the client travelled that tick with
     * whichever modifier Player#causeExtraKnockback and LocalPlayer#aiStep left, and the teleport keeps it
     * (ClientPacketListener#handleMovePlayer), so the first move after the correction may carry on at vanilla's speed.
     */
    @Test
    void aResetOnAMoveSetBackUnsimulatedKeepsVanillasModifierPossible() throws Exception {
        try (var f = new JavaTickFixture()) {
            position = startSprint(f, YAW, CHARGE_TICKS);
            Vec3 velocity = carried(f, CANONICAL);
            holdQuadrupledModifier(f);
            for (int tick = 0; tick < 3; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, YAW));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                velocity = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
            }

            attack(f);
            f.move(position.add(21.0, 0.0, 0.0), true);
            f.tickEnd();
            f.fixture.channel.runPendingTasks();
            var setbacks = f.player.getSetbackTeleportUtil();
            assertTrue(setbacks.isPendingSetback(), setbacks.getDebugStrings());

            var correction = setbacks.getRequiredSetBack();
            Vec3 target = correction.getTeleportData().getLocation();
            f.pongThrough(f.player.lastTransactionSent.get());
            var accept = new ServerboundAcceptTeleportation(
                    correction.getTeleportData().getTeleportId(), new Vec3d(target.x, target.y, target.z), YAW, 0.0F);
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, accept);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, accept);
            assertFalse(event.isCancelled());
            assertTrue(correction.isComplete());

            Vec3 delta = correction.getVelocity().add(ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, YAW));
            PredictionResult result = f.tick(target.add(delta.x, 0.0, delta.z), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * A sprint diagonally into a corner: Entity#collide ends the attack tick's move there at either outcome, and
     * LocalPlayer#isHorizontalCollisionMinor keeps the sprint, as the attempted move follows the input. The next move,
     * pressed into the corner, ends there at either speed too, so both modifiers stay possible.
     */
    private Vec3 cornerTie(JavaTickFixture f) {
        for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
            for (int i = -2; i <= 1; i++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(1, y, i), BlockIds.STONE.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(i, y, 1), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        position = startSprint(f, INTO_CORNER, CHARGE_TICKS);
        holdQuadrupledModifier(f);
        attack(f);
        Vec3 corner = new Vec3(1.0 - 0.6F / 2.0F, FLOOR_Y, 1.0 - 0.6F / 2.0F);
        PredictionResult result = f.tick(corner, true);
        assertTrue(exact(result), describe(result));
        assertSame(QUADRUPLED_MODIFIER, f.player.sprintState.heldModifier());
        assertTrue(f.player.sprintState.canonicalPossible());
        result = f.tick(corner, true);
        assertTrue(exact(result), describe(result));
        assertSame(QUADRUPLED_MODIFIER, f.player.sprintState.heldModifier());
        assertTrue(f.player.sprintState.canonicalPossible());
        return corner;
    }

    /** Turning away from the corner and sprinting off at {@code speed}; the velocity carried into the next tick. */
    private Vec3 leaveCorner(JavaTickFixture f, Vec3 corner, float speed) {
        f.player.xRot = AWAY;
        Vec3 delta = ClientMoveInput.of(f, SPRINT, 1.0F, speed, AWAY);
        position = corner.add(delta.x, 0.0, delta.z);
        PredictionResult result = f.tick(position, true);
        assertTrue(exact(result), describe(result));
        return new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
    }

    private Vec3 position;

    /**
     * Three ticks at the server's quadrupled speed, then an attack whose reset is only possible and a move with the
     * damped velocity at vanilla's speed; the velocity carried into the next tick.
     */
    private Vec3 provenReset(JavaTickFixture f) {
        position = startSprint(f, YAW, CHARGE_TICKS);
        Vec3 velocity = carried(f, CANONICAL);
        holdQuadrupledModifier(f);
        for (int tick = 0; tick < 3; tick++) {
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, YAW));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult result = f.tick(position, true);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            velocity = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
        }
        attack(f);
        Vec3 damped = new Vec3(velocity.x * 0.6, 0.0, velocity.z * 0.6);
        Vec3 delta = damped.add(ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, YAW));
        position = position.add(delta.x, 0.0, delta.z);
        PredictionResult result = f.tick(position, true);
        assertTrue(exact(result), describe(result));
        assertSame(CompensatedEntities.SPRINTING_SPEED_MODIFIER, f.player.sprintState.heldModifier());
        assertFalse(f.player.sprintState.canonicalPossible());
        return new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
    }

    /** MultiPlayerGameMode#attack on an entity the server never sent: the reset is only possible. */
    private static void attack(JavaTickFixture f) {
        var attack = new ServerboundInteract(99, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        new PacketPlayerAttack().onInteract(f.event(ServerboundPackets.INTERACT, attack), f.player, attack);
    }

    /** A first sprinting tick from rest at vanilla's sprint speed; the position it reached. */
    private static Vec3 startSprint(JavaTickFixture f) {
        return startSprint(f, YAW, 0);
    }

    /** {@link #startSprint(JavaTickFixture)} facing {@code yaw}, after {@code restTicks} ticks standing still. */
    private static Vec3 startSprint(JavaTickFixture f, float yaw, int restTicks) {
        f.player.xRot = yaw;
        f.player.yRot = 0.0F;
        Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
        f.place(start, Vec3.ZERO);
        for (int tick = 0; tick < restTicks; tick++) {
            PredictionResult result = f.tick(start, true);
            assertTrue(exact(result), describe(result));
        }
        var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
        f.input(SPRINT);
        Vec3 delta = ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, yaw);
        Vec3 position = new Vec3(0.5 + delta.x, FLOOR_Y, 0.5 + delta.z);
        PredictionResult result = f.tick(position, true);
        assertTrue(exact(result), describe(result));
        return position;
    }

    /** The first tick's delta after LivingEntity#travelInAir's ground friction. */
    private static Vec3 carried(JavaTickFixture f, float speed) {
        Vec3 delta = ClientMoveInput.of(f, SPRINT, 1.0F, speed, YAW);
        return new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
    }

    private static void holdQuadrupledModifier(JavaTickFixture f) {
        var attributes = new ClientboundUpdateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot("minecraft:movement_speed", 0.1F, List.of(QUADRUPLED_MODIFIER))));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, attributes, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, attributes);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
