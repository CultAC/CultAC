package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The ground jump launches and arms its cooldown on the same tick, and only on an on-foot tick. */
public final class BedrockGroundJumpTickTest {
    private static final Vec3d FEET = new Vec3d(0.5D, 64.0D, 0.5D);
    private static final Vec3d RESTING = new Vec3d(0.0D, -0.0784D, 0.0D);
    private static final double JUMP = 0.42F;
    // The client's position is a float: 64 + 0.42 lands on this value.
    private static final double LAUNCHED_Y = 64.41999816894531D;

    // A capture shows a grounded client holding jump on the tick it handles a teleport:
    // position unchanged with delta (0, 0.42, 0), then +0.42 with delta y 0.3332.
    @Test
    public void teleportTickLaunchesAndTheNextTickMovesWithIt() {
        var teleport = tick(grounded(RESTING), held(1L), BedrockMobJumpComponentState.DEFAULT, true, walking());
        var launched = teleport.movementResult();

        assertFalse(launched.travelActive());
        assertTrue(launched.groundJumpApplied());
        assertEquals(FEET, launched.predictedPosition());
        assertEquals(new Vec3d(0.0D, JUMP, 0.0D), launched.predictedVelocity());
        assertEquals(10, teleport.mobJumpComponent().jumpCooldownTicks());

        var carried = BedrockForwardTick.finish(launched, launched.predictedState());
        var next = tick(carried, held(2L), teleport.mobJumpComponent(), false, walking());
        var moved = next.movementResult();

        assertTrue(moved.travelActive());
        assertFalse(moved.groundJumpApplied());
        assertEquals(LAUNCHED_Y, moved.predictedPosition().y(), 0.0D);
        assertEquals((JUMP - 0.08D) * 0.98D, moved.predictedVelocity().y(), 1.0E-6D);
        assertEquals(9, next.mobJumpComponent().jumpCooldownTicks());
    }

    // A replay of a server rotation on a sprint-jumping client: the teleport tick reports
    // the rest velocity plus (0.2, 0.42 + 0.0784, 0) at yaw -90, a 0.53703 velocity change.
    @Test
    public void teleportTickLaunchCarriesTheSprintImpulse() {
        var sprinting = grounded(RESTING).withSprinting(true);
        var frame = new BedrockInputFrame(1L, -90.0F, 0.0F, true, false, true);
        var launched = tick(sprinting, frame, BedrockMobJumpComponentState.DEFAULT, true, walking())
                .movementResult();

        Vec3d change = launched.predictedVelocity().subtract(RESTING);
        assertEquals(0.2D, change.x(), 1.0E-6D);
        assertEquals(0.4984D, change.y(), 1.0E-6D);
        assertEquals(0.0D, change.z(), 1.0E-6D);
        assertEquals(0.53703D, Math.sqrt(change.x() * change.x() + change.y() * change.y()), 1.0E-5D);
    }

