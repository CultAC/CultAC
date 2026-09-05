package ac.cult.cultac.bedrock.prediction.geometry;

import java.util.ArrayList;
import java.util.List;

// bamboo changes based on positions in a way that's different to java edition
final class BedrockBambooCollisionShape {
    private static final long SEED_XOR = 0x6a09e667f3bcc909L;
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;
    private static final long MIX_MULTIPLIER_1 = 0xbf58476d1ce4e5b9L;
    private static final long MIX_MULTIPLIER_2 = 0x94d049bb133111ebL;
    private static final float MIN_OFFSET = -0.25F;
    private static final float MAX_OFFSET = 0.25F;
    private static final int OFFSET_POSITIONS = 16;
    private static final PositionOffset ORIGIN_OFFSET = positionOffset(0, 0);

    private BedrockBambooCollisionShape() {
    }

    static List<WorldCollisionBox> at(
            BlockPosition position,
            BedrockCollisionOverrideShape originShape
    ) {
        PositionOffset offset = positionOffset(position.x(), position.z());
        double deltaX = offset.x() - ORIGIN_OFFSET.x();
        double deltaZ = offset.z() - ORIGIN_OFFSET.z();

        List<WorldCollisionBox> boxes = new ArrayList<>(originShape.boxes().size());
        for (BlockAabb box : originShape.boxes()) {
            boxes.add(new WorldCollisionBox(
                    position.x() + box.minX() + deltaX,
                    position.y() + box.minY(),
                    position.z() + box.minZ() + deltaZ,
                    position.x() + box.maxX() + deltaX,
                    position.y() + box.maxY(),
                    position.z() + box.maxZ() + deltaZ));
        }
        return List.copyOf(boxes);
    }

    static double offsetX(int x, int z) {
        return positionOffset(x, z).x();
    }

    static double offsetZ(int x, int z) {
        return positionOffset(x, z).z();
    }

    private static PositionOffset positionOffset(int x, int z) {
        long mixedPosition = (long) (x * 0x002fc20f) ^ (long) z * 0x06ebfff5L;
        long hashedPosition = (mixedPosition * 0x0285b825L + 0x0bL) * mixedPosition;
        long seed = (long) (int) (hashedPosition >>> 16) ^ SEED_XOR;
        long state0 = mix(seed);
        long state1 = mix(seed + GOLDEN_GAMMA);
        if ((state0 | state1) == 0L) {
            state0 = GOLDEN_GAMMA;
            state1 = SEED_XOR;
        }

        float offsetX = quantizedOffset(output(state0, state1));
        long xor = state1 ^ state0;
        state0 = Long.rotateLeft(state0, 49) ^ xor ^ (xor << 21);
        state1 = Long.rotateLeft(xor, 28);
        // vanilla advances once for each Vec3 component. Y is fixed at zero for
        // bamboo, but its state transition still occurs before Z is sampled.
        xor = state1 ^ state0;
        state0 = Long.rotateLeft(state0, 49) ^ xor ^ (xor << 21);
        state1 = Long.rotateLeft(xor, 28);
        float offsetZ = quantizedOffset(output(state0, state1));
        return new PositionOffset(offsetX, offsetZ);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * MIX_MULTIPLIER_1;
        value = (value ^ (value >>> 27)) * MIX_MULTIPLIER_2;
        return value ^ (value >>> 31);
    }

    private static int output(long state0, long state1) {
        return (int) ((Long.rotateLeft(state0 + state1, 17) + state0) >>> 40);
    }

    private static float quantizedOffset(int random24) {
        float unit = random24 * 0x1.0p-24F;
        float step = (MAX_OFFSET - MIN_OFFSET) / (OFFSET_POSITIONS - 1);
        return MIN_OFFSET + (float) Math.floor(unit * OFFSET_POSITIONS) * step;
    }

    private record PositionOffset(float x, float z) {
    }
}
