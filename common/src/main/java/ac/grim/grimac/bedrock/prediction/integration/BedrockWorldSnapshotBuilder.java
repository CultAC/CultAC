package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.model.AttributeState;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.model.EquipmentState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.MovementModifierState;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.world.BedrockClimbableContact;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.FluidState;
import ac.grim.grimac.bedrock.prediction.world.HoneySlideState;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import ac.grim.grimac.bedrock.prediction.world.PowderSnowContactState;
import ac.grim.grimac.bedrock.prediction.world.StandingSurfaceState;
import ac.grim.grimac.bedrock.prediction.world.WorldContactState;
import ac.grim.grimac.bedrock.protocol.BedrockAuthInputFrame;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;

final class BedrockWorldSnapshotBuilder {
    private final BedrockBlockCollisionWorldSampler blockWorld = new BedrockBlockCollisionWorldSampler();
    private final BedrockEntityContactFactory entityContacts = new BedrockEntityContactFactory();

    BedrockWorldSnapshot create(
            GrimPlayer player,
            SimulationContext context,
            BedrockCollisionOverrideCatalog geometry,
            BedrockAuthInputFrame frame,
            BedrockPlayerContext playerContext
    ) {
        BedrockBlockCollisionWorldSampler.BedrockSampledBlockWorld sampledBlockWorld = blockWorld.sample(
                player,
                context,
                geometry,
                frame,
                playerContext);
        BlockCollisionWorld collisionWorld = withHardCollidingEntities(
                sampledBlockWorld.world(),
                context.getHardCollidingEntityCollisionsForMovementTick());
        BedrockMovementContext movementContext = movementContext(
                collisionWorld,
                playerContext,
                player,
                context);
        return new BedrockWorldSnapshot(
                movementContext,
                collisionWorld,
                movementContext.playerDimensionsState(),
                BedrockClimbableContact.NONE,
                PowderSnowContactState.NONE,
                new StandingSurfaceState(java.util.Set.of()),
                BlockMovementSlowdownState.NONE,
                HoneySlideState.NONE);
    }

    private BedrockMovementContext movementContext(
            BlockCollisionWorld blockWorld,
            BedrockPlayerContext playerContext,
            GrimPlayer player,
            SimulationContext context
    ) {
        return playerContext.applyTo(
                new BedrockMovementContext(
                        BedrockEffectState.NONE,
                        AttributeState.DEFAULT,
                        new WorldContactState(
                                sourceLiquidMedium(context),
                                FluidState.NONE,
                                sourceWaterContact(context),
                                sourceLavaContact(context),
                                blockWorld),
                        EquipmentState.NONE,
                        entityContacts.create(player, context),
                        MovementModifierState.NONE,
                        PlayerDimensionsState.DEFAULT),
                player,
                context);
    }

    private static Medium sourceLiquidMedium(SimulationContext context) {
        if (context == null || context.getWorldData() == null) {
            return Medium.AIR;
        }
        if (context.getWorldData().getInWater().determinePessimistically()) {
            return Medium.WATER;
        }
        if (context.getWorldData().getInLava().determinePessimistically()) {
            return Medium.LAVA;
        }
        return Medium.AIR;
    }

    private static boolean sourceWaterContact(SimulationContext context) {
        return context != null && context.getWorldData() != null
                && context.getWorldData().getInWater().determinePessimistically();
    }

    private static boolean sourceLavaContact(SimulationContext context) {
        return context != null && context.getWorldData() != null
                && context.getWorldData().getInLava().determinePessimistically();
    }

    static BlockCollisionWorld withHardCollidingEntities(
            BlockCollisionWorld world,
            List<SimulationContext.HardCollidingEntityCollision> hardCollisions
    ) {
        if (hardCollisions == null || hardCollisions.isEmpty()) {
            return world;
        }
        ArrayList<PlacedBlockCollision> blocks = new ArrayList<>(world.blocks());
        for (SimulationContext.HardCollidingEntityCollision collision : hardCollisions) {
            PlacedBlockCollision block = hardCollidingEntityBlock(collision);
            if (block != null) {
                blocks.add(block);
            }
        }
        return new BlockCollisionWorld(blocks);
    }

    private static PlacedBlockCollision hardCollidingEntityBlock(
            SimulationContext.HardCollidingEntityCollision collision
    ) {
        if (collision == null || collision.box() == null) {
            return null;
        }
        SimpleCollisionBox sourceBox = collision.box();
        if (!Double.isFinite(sourceBox.minX) || !Double.isFinite(sourceBox.minY) || !Double.isFinite(sourceBox.minZ)
                || !Double.isFinite(sourceBox.maxX) || !Double.isFinite(sourceBox.maxY) || !Double.isFinite(sourceBox.maxZ)
                || sourceBox.minX >= sourceBox.maxX || sourceBox.minY >= sourceBox.maxY || sourceBox.minZ >= sourceBox.maxZ) {
            return null;
        }
        WorldCollisionBox collisionBox = new WorldCollisionBox(
                sourceBox.minX,
                sourceBox.minY,
                sourceBox.minZ,
                sourceBox.maxX,
                sourceBox.maxY,
                sourceBox.maxZ);
        String identifier = hardCollidingEntityIdentifier(collision);
        return PlacedBlockCollision.manual(
                new BlockPosition(
                        (int) Math.floor(collisionBox.minX()),
                        (int) Math.floor(collisionBox.minY()),
                        (int) Math.floor(collisionBox.minZ())),
                identifier,
                identifier,
                List.of(collisionBox),
                List.of(collisionBox),
                List.of(collisionBox));
    }

    private static String hardCollidingEntityIdentifier(SimulationContext.HardCollidingEntityCollision collision) {
        if (collision.boatLike()) {
            return "minecraft:boat_entity_collision";
        }
        if (collision.minecartLike()) {
            return "minecraft:minecart_entity_collision";
        }
        return "minecraft:hard_entity_collision";
    }
}
