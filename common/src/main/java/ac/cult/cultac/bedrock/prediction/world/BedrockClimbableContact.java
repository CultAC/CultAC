package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;

public record BedrockClimbableContact(
    boolean climbing,
    boolean scaffolding,
    boolean descendAllowed,
    boolean ascendableBlock
) {
    public static final BedrockClimbableContact NONE = new BedrockClimbableContact(
        false,
        false,
        false,
        false
    );

    public static BedrockClimbableContact fromBlockWorld(
        BlockCollisionWorld blockCollisionWorld,
        Vec3d physicalFeetPosition,
        double playerWidth,
        double playerHeight
    ) {
        return fromBlockWorld(
            blockCollisionWorld,
            physicalFeetPosition,
            playerWidth,
            playerHeight,
            false
        );
    }

    public static BedrockClimbableContact fromBlockWorld(
        BlockCollisionWorld blockCollisionWorld,
        Vec3d physicalFeetPosition,
        double playerWidth,
        double playerHeight,
        boolean canStandOnSnow
    ) {
        if (blockCollisionWorld == null || blockCollisionWorld.isEmpty()) {
            return NONE;
        }
        boolean scaffoldingContact = false;
        boolean descendAllowed = false;
        boolean climbableContact = false;
        boolean ascendableBlock = false;
        double actorFeetBlockY = Math.floor(physicalFeetPosition.y());
        for (PlacedBlockCollision block : blockCollisionWorld.blocks()) {
            if (isScaffoldingBlock(block)) {
                if (blockAtActorHeight(block, physicalFeetPosition, playerWidth, actorFeetBlockY)) {
                    scaffoldingContact = true;

                    descendAllowed = true;
                }
                if (scaffoldingAtActorHeight(block, physicalFeetPosition, playerWidth, Math.floor(physicalFeetPosition.y() - 1.0D))) {
                    descendAllowed = true;
                }
                continue;
            }
            if (canStandOnSnow && isPowderSnowBlock(block)) {

                if (blockAtActorHeight(block, physicalFeetPosition, playerWidth, actorFeetBlockY)) {
                    ascendableBlock = true;
                }
                if (blockAtActorHeight(block, physicalFeetPosition, playerWidth,
                    Math.floor(physicalFeetPosition.y() - 1.0D))
                    ) {
                    descendAllowed = true;
                }
                continue;
            }
            if (isClimbableBlock(block)) {

                if (blockAtActorFeet(block, physicalFeetPosition, actorFeetBlockY)) {
                    climbableContact = true;
                }
            }
        }
        if (scaffoldingContact || descendAllowed
            || climbableContact || ascendableBlock) {
            return new BedrockClimbableContact(
                scaffoldingContact || climbableContact,
                scaffoldingContact,
                descendAllowed,
                ascendableBlock
            );
        }
        return NONE;
    }

    public boolean scaffoldingDescendAllowed() {
        return descendAllowed;
    }

    private static boolean blockAtActorHeight(
        PlacedBlockCollision block,
        Vec3d physicalFeetPosition,
        double playerWidth,
        double blockY
    ) {
        BlockPosition position = block.position();
        double halfWidth = playerWidth * 0.5D;
        return position.y() == (int) blockY
            && physicalFeetPosition.x() + halfWidth > position.x()
            && physicalFeetPosition.x() - halfWidth < position.x() + 1.0D
            && physicalFeetPosition.z() + halfWidth > position.z()
            && physicalFeetPosition.z() - halfWidth < position.z() + 1.0D;
    }

    private static boolean scaffoldingAtActorHeight(
        PlacedBlockCollision scaffolding,
        Vec3d physicalFeetPosition,
        double playerWidth,
        double blockY
    ) {
        return blockAtActorHeight(scaffolding, physicalFeetPosition, playerWidth, blockY);
    }

    private static boolean blockAtActorFeet(
        PlacedBlockCollision block,
        Vec3d physicalFeetPosition,
        double blockY
    ) {
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

    private static boolean isPowderSnowBlock(PlacedBlockCollision block) {
        return block.hasContactBehavior(BlockContactBehavior.POWDER_SNOW);
    }
}
