package ac.cult.cultac.bedrock.prediction.geometry;

import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;

/** Float AABB operations. Nominal dimensions do not describe a translated box's exact bounds. */
public final class BedrockActorBox {
    private BedrockActorBox() { }

    public static WorldCollisionBox create(Vec3d feet, PlayerDimensionsState dimensions, BedrockCoordinateFrame frame) {
        float radius = (float) dimensions.width() * 0.5F;
        return new WorldCollisionBox(frame.roundX(feet.x() - radius), (float) feet.y(),
                frame.roundZ(feet.z() - radius), frame.roundX(feet.x() + radius),
                (float) ((float) feet.y() + (float) dimensions.height()), frame.roundZ(feet.z() + radius));
    }

    /** Size updates rebuild X/Z around the position while retaining the box's minimum Y. */
    public static WorldCollisionBox resize(WorldCollisionBox box, Vec3d position,
            PlayerDimensionsState dimensions, BedrockCoordinateFrame frame) {
        return create(new Vec3d(position.x(), box.minY(), position.z()), dimensions, frame);
    }

    public static WorldCollisionBox move(WorldCollisionBox box, Vec3d movement, BedrockCoordinateFrame frame) {
        float dx = (float) movement.x(), dy = (float) movement.y(), dz = (float) movement.z();
        return new WorldCollisionBox(frame.roundX(box.minX() + dx), (float) ((float) box.minY() + dy),
                frame.roundZ(box.minZ() + dz), frame.roundX(box.maxX() + dx),
                (float) ((float) box.maxY() + dy), frame.roundZ(box.maxZ() + dz));
    }

    public static Vec3d feet(WorldCollisionBox box, BedrockCoordinateFrame frame) {
        return new Vec3d(frame.originX() + (double) ((frame.localX(box.minX()) + frame.localX(box.maxX())) * 0.5F),
                box.minY(), frame.originZ() + (double) ((frame.localZ(box.minZ()) + frame.localZ(box.maxZ())) * 0.5F));
    }
}
