package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A client sprint-swimming in deep water attacks a player it may not hold. Minecraft#tick's handleKeybinds runs
 * Player#attack before LocalPlayer#tick, so a causeExtraKnockback that ran setSprinting(false) leaves the swim in
 * Entity#updateSwimming, and aiStep re-sprints without a STOP_SPRINTING; Player#updatePlayerPose then stands. The next
 * updateSwimming swims again: the client is sprinting, LocalPlayer#isUnderWater and in water at Entity#blockPosition.
 */
class JavaSwimExitEyeTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final float YAW = 30.0F;
    // LivingEntity#travelInWater's sprinting slowdown with no water_movement_efficiency.
    private static final float SLOW_DOWN = 0.9F;
    private static final double SWIM = Pose.SWIMMING.eyeHeight;
    private static final double STAND = Pose.STANDING.eyeHeight;

    private Vec3 position;

    @Test
    void aPossibleAttackResetActsFromTheSwimOrTheStandingEyeUntilTheSwimResumes() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 velocity = swim(f);
            assertEquals(List.of(SWIM), f.player.getPossibleEyeHeights());

            attack(f);
            Vec3 delta = new Vec3(velocity.x * 0.6, 0.0, velocity.z * 0.6).add(input(f));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult attacked = f.tick(position, false);
            assertTrue(exact(attacked), describe(attacked));
            assertEquals(List.of(SWIM, STAND), f.player.getPossibleEyeHeights());

            delta = slowed(delta).add(input(f));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult next = f.tick(position, false);
            assertTrue(exact(next), describe(next));
            assertEquals(List.of(SWIM), f.player.getPossibleEyeHeights());
        }
    }

    @Test
    void theServersSharedFlagsKeepThePoseTheLastTickLeft() throws Exception {
        try (var f = new JavaTickFixture()) {
            attacked(f, swim(f));
            assertEquals(List.of(SWIM, STAND), f.player.getPossibleEyeHeights());

            sharedFlags(f, (byte) 0);
            assertEquals(List.of(SWIM, STAND), f.player.getPossibleEyeHeights());
        }
    }

    @Test
    void theStandingEyeStaysWhileAnyCarriedEyeLeavesTheClientOutOfTheSwim() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 velocity = attacked(f, swim(f));
            SimulationProcessor processor = f.player.checkManager.getSimulationProcessor();
            Field carried = SimulationProcessor.class.getDeclaredField("profileCarry");
            carried.setAccessible(true);
            JavaPredictionCarry carry = (JavaPredictionCarry) carried.get(processor);
            List<JavaPredictionCarry.State> states = new ArrayList<>(carry.states());
            for (JavaPredictionCarry.State state : carry.states()) states.add(dryEye(state));
            carried.set(processor, new JavaPredictionCarry(carry.actor(), carry.fallDistance(), states));

            // LocalPlayer#aiStep stops the sprint of the client whose eye is out of the water.
            f.command(PlayerCommandAction.STOP_SPRINTING);
            Vec3 delta = velocity.add(input(f));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult next = f.tick(position, false);
            assertTrue(exact(next), describe(next));
            assertEquals(List.of(SWIM, STAND), f.player.getPossibleEyeHeights());
        }
    }

    private static JavaPredictionCarry.State dryEye(JavaPredictionCarry.State state) {
        return new JavaPredictionCarry.State(
                state.velocity(),
                state.fallDistance(),
                state.stuckSpeed(),
                state.movementPatch(),
                state.pistonStart(),
                state.powderContact(),
                0,
                state.fluidTagOrder(),
                state.spinRebound(),
                state.fallDistanceMax(),
                state.vanillaMath());
    }

    /** The tick of a possible attack reset; the velocity carried into the next tick. */
    private Vec3 attacked(JavaTickFixture f, Vec3 velocity) {
        attack(f);
        Vec3 delta = new Vec3(velocity.x * 0.6, 0.0, velocity.z * 0.6).add(input(f));
        position = position.add(delta.x, 0.0, delta.z);
        PredictionResult attacked = f.tick(position, false);
        assertTrue(exact(attacked), describe(attacked));
        return slowed(delta);
    }

    /** The server's shared flags for the player, acknowledged by the client. */
    private static void sharedFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Sprint ticks from rest, enough for Player#getAttackStrengthScale to pass 0.9 at the default attack speed; the
     * velocity carried into the next tick.
     */
    private Vec3 swim(JavaTickFixture f) {
        f.water(new BlockPos(-8, FLOOR_Y, -4), new BlockPos(8, FLOOR_Y + 3, 8));
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        position = new Vec3(0.5, FLOOR_Y + 1, 0.5);
        f.place(position, Vec3.ZERO);
        f.command(PlayerCommandAction.START_SPRINTING);
        f.input(SPRINT);
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < 6; tick++) {
            Vec3 delta = velocity.add(input(f));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult result = f.tick(position, false);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            velocity = slowed(delta);
        }
        assertTrue(f.player.swimState.swimming());
        return velocity;
    }

    // LivingEntity#travelInWater's moveRelative speed with no water_movement_efficiency.
    private static Vec3 input(JavaTickFixture f) {
        return ClientMoveInput.of(f, SPRINT, 1.0F, 0.02F, YAW);
    }

    private static Vec3 slowed(Vec3 delta) {
        return new Vec3(delta.x * SLOW_DOWN, 0.0, delta.z * SLOW_DOWN);
    }

    private void attack(JavaTickFixture f) {
        Vec3 at = position.add(0.0, 0.0, 2.5);
        var add = new ClientboundAddEntity(99, "minecraft:player", new Vec3d(at.x, at.y, at.z), 0, 0, 0);
        var spawn = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
        f.player.packetEntityReplication.onAddEntity(spawn, f.player, add);
        spawn.tasksAfter().forEach(Runnable::run);
        var attack = new ServerboundInteract(99, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
        new PacketPlayerAttack().onInteract(f.event(ServerboundPackets.INTERACT, attack), f.player, attack);
    }
}
