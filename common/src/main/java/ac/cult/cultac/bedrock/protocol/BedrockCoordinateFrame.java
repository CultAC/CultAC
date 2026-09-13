package ac.cult.cultac.bedrock.protocol;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import net.minecraft.world.phys.Vec3;

/** An immutable, session-local GFP origin. All stored simulation positions remain world positions. */
public record BedrockCoordinateFrame(int originX, int originZ, long revision) {
    public static final BedrockCoordinateFrame IDENTITY = new BedrockCoordinateFrame(0, 0, 0);

    public BedrockCoordinateFrame {
        if (revision < 0 || revision == 0 && (originX != 0 || originZ != 0)) {
            throw new IllegalArgumentException("A shifted origin requires a positive transport revision");
        }
    }

    public float localX(double worldX) { return (float) (worldX - originX); }
    public float localZ(double worldZ) { return (float) (worldZ - originZ); }
    public double roundX(double worldX) { return originX + (double) localX(worldX); }
    public double roundZ(double worldZ) { return originZ + (double) localZ(worldZ); }

    public Vec3 toWorld(Vec3 local) {
        return local == null ? null : new Vec3(local.x + originX, local.y, local.z + originZ);
    }

    public Vec3 toLocal(Vec3 world) {
        return world == null ? null : new Vec3(world.x - originX, world.y, world.z - originZ);
    }

    public Vec3d toWorld(Vec3d local) {
        return new Vec3d(local.x() + originX, local.y(), local.z() + originZ);
    }

    public Vec3d toLocal(Vec3d world) {
        return new Vec3d(world.x() - originX, world.y(), world.z() - originZ);
    }

    public WorldCollisionBox roundBox(WorldCollisionBox box) {
        return new WorldCollisionBox(roundX(box.minX()), (float) box.minY(), roundZ(box.minZ()),
                roundX(box.maxX()), (float) box.maxY(), roundZ(box.maxZ()));
    }
}
