package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import java.util.ArrayList;
import java.util.List;

/**
 * A boat breaks every lily pad within half a block of its box where its move ends. Each one halves its velocity,
 * and the client alone removes it from its world.
 */
public final class BedrockBoatLilyPads {
    private static final String LILY_PAD = "minecraft:lily_pad";
    private static final float REACH = 0.5F;
    private static final float CELL_EDGE = 0.001F;

    private BedrockBoatLilyPads() {}

    /** The lily pads a boat whose move ended at {@code feet} breaks. Every block cell counts, whatever its shape. */
    public static List<BlockPosition> reached(BlockCollisionWorld world, PlayerDimensionsState dimensions, Vec3d feet) {
        var frame = world.coordinateFrame();
        float radius = (float) dimensions.radius();
        float x = frame.localX(feet.x()), y = (float) feet.y(), z = frame.localZ(feet.z());
        int minY = firstCell(y), maxY = lastCell(y + (float) dimensions.height());
        int minZ = firstCell(z - radius), maxZ = lastCell(z + radius);
        int minX = firstCell(x - radius), maxX = lastCell(x + radius);
        List<BlockPosition> pads = new ArrayList<>(0);
        for (int cellY = minY; cellY <= maxY; cellY++) {
            for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                for (int cellX = minX; cellX <= maxX; cellX++) {
                    var position = new BlockPosition(cellX + frame.originX(), cellY, cellZ + frame.originZ());
                    var block = world.blocksByPosition().get(position);
                    if (block != null && block.bedrockIdentifier().equals(LILY_PAD)) pads.add(position);
                }
            }
        }
        return pads;
    }

    static Vec3d slow(Vec3d velocity, int pads) {
        float x = (float) velocity.x(), y = (float) velocity.y(), z = (float) velocity.z();
        for (int pad = 0; pad < pads; pad++) {
            x *= 0.5F;
            y *= 0.5F;
            z *= 0.5F;
        }
        return pads == 0 ? velocity : new Vec3d(x, y, z);
    }

    private static int firstCell(float min) {
        return (int) Math.floor((min - REACH) + CELL_EDGE);
    }

    private static int lastCell(float max) {
        return (int) Math.floor((max + REACH) - CELL_EDGE);
    }
}
