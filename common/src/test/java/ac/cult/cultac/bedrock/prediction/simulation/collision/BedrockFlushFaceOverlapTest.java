package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BedrockActorBox;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.List;
import org.junit.Test;

public final class BedrockFlushFaceOverlapTest {
    private static final PlayerDimensionsState GLIDING = new PlayerDimensionsState(0.6F, 0.6F);
    /** A closed north-facing fence gate: its box spans z 0.375-0.625 and rises 1.5 blocks. */
    private static final BlockCollisionWorld CLOSED_GATE = new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
            new BlockPosition(274, 84, -64),
            "minecraft:oak_fence_gate[facing=north,open=false]",
            "minecraft:fence_gate",
            List.of(new WorldCollisionBox(274.0D, 84.0D, -63.625D, 275.0D, 85.5D, -63.375D)))));

    /**
     * Flag 83 (2026-11-12): a glide that rested flush against the gate face reported feet whose float rounding puts a
     * box built from them one float step inside the face. The rise clears the gate top and the client carried its
     * whole 0.811 along z into the next tick; leaving that overlap through the z face stopped z and zeroed it.
     */
    @Test
    public void aBoxOneFloatStepInsideAFaceTouchesItInsteadOfLeavingIt() {
        Vec3d start = new Vec3d(274.8726806640625D, 85.39336395263672D, -63.92499542236328D);
        Vec3d velocity = new Vec3d(0.557586669921875D, 0.1091766357421875D, 0.8110313415527344D);
        // The rebuilt box sits exactly one float step inside the gate face.
        float maxZ = (float) BedrockActorBox.create(start, GLIDING, BedrockCoordinateFrame.IDENTITY)
                .maxZ();
        assertEquals(Math.ulp(-63.625F), maxZ + 63.625F, 0.0F);

        var result = move(start, velocity);

        assertEquals(velocity.z(), result.velocity().z(), 0.0D);
        assertEquals(velocity.x(), result.velocity().x(), 0.0D);
        assertFalse(result.collisionFlags().toString(), result.collisionFlags().zCollision());
        assertFalse(result.collisionFlags().horizontalCollision());
        // The client's reported feet on that tick.
        assertEquals(-63.11396789550781F, (float) result.position().z(), 0.0F);
    }

    @Test
    public void aMoveItsOwnPhaseClipsStillStopsTheAxis() {
        Vec3d start = new Vec3d(274.5D, 84.5D, -64.5D);
        Vec3d velocity = new Vec3d(0.0D, 0.0D, 0.7D);

        var result = move(start, velocity);

        assertEquals(0.0D, result.velocity().z(), 0.0D);
        assertTrue(result.collisionFlags().zCollision());
        assertEquals(-63.625F, (float) result.collisionBox().maxZ(), 2.0E-6F);
    }

    private static BedrockEntityMove.Result move(Vec3d start, Vec3d velocity) {
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                        start, velocity, BedrockInputFrame.idle(11284L), BedrockCollisionFlags.AIR)
                .withGliding(true)
                .withPlayerDimensions(BedrockBoundingBoxMode.HORIZONTAL, GLIDING, true);
        return BedrockEntityMove.move(
                state,
                BedrockInputFrame.idle(11285L),
                CLOSED_GATE,
                GLIDING,
                start.add(velocity),
                velocity,
                velocity.y());
    }
}
