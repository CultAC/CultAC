package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.InsideBlock;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.phys.Vec3;

/** Runs the shared push handler on packet delta, after travel and end-of-tick velocity effects. */
final class BedrockEndTickBlockPush {
    private BedrockEndTickBlockPush() { }

    static BedrockMovementState apply(BedrockMovementResult movement, BedrockMovementState state,
            Vec3d reportedVelocity) {
        if (reportedVelocity == null || !movement.travelActive() || state.isVehicle()) return state;
        var endpoint = state.physicalFeetPosition();
        var dimensions = movement.movementContext().playerDimensionsState();
        var box = GetBoundingBox.getBoundingBoxFromPosAndSize(endpoint.x(), endpoint.y(), endpoint.z(),
                (float) dimensions.width(), (float) dimensions.height());
        var shapes = movement.movementContext().worldState().blockCollisionWorld().collisionBoxes().stream()
                .map(shape -> new SimpleCollisionBox(shape.minX(), shape.minY(), shape.minZ(),
                        shape.maxX(), shape.maxY(), shape.maxZ())).toList();
        boolean overlap = WorldStageBuilder.moveTowardsClosestSpaceBedrock(box, shapes);
        var velocity = state.velocity();
        var pushed = InsideBlock.apply(new PredVector(new Vec3(velocity.x(), velocity.y(), velocity.z())),
                new Vec3(reportedVelocity.x(), reportedVelocity.y(), reportedVelocity.z()), overlap);
        return state.withVelocityAndCollisionFlags(new Vec3d(pushed.x, pushed.y, pushed.z), state.collisionFlags());
    }
}
