package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.geometry.BedrockActorBox;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;

/**
 * The climb contact a tick starts from. The client keeps the in and over scaffolding, descendable and ascendable
 * contacts as actor flags, which it recomputes after each move and which a flags update for the player overwrites until
 * then.
 */
public record BedrockClimbableContact(
        boolean ladder, boolean scaffolding, boolean overScaffolding, boolean descendAllowed, boolean ascendableBlock) {
    public static final BedrockClimbableContact NONE = new BedrockClimbableContact(false, false, false, false, false);

    public static BedrockClimbableContact fromBlockWorld(
            BlockCollisionWorld blockCollisionWorld,
            Vec3d physicalFeetPosition,
            double playerWidth,
            double playerHeight) {
        return fromBlockWorld(blockCollisionWorld, physicalFeetPosition, playerWidth, playerHeight, false);
    }

    public static BedrockClimbableContact fromBlockWorld(
            BlockCollisionWorld blockCollisionWorld,
            Vec3d physicalFeetPosition,
            double playerWidth,
            double playerHeight,
            boolean canStandOnSnow) {
        if (blockCollisionWorld == null || blockCollisionWorld.isEmpty()) {
            return NONE;
        }
        boolean scaffoldingContact = false;
        boolean overScaffolding = false;
        boolean descendAllowed = false;
        boolean climbableContact = false;
        boolean ascendableBlock = false;
        // The client scans the block columns its box reaches, edges included: the row its feet
        // are in holds what it can ascend, the row one below what it can descend through.
        // Scaffolding in the row below counts only where a block holds it up.
        WorldCollisionBox box = BedrockActorBox.create(
                physicalFeetPosition,
                new PlayerDimensionsState(playerWidth, playerHeight),
                blockCollisionWorld.coordinateFrame());
        int feetRow = (int) Math.floor(box.minY());
        int rowBelow = (int) Math.floor((float) box.minY() - 1.0F);
        for (PlacedBlockCollision block : blockCollisionWorld.blocks()) {
            boolean scaffolding = isScaffoldingBlock(block);
            if (scaffolding || canStandOnSnow && isPowderSnowBlock(block)) {
                if (!inBoxColumns(block, box)) {
                    continue;
                }
                if (block.position().y() == feetRow) {
                    scaffoldingContact |= scaffolding;
                    ascendableBlock |= !scaffolding;
                }
                if (block.position().y() == rowBelow) {
                    overScaffolding |= scaffolding;
                    descendAllowed |= !scaffolding || heldUpFromBelow(blockCollisionWorld, block.position());
                }
                continue;
            }
            if (isClimbableBlock(block)) {

                if (blockAtActorFeet(block, physicalFeetPosition, Math.floor(physicalFeetPosition.y()))) {
                    climbableContact = true;
                }
            }
        }
        if (scaffoldingContact || overScaffolding || descendAllowed || climbableContact || ascendableBlock) {
            return new BedrockClimbableContact(
                    climbableContact, scaffoldingContact, overScaffolding, descendAllowed, ascendableBlock);
        }
        return NONE;
    }

    public boolean climbing() {
        return ladder || scaffolding;
    }

    public BedrockClimbableContact withActorFlags(BedrockClimberFlags flags) {
        return new BedrockClimbableContact(
                ladder,
                flags.inScaffolding(),
                flags.overScaffolding(),
                flags.overDescendableBlock(),
                flags.inAscendableBlock());
    }

    public boolean scaffoldingDescendAllowed() {
        return descendAllowed;
    }

    /**
     * Whether the scaffolding at {@code scaffolding} rests on a block. Sneak falls through scaffolding only where the
     * block under it is neither air nor water: a layer hanging over either holds a sneaking player up.
     */
    private static boolean heldUpFromBelow(BlockCollisionWorld world, BlockPosition scaffolding) {
        for (PlacedBlockCollision block : world.blocks()) {
            BlockPosition position = block.position();
            if (position.y() == scaffolding.y() - 1
                    && position.x() == scaffolding.x()
                    && position.z() == scaffolding.z()
                    && !block.isEntityCollision()
                    && !isWaterBlock(block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inBoxColumns(PlacedBlockCollision block, WorldCollisionBox box) {
        BlockPosition position = block.position();
        return position.x() >= Math.floor(box.minX())
                && position.x() <= Math.floor(box.maxX())
                && position.z() >= Math.floor(box.minZ())
                && position.z() <= Math.floor(box.maxZ());
    }

    private static boolean blockAtActorFeet(PlacedBlockCollision block, Vec3d physicalFeetPosition, double blockY) {
        BlockPosition position = block.position();
        return position.x() == (int) Math.floor(physicalFeetPosition.x())
                && position.y() == (int) blockY
                && position.z() == (int) Math.floor(physicalFeetPosition.z());
    }

    private static boolean isScaffoldingBlock(PlacedBlockCollision block) {
        return block.hasContactBehavior(BlockContactBehavior.SCAFFOLDING);
    }

    private static boolean isClimbableBlock(PlacedBlockCollision block) {
        return block.hasContactBehavior(BlockContactBehavior.CLIMBABLE);
    }

    private static boolean isWaterBlock(PlacedBlockCollision block) {
        return "minecraft:water".equals(block.bedrockIdentifier())
                || "minecraft:flowing_water".equals(block.bedrockIdentifier());
    }

    private static boolean isPowderSnowBlock(PlacedBlockCollision block) {
        return block.hasContactBehavior(BlockContactBehavior.POWDER_SNOW);
    }
}
