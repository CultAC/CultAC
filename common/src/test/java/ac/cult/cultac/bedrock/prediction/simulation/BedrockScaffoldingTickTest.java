package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
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
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Scaffolding ticks replayed from a sneaking client teleported onto a column and later inside it. */
public final class BedrockScaffoldingTickTest {
    private static final double STEP = 0.15F;
    private static final Vec3d RESTING = new Vec3d(0.0D, -0.0784D, 0.0D);

    // Teleported onto the top of a column with sneak held, the client stays put on the tick
    // it handles the teleport and on the next one, and descends 0.15 a tick from the third.
    @Test
    public void descentStartsOneTickAfterTheTeleportTick() {
        BedrockMovementContext world = column(0, 4);
        var top = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 5.0D, 0.5D), RESTING, BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND);
        assertEquals(BedrockClimbableContact.NONE, top.climbableContact());

        var teleport = tick(top, sneak(1L, false), world, true);
        assertFalse(teleport.travelActive());
        assertEquals(0.0D, moved(teleport).y(), 0.0D);
        assertEquals(BedrockClimbableContact.NONE, teleport.predictedState().climbableContact());

        var standing = tick(teleport.predictedState(), sneak(2L, false), world, false);
        assertEquals(0.0D, moved(standing).y(), 0.0D);
        assertEquals(-0.0784D, standing.predictedVelocity().y(), 1.0E-6D);
        assertTrue(standing.predictedState().climbableContact().descendAllowed());

        var descending = tick(standing.predictedState(), sneak(3L, false), world, false);
        assertEquals(-STEP, moved(descending).y(), 2.0E-6D);
    }

    // Inside the bottom block of a column with jump and sneak both held, the client rises
    // 0.15 a tick (120.15, 120.30, 120.45 from the floor at 120), as it does with jump alone:
    // the row below its feet holds no scaffolding to fall through.
    @Test
    public void heldJumpAscendsInTheBottomBlockEvenWithSneakHeld() {
        BedrockMovementContext world = column(64, 67);
        for (boolean sneaking : new boolean[] {true, false}) {
            var state = inside(world, 64.0D, RESTING, true);
            for (int tick = 1; tick <= 3; tick++) {
                var result = tick(state, sneak(tick, true).withSneak(sneaking), world, false);
                assertEquals(STEP, moved(result).y(), 2.0E-6D);
                assertEquals(64.0D + STEP * tick, result.predictedPosition().y(), 2.0E-5D);
                state = result.predictedState();
            }
        }
    }

    // Higher in the column sneak falls through at 0.15 a tick, with or without jump held.
    @Test
    public void sneakFallsThroughWhileTheRowBelowHoldsScaffolding() {
        BedrockMovementContext world = column(64, 67);
        for (boolean jumping : new boolean[] {false, true}) {
            var result = tick(inside(world, 66.0D, Vec3d.ZERO, false), sneak(1L, jumping), world, false);
            assertEquals(-STEP, moved(result).y(), 2.0E-6D);
        }
    }

    // A capture of a sneaking descent to the foot of a column: from feet 121.100474 the
    // client steps -0.15 and reports -0.147; from 120.95047, inside the bottom block, it
    // steps that -0.147 in free fall and reports -0.22246.
    @Test
    public void forcedDescentEndsWhereTheRowBelowIsNotScaffolding() {
        BedrockMovementContext world = column(120, 124);
        var above = inside(world, 121.100474D, new Vec3d(0.0D, -0.147D, 0.0D), false);
        var last = tick(above, sneak(1L, false), world, false);
        assertEquals(-STEP, moved(last).y(), 2.0E-5D);
        var carried = BedrockForwardTick.finish(last, last.predictedState());
        // The carry that reports the drag of the forced step, -0.147, is the client's.
        var bottom = carried.stream()
                .filter(next -> Math.abs(next.velocity().y() + 0.147D) < 1.0E-6D)
                .findFirst()
                .orElseThrow();
        assertTrue(bottom.climbableContact().scaffolding());
        assertFalse(bottom.climbableContact().descendAllowed());

        var falling = tick(bottom, sneak(2L, false), world, false);
        assertEquals(-0.147D, moved(falling).y(), 2.0E-5D);
        assertEquals(-0.22246D, falling.predictedVelocity().y(), 1.0E-5D);
    }

    private static BedrockMovementState inside(
            BedrockMovementContext world, double feetY, Vec3d velocity, boolean onGround) {
        Vec3d feet = new Vec3d(0.5D, feetY, 0.5D);
        var contact = BedrockClimbableContact.fromBlockWorld(world.worldState().blockCollisionWorld(), feet, 0.6D, 1.8D);
        assertTrue(contact.scaffolding());
        return BedrockMovementState.fromPhysicalFeet(
                        feet,
                        velocity,
                        BedrockInputFrame.idle(0L),
                        onGround ? BedrockCollisionFlags.ON_GROUND : BedrockCollisionFlags.AIR)
                .withClimbableContact(contact);
    }

    private static Vec3d moved(BedrockMovementResult result) {
        return result.predictedPosition().subtract(result.previousState().physicalFeetPosition());
    }

    private static BedrockMovementResult tick(
            BedrockMovementState state, Keys keys, BedrockMovementContext world, boolean handlesTeleport) {
        BedrockInputFrame frame = keys.frame();
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(world), state, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        snapshot,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        handlesTeleport,
                        Vec3d.ZERO))
                .getFirst()
                .movementResult();
    }

    private static Keys sneak(long tick, boolean jump) {
        return new Keys(tick, jump, true);
    }

    private record Keys(long tick, boolean jump, boolean sneak) {
        Keys withSneak(boolean held) {
            return new Keys(tick, jump, held);
        }

        BedrockInputFrame frame() {
            return new BedrockInputFrame(
                    tick, 0.0F, 0.0F, jump, sneak, false, sneak ? Set.of("SNEAKING", "SNEAK_DOWN") : Set.of());
        }
    }

    /** A column of scaffolding at x 0, z 0 on a stone floor. Only its top block holds a player up. */
    private static BedrockMovementContext column(int bottomY, int topY) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(0, bottomY - 1, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(-4.0D, bottomY - 1.0D, -4.0D, 4.0D, bottomY, 4.0D))));
        for (int y = bottomY; y <= topY; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(0, y, 0),
                    "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                    "minecraft:scaffolding",
                    y == topY ? List.of(new WorldCollisionBox(0.0D, y + 0.875D, 0.0D, 1.0D, y + 1.0D, 1.0D)) : List.of(),
                    Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING)));
        }
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