    // The same capture: a teleport handled eight ticks after a launch, grounded with jump
    // held, reports delta y -0.0784, and one handled in the air does too.
    @Test
    public void teleportTickDoesNotLaunchInsideTheCooldownOrInTheAir() {
        var cooling = tick(grounded(RESTING), held(1L), new BedrockMobJumpComponentState(false, 3), true, walking());
        assertFalse(cooling.movementResult().groundJumpApplied());
        assertEquals(RESTING, cooling.movementResult().predictedVelocity());
        assertEquals(2, cooling.mobJumpComponent().jumpCooldownTicks());

        var airborne = BedrockMovementState.fromPhysicalFeet(
                FEET, RESTING, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        var falling = tick(airborne, held(1L), BedrockMobJumpComponentState.DEFAULT, true, walking());
        assertFalse(falling.movementResult().groundJumpApplied());
        assertEquals(RESTING, falling.movementResult().predictedVelocity());
        assertEquals(0, falling.mobJumpComponent().jumpCooldownTicks());
    }

    // A replay of a vehicle removed after 45 rider ticks with jump held: the client launches
    // on its first on-foot tick.
    @Test
    public void heldTicksNeverArmTheCooldownSoTheFirstOnFootTickLaunches() {
        BedrockMovementState state = grounded(Vec3d.ZERO);
        BedrockMobJumpComponentState component = BedrockMobJumpComponentState.DEFAULT;
        for (long riderTick = 1L; riderTick <= 45L; riderTick++) {
            var held = BedrockImmobileTick.advance(
                    state, held(riderTick), BedrockWorldSnapshot.fromContext(walking()), component, false);
            state = held.state();
            component = held.mobJumpComponent();
            assertEquals(0, component.jumpCooldownTicks());
        }

        var onFoot = tick(state, held(46L), component, false, walking());
        assertTrue(onFoot.movementResult().groundJumpApplied());
        assertEquals(LAUNCHED_Y, onFoot.movementResult().predictedPosition().y(), 0.0D);
        assertEquals(10, onFoot.mobJumpComponent().jumpCooldownTicks());
    }

    @Test
    public void heldTicksLetARunningCooldownCountDown() {
        BedrockMovementState state = grounded(Vec3d.ZERO);
        BedrockMobJumpComponentState component = new BedrockMobJumpComponentState(false, 10);
        for (long riderTick = 1L; riderTick <= 12L; riderTick++) {
            var held = BedrockImmobileTick.advance(
                    state, held(riderTick), BedrockWorldSnapshot.fromContext(walking()), component, false);
            state = held.state();
            component = held.mobJumpComponent();
            assertEquals(Math.max(0L, 10L - riderTick), component.jumpCooldownTicks());
        }
    }

    // A replay of jump pressed while flying against the ground: the client launches on its
    // first grounded on-foot tick six ticks later, not ten ticks after the press.
    @Test
    public void flyingNeitherLaunchesNorArmsTheCooldown() {
        var flying = tick(grounded(Vec3d.ZERO), held(1L), BedrockMobJumpComponentState.DEFAULT, false, flying());
        assertFalse(flying.movementResult().groundJumpApplied());
        assertEquals(0, flying.mobJumpComponent().jumpCooldownTicks());

        var onFoot = tick(grounded(Vec3d.ZERO), held(2L), flying.mobJumpComponent(), false, walking());
        assertTrue(onFoot.movementResult().groundJumpApplied());
        assertEquals(10, onFoot.mobJumpComponent().jumpCooldownTicks());
    }

    // A teleport onto a ladder the last move never touched, jump held: the client reports
    // delta (0, 0.2, 0) at the teleported position and climbs by it on the next tick.
    @Test
    public void teleportTickClimbsTheLadderAtTheTeleportedFeet() {
        var ladder = context(new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 64, 0), "minecraft:ladder", "minecraft:ladder", List.of()))));
        var airborne = airborne();
        assertFalse(airborne.climbableContact().climbing());

        var teleport = tick(airborne, held(1L), BedrockMobJumpComponentState.DEFAULT, true, ladder);
        var climbing = teleport.movementResult();

        assertFalse(climbing.travelActive());
        assertFalse(climbing.groundJumpApplied());
        assertEquals(FEET, climbing.predictedPosition());
        assertEquals(new Vec3d(0.0D, 0.2F, 0.0D), climbing.predictedVelocity());
        assertEquals(0, teleport.mobJumpComponent().jumpCooldownTicks());
    }

    // The same teleport tick without a ladder at the feet keeps the velocity it started with.
    @Test
    public void teleportTickBesideALadderDoesNotClimb() {
        var ladder = context(new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(1, 64, 0), "minecraft:ladder", "minecraft:ladder", List.of()))));
        var airborne = airborne();

        var teleport = tick(airborne, held(1L), BedrockMobJumpComponentState.DEFAULT, true, ladder);

        assertEquals(Vec3d.ZERO, teleport.movementResult().predictedVelocity());
    }

    // A teleport into still water with jump held: the swim-up impulse alone, no gravity or drag.
    @Test
    public void teleportTickInWaterAddsTheSwimUpImpulse() {
        var water = context(new BlockCollisionWorld(List.of(water(0, 64, 0, 0), water(0, 65, 0, 0))));
        var airborne = airborne();

        var teleport = tick(airborne, held(1L), BedrockMobJumpComponentState.DEFAULT, true, water);

        assertEquals(FEET, teleport.movementResult().predictedPosition());
        assertEquals(new Vec3d(0.0D, 0.04F, 0.0D), teleport.movementResult().predictedVelocity());
    }

    // A teleport into flowing water, nothing held: the client reports the flow push alone.
    @Test
    public void teleportTickInFlowingWaterTakesTheFlowPush() {
        var water = context(new BlockCollisionWorld(List.of(
                water(0, 64, -1, 0),
                water(0, 64, 0, 1),
                water(0, 64, 1, 2),
                water(0, 63, -1, 0),
                water(0, 63, 0, 0),
                water(0, 63, 1, 0))));
        var airborne = airborne();

        var teleport = tick(airborne, BedrockInputFrame.idle(1L), BedrockMobJumpComponentState.DEFAULT, true, water);

        assertEquals(FEET, teleport.movementResult().predictedPosition());
        assertEquals(new Vec3d(0.0D, 0.0D, 0.014D), teleport.movementResult().predictedVelocity());
    }

    private static BedrockMovementState airborne() {
        return BedrockMovementState.fromPhysicalFeet(
                FEET, Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
    }

    private static PlacedBlockCollision water(int x, int y, int z, int level) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z), "minecraft:water[level=" + level + "]", "minecraft:water", List.of());
    }

    private static BedrockSimulation.Candidate tick(
            BedrockMovementState state,
            BedrockInputFrame frame,
            BedrockMobJumpComponentState component,
            boolean handlesTeleport,
            BedrockMovementContext context) {
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), state, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        state, frame, frame.intent(), snapshot, false, 0.0D, component, true, handlesTeleport))
                .getFirst();
    }

    private static BedrockMovementState grounded(Vec3d velocity) {
        return BedrockMovementState.fromPhysicalFeet(
                FEET, velocity, BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND);
    }

    private static BedrockInputFrame held(long tick) {
        return new BedrockInputFrame(tick, 0.0F, 0.0F, true, false, false, Set.of());
    }

    private static BedrockMovementContext walking() {
        return context(MovementModifierState.NONE);
    }

    private static BedrockMovementContext flying() {
        return context(
                new MovementModifierState(false, true, true, false, 0.05D, false, false, false, false, 0.35D, 0L));
    }

    private static BedrockMovementContext context(BlockCollisionWorld world) {
        return context(MovementModifierState.NONE, world);
    }

    private static BedrockMovementContext context(MovementModifierState modifiers) {
        return context(modifiers, BlockCollisionWorld.EMPTY);
    }

    private static BedrockMovementContext context(MovementModifierState modifiers, BlockCollisionWorld world) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                modifiers,
                PlayerDimensionsState.DEFAULT);
    }
}
