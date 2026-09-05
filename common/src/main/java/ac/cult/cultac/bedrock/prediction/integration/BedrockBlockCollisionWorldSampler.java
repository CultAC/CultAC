package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.integration.BedrockSolidBlockSampler.BedrockSolidBlockSample;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;

final class BedrockBlockCollisionWorldSampler {
    private final BedrockFluidBlockSampler fluidBlocks = new BedrockFluidBlockSampler();
    private final BedrockSolidBlockSampler solidBlocks = new BedrockSolidBlockSampler(fluidBlocks);

    BedrockSampledBlockWorld sample(
            CultPlayer player,
            SimulationContext context,
            BedrockCollisionOverrideCatalog geometry,
            BedrockAuthInputFrame frame,
            BedrockPlayerContext playerContext
    ) {
        if (player.compensatedWorld == null) {
            throw new IllegalStateException("missing compensated world for Bedrock block sampling");
        }

        BedrockActorCollisionQuery actorQuery = actorQuery(context, playerContext);
        SimpleCollisionBox actorBox = actorQuery.actorBox();
        SimpleCollisionBox query = actorQuery.queryBox();
        BedrockSolidBlockSample solidBlockSample = solidBlocks.sample(
                player,
                query,
                geometry);
        boolean wearingLeatherBoots = playerContext.wearingLeatherBoots();
        WorldCollisionBox worldActorBox = toWorldCollisionBox(actorBox);
        BedrockMovementState previousState = BedrockProfileState.previousState(context);
        BedrockCollisionShapeQuery shapeQuery = BedrockCollisionShapeQuery.actor(
                worldActorBox,
                false,
                wearingLeatherBoots,
                collisionFallDistance(previousState, playerContext, player)
        );
        BlockCollisionWorld dynamicGeneratedWorld = solidBlockSample.dynamicGeneratedBlockStates().isEmpty()
                ? BlockCollisionWorld.EMPTY
                : new BedrockCollisionWorldBuilder(geometry).build(
                        solidBlockSample.dynamicGeneratedBlockStates(),
                        shapeQuery);
        BlockCollisionWorld staticGeneratedWorld = solidBlockSample.staticGeneratedWorld();
        if (dynamicGeneratedWorld.isEmpty()) {
            return new BedrockSampledBlockWorld(solidBlockSample.actorIndependentWorld());
        }
        if (solidBlockSample.javaCollisionBlocks().isEmpty()
                && solidBlockSample.fluidCollisionBlocks().isEmpty()
                && staticGeneratedWorld.isEmpty()) {
            return new BedrockSampledBlockWorld(dynamicGeneratedWorld);
        }
        List<PlacedBlockCollision> blocks = new ArrayList<>(
                staticGeneratedWorld.blocks().size()
                        + dynamicGeneratedWorld.blocks().size()
                        + solidBlockSample.javaCollisionBlocks().size()
                        + solidBlockSample.fluidCollisionBlocks().size());
        blocks.addAll(staticGeneratedWorld.blocks());
        blocks.addAll(dynamicGeneratedWorld.blocks());
        blocks.addAll(solidBlockSample.javaCollisionBlocks());
        blocks.addAll(solidBlockSample.fluidCollisionBlocks());
        return new BedrockSampledBlockWorld(new BlockCollisionWorld(blocks));
    }

    private BedrockActorCollisionQuery actorQuery(
            SimulationContext context,
            BedrockPlayerContext playerContext
    ) {
        SimpleCollisionBox actorBox = trustedActorCollisionBox(context, playerContext);
        return new BedrockActorCollisionQuery(
                actorBox,
                actorBox.copy().expand(trustedCollisionQueryExpansion(context)));
    }

    private SimpleCollisionBox trustedActorCollisionBox(
            SimulationContext context,
            BedrockPlayerContext playerContext
    ) {
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
                feet.z() + radius
        );
    }

    private double trustedCollisionQueryExpansion(SimulationContext context) {
        BedrockMovementState previousState = BedrockProfileState.previousState(context);
        if (previousState == null) {
            return 2.0D;
        }
        double velocityLength = previousState.velocity().length();
        if (!Double.isFinite(velocityLength)) {
            return 2.0D;
        }
        return Math.max(2.0D, Math.min(16.0D, velocityLength + 2.0D));
    }

    private static WorldCollisionBox toWorldCollisionBox(SimpleCollisionBox box) {
        return new WorldCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static float collisionFallDistance(
            BedrockMovementState previousState,
            BedrockPlayerContext playerContext,
            CultPlayer player
    ) {
        if (previousState == null
                || playerContext.effects().slowFalling()
                || playerContext.effects().levitationLevel() > 0
                || player.isFlying) {
            return 0.0F;
        }
        return previousState.fallDistance();
    }

    record BedrockSampledBlockWorld(BlockCollisionWorld world) {
    }

    private record BedrockActorCollisionQuery(
            SimpleCollisionBox actorBox,
            SimpleCollisionBox queryBox
    ) {
    }
}
