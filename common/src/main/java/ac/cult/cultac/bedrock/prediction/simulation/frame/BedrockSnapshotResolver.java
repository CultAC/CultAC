package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PowderSnowContactState;
import ac.cult.cultac.bedrock.prediction.world.StandingSurfaceState;
import java.util.ArrayList;

public final class BedrockSnapshotResolver {
    private BedrockSnapshotResolver() {
    }

    public static BedrockWorldSnapshot forState(
        BedrockWorldSnapshot snapshot,
        BedrockMovementState state,
        BedrockInputFrame frame
    ) {
        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
            state, snapshot.playerDimensionsState(), frame
        );
        snapshot = snapshot.withPlayerDimensions(dimensions);
        BedrockMovementContext context = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
            snapshot.movementContext(), state.physicalFeetPosition(), dimensions
        );
        snapshot = snapshot.withMovementContext(context);
        BedrockClimbableContact climbable = snapshot.climbableContactAt(state.physicalFeetPosition(), dimensions);
        PowderSnowContactState powderSnow = BedrockPowderSnowContactResolver.fromBlockWorld(
            dimensions.width(),
            dimensions.height(),
            state.physicalFeetPosition(),
            snapshot.blockCollisionWorld(),
            context.equipmentState().leatherBoots()
        );
        StandingSurfaceState standing = BedrockStandingSurfaceResolver.fromBlockWorld(
            state.physicalFeetPosition(), snapshot.blockCollisionWorld(), dimensions
        );
        BlockMovementSlowdownState slowdown = BedrockBlockMovementSlowdownResolver.nextState(
            context, state.physicalFeetPosition(), dimensions
        );
        return snapshot.withInitialContacts(
            climbable,
            powderSnow,
            standing,
            slowdown,
            honeySlide(snapshot, dimensions.width())
        );
    }

    private static HoneySlideState honeySlide(BedrockWorldSnapshot snapshot, double actorWidth) {
        ArrayList<BlockPosition> positions = new ArrayList<>();
        for (PlacedBlockCollision block : snapshot.blockCollisionWorld().blocks()) {
            if (block.hasContactBehavior(PlacedBlockCollision.BlockContactBehavior.HONEY)) {
                positions.add(block.position());
            }
        }
        return positions.isEmpty() ? HoneySlideState.NONE : HoneySlideState.active(actorWidth, positions);
    }
}
