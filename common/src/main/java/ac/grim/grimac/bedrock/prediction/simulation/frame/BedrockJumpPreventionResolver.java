package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.world.BedrockBlockMetadata;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.JumpPreventionState;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.Objects;
import java.util.Optional;

final class BedrockJumpPreventionResolver {
    private static final long PREVENT_JUMPING_BLOCK_PROPERTY = 0x80000000000L;
    private static final long CHECK_BELOW_BLOCK_PROPERTY = 0x100L;

    private BedrockJumpPreventionResolver() {
    }

    static JumpPreventionState resolve(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        boolean onGround
    ) {
        Objects.requireNonNull(context, "context");
        return fromBlockWorld(onGround, physicalFeetPosition, context.worldState().blockCollisionWorld());
    }

    private static JumpPreventionState fromBlockWorld(
        boolean onGround,
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld
    ) {
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(blockWorld, "blockWorld");
        if (!onGround) {
            return JumpPreventionState.NONE;
        }

        int x = floorToInt((float) physicalFeetPosition.x());
        int y = floorToInt((float) physicalFeetPosition.y());
        int z = floorToInt((float) physicalFeetPosition.z());
        BlockPosition currentPosition = new BlockPosition(x, y, z);
        Optional<PlacedBlockCollision> currentBlock = blockWorld.blockAt(currentPosition);
        if (currentBlock.filter(BedrockJumpPreventionResolver::preventsJumping).isPresent()) {
            return JumpPreventionState.blockedBy(currentPosition);
        }

        float feetY = (float) physicalFeetPosition.y();
        boolean checkBelow = feetY <= (float) y
            || currentBlock.filter(block -> BedrockBlockMetadata.hasBedrockBlockProperty(block, CHECK_BELOW_BLOCK_PROPERTY)).isPresent();
        if (!checkBelow) {
            return JumpPreventionState.NONE;
        }

        BlockPosition belowPosition = new BlockPosition(x, y - 1, z);
        return blockWorld.blockAt(belowPosition)
            .filter(BedrockJumpPreventionResolver::preventsJumping)
            .map(block -> JumpPreventionState.blockedBy(belowPosition))
            .orElse(JumpPreventionState.NONE);
    }

    private static boolean preventsJumping(PlacedBlockCollision block) {
        return BedrockBlockMetadata.hasBedrockBlockProperty(block, PREVENT_JUMPING_BLOCK_PROPERTY);
    }

    private static int floorToInt(float value) {
        return (int) Math.floor(value);
    }
}
