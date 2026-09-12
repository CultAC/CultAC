package ac.cult.cultac.utils.collisions;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** The single-AABB collision query in pre-1.9 BlockPistonMoving, not its render offset. */
public final class LegacyPistonCollision {
    private LegacyPistonCollision() {
    }

    public static CollisionBox movement(CultPlayer player, BlockState movedState, BlockPos pos,
                                        Direction facing, boolean extending, float previousProgress) {
        return bounds(player, movedState, pos, facing, extending ? 1.0F - previousProgress : previousProgress);
    }

    public static CollisionBox pushing(CultPlayer player, BlockState movedState, BlockPos pos,
                                       Direction facing, boolean extending, float nextProgress) {
        // TileEntityPiston#launchWithSlimeBlock uses the new progress, and a
        // different retraction transform from the movement collision query.
        return bounds(player, movedState, pos, facing, extending ? 1.0F - nextProgress : nextProgress - 1.0F);
    }

    private static CollisionBox bounds(CultPlayer player, BlockState movedState, BlockPos pos,
                                       Direction facing, float amount) {
        if (movedState.isAir() || movedState.is(Blocks.MOVING_PISTON)) {
            return NoCollisionBox.INSTANCE;
        }

        SimpleCollisionBox bounds;
        if (movedState.is(Blocks.PISTON_HEAD)) {
            // BlockPistonExtension#addCollisionBoxesToList restores the block bounds
            // to a cube. The moving block calls getCollisionBoundingBox directly,
            // not the head's two-piece addCollisionBoxesToList implementation.
            bounds = new SimpleCollisionBox(pos);
        } else {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            ClientBlockShapes.movement(player, movedState, pos.getX(), pos.getY(), pos.getZ()).downCast(boxes);
            if (boxes.isEmpty()) return NoCollisionBox.INSTANCE;
            bounds = boxes.getFirst().copy();
            for (int i = 1; i < boxes.size(); i++) bounds.union(boxes.get(i));
        }

        // BlockPistonMoving#getBoundingBox changes only the facing face.
        double x = (float) facing.getStepX() * amount;
        double y = (float) facing.getStepY() * amount;
        double z = (float) facing.getStepZ() * amount;
        if (facing.getStepX() < 0) bounds.minX -= x; else bounds.maxX -= x;
        if (facing.getStepY() < 0) bounds.minY -= y; else bounds.maxY -= y;
        if (facing.getStepZ() < 0) bounds.minZ -= z; else bounds.maxZ -= z;

        // 1.8 constructs a new AxisAlignedBB, which sorts its endpoints. 1.7
        // mutates the original bounds, retaining inverted endpoints. In both
        // cases keep zero-thickness planes: they can clip a crossing movement.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8)) {
            return new SimpleCollisionBox(Math.min(bounds.minX, bounds.maxX), Math.min(bounds.minY, bounds.maxY),
                    Math.min(bounds.minZ, bounds.maxZ), Math.max(bounds.minX, bounds.maxX),
                    Math.max(bounds.minY, bounds.maxY), Math.max(bounds.minZ, bounds.maxZ), false);
        }
        return new SimpleCollisionBox(bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ, false);
    }
}
