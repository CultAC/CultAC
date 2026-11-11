package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

final class BedrockBlockCollisionWorldSampler {
    private final BedrockFluidBlockSampler fluidBlocks = new BedrockFluidBlockSampler();
    private final BedrockSolidBlockSampler solidBlocks = new BedrockSolidBlockSampler(fluidBlocks);

    BedrockSampledBlockWorld sample(
            CultPlayer player,
            SimulationContext context,
            BedrockCollisionOverrideCatalog geometry,
            BedrockAuthInputFrame frame,
            BedrockPlayerContext playerContext,
            BedrockCollisionSampleRegion region) {
        if (player.compensatedWorld == null) {
            throw new IllegalStateException("missing compensated world for Bedrock block sampling");
        }

        return sample(
                player,
                geometry,
                trustedActorCollisionBox(context, playerContext),
                region.toQueryBox(),
                playerContext.wearingLeatherBoots(),
                collisionFallDistance(BedrockProfileState.previousState(context), playerContext, player));
    }

    /**
     * The region sampled before the tick is simulated. It is sized from the velocity carried into the tick, so a
     * move that this tick's impulses lengthen can leave it.
     */
    BedrockCollisionSampleRegion carriedMoveRegion(SimulationContext context, BedrockPlayerContext playerContext) {
        BedrockMovementState previousState = BedrockProfileState.previousState(context);
        return BedrockCollisionSampleRegion.around(
                trustedActorCollisionBox(context, playerContext),
                previousState == null ? 0.0D : previousState.velocity().length());
    }

    BedrockWorldSnapshot replayWorld(CultPlayer player, BedrockMovementState state, BedrockWorldSnapshot snapshot) {
        Vec3d feet = state.physicalFeetPosition();
        var dimensions = state.playerDimensions();
        double radius = dimensions.radius();
        var box = new SimpleCollisionBox(
                feet.x() - radius,
                feet.y(),
                feet.z() - radius,
                feet.x() + radius,
                feet.y() + dimensions.height(),
                feet.z() + radius);
        var world = sample(
                        player,
                        ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings.catalog(),
                        box,
                        box.copy()
                                .expand(Math.max(
                                        2.0D, Math.min(16.0D, state.velocity().length() + 2.0D))),
                        snapshot.movementContext().equipmentState().leatherBoots(),
                        state.fallDistance())
                .world();
        return snapshot.withBlockCollisionWorld(world.withCoordinateFrame(state.coordinateFrame()));
    }

    private BedrockSampledBlockWorld sample(
            CultPlayer player,
            BedrockCollisionOverrideCatalog geometry,
            SimpleCollisionBox actorBox,
            SimpleCollisionBox query,
            boolean wearingLeatherBoots,
            float fallDistance) {
        BedrockCollisionShapeQuery shapeQuery = BedrockCollisionShapeQuery.actor(
                toWorldCollisionBox(actorBox), false, wearingLeatherBoots, fallDistance);
        return new BedrockSampledBlockWorld(solidBlocks.sample(player, query, geometry, shapeQuery));
    }

    private SimpleCollisionBox trustedActorCollisionBox(SimulationContext context, BedrockPlayerContext playerContext) {
        java.util.Optional<Vec3d> trustedFeetPosition = BedrockProfileState.trustedFeetPosition(context);
        if (trustedFeetPosition.isEmpty()) {
            throw new IllegalStateException("missing trusted Bedrock actor position for block sampling");
        }

        Vec3d feet = trustedFeetPosition.get();
        PlayerDimensionsState dimensions = playerContext.dimensions();
        double radius = dimensions.radius();
        double height = dimensions.height();
        return new SimpleCollisionBox(
                feet.x() - radius,
                feet.y(),
                feet.z() - radius,
                feet.x() + radius,
                feet.y() + height,
                feet.z() + radius);
    }

    private static WorldCollisionBox toWorldCollisionBox(SimpleCollisionBox box) {
        return new WorldCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static float collisionFallDistance(
            BedrockMovementState previousState, BedrockPlayerContext playerContext, CultPlayer player) {
        if (previousState == null
                || playerContext.effects().slowFalling()
                || playerContext.effects().levitationLevel() > 0
                || player.isFlying) {
            return 0.0F;
        }
        return previousState.fallDistance();
    }

    record BedrockSampledBlockWorld(BlockCollisionWorld world) {}
}
