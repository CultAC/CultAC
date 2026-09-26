package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;

public final class BedrockCollisionFetchBox {
    private static final float MAX_MOVE_LENGTH = 16.0F;
    private static final float SNEAK_SHRINK = 0.025F;
    private static final float SNEAK_STEP_SCALE = 1.01F;
    private static final float VERTICAL_PROBE_DOWN = 0.2F;
    private static final float VERTICAL_PROBE_UP = 0.08F;

    private BedrockCollisionFetchBox() {
    }

    public static WorldCollisionBox of(WorldCollisionBox actor, Vec3d requestedMove, double maxUpStep) {
        float dx = (float) requestedMove.x(), dy = (float) requestedMove.y(), dz = (float) requestedMove.z();
        float lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared > MAX_MOVE_LENGTH * MAX_MOVE_LENGTH) {
            float length = (float) Math.sqrt(lengthSquared);
            dx = dx / length * MAX_MOVE_LENGTH;
            dy = dy / length * MAX_MOVE_LENGTH;
            dz = dz / length * MAX_MOVE_LENGTH;
        }
        float step = (float) maxUpStep;
        Box box = Box.of(actor);
        Box fetch = box.expand(dx, dy, dz)
                .union(sneakVolume(box, dx, dz, step))
                .union(autoStepVolume(box, dx, dy, dz, step))
                .union(verticalVolume(box, dy));
        return fetch.toWorld();
    }

    private static Box sneakVolume(Box box, float dx, float dz, float step) {
        Box shrunk = box.shrinkXz(SNEAK_SHRINK);
        float down = step * SNEAK_STEP_SCALE;
        Box lowered = new Box(shrunk.minX, shrunk.minY - down, shrunk.minZ,
                shrunk.maxX, shrunk.maxY, shrunk.maxZ);
        return lowered.offset(dx, 0.0F).union(lowered.offset(0.0F, dz)).union(lowered.offset(dx, dz));
    }

    private static Box autoStepVolume(Box box, float dx, float dy, float dz, float step) {
        return box.expand(dx, dy, dz).union(box.expand(0.0F, step, 0.0F)).union(box.expand(dx, step + dy, dz));
    }

    private static Box verticalVolume(Box box, float dy) {
        return new Box(box.minX, box.minY + (-VERTICAL_PROBE_DOWN - Math.abs(dy)), box.minZ,
                box.maxX, box.maxY + VERTICAL_PROBE_UP, box.maxZ);
    }

    private record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        static Box of(WorldCollisionBox box) {
            return new Box((float) box.minX(), (float) box.minY(), (float) box.minZ(),
                    (float) box.maxX(), (float) box.maxY(), (float) box.maxZ());
        }

        Box expand(float x, float y, float z) {
            return new Box(x < 0.0F ? minX + x : minX, y < 0.0F ? minY + y : minY, z < 0.0F ? minZ + z : minZ,
                    x > 0.0F ? maxX + x : maxX, y > 0.0F ? maxY + y : maxY, z > 0.0F ? maxZ + z : maxZ);
        }

        Box shrinkXz(float amount) {
            float lowX = minX + amount, highX = maxX - amount;
            float lowZ = minZ + amount, highZ = maxZ - amount;
            if (lowX > highX) lowX = highX = (lowX + highX) / 2.0F;
            if (lowZ > highZ) lowZ = highZ = (lowZ + highZ) / 2.0F;
            return new Box(lowX, minY, lowZ, highX, maxY, highZ);
        }

        Box offset(float x, float z) {
            return new Box(minX + x, minY, minZ + z, maxX + x, maxY, maxZ + z);
        }

        Box union(Box other) {
            return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }

        WorldCollisionBox toWorld() {
            return new WorldCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
