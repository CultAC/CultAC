package ac.cult.cultac.bedrock.prediction.geometry;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a block that a hash of its position shifts on X and Z, differently from Java edition: bamboo by up to
 * 0.25, and pointed dripstone and sulfur spikes by up to 0.125. The position hashed is the one the session's
 * coordinate frame gives the block, and the catalog holds each shape as shifted at position (0, 0).
 */
final class BedrockOffsetBlockShape {
    private static final long SEED_XOR = 0x6a09e667f3bcc909L;
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;
    private static final long MIX_MULTIPLIER_1 = 0xbf58476d1ce4e5b9L;
    private static final long MIX_MULTIPLIER_2 = 0x94d049bb133111ebL;
    private static final int OFFSET_POSITIONS = 16;

    private BedrockOffsetBlockShape() {}

    /** {@code originShape} at {@code position}, shifted by up to {@code maxOffset} for its place in {@code frame}. */
    static List<WorldCollisionBox> at(
            BlockPosition position,
            BedrockCollisionOverrideShape originShape,
            float maxOffset,
            BedrockCoordinateFrame frame) {
        PositionOffset offset =
                positionOffset(position.x() - frame.originX(), position.z() - frame.originZ(), maxOffset);
        PositionOffset originOffset = positionOffset(0, 0, maxOffset);

        List<WorldCollisionBox> boxes = new ArrayList<>(originShape.boxes().size());
        for (BlockAabb box : originShape.boxes()) {
            boxes.add(new WorldCollisionBox(
                    position.x() + unshifted(box.minX(), originOffset.x()) + offset.x(),
                    position.y() + box.minY(),
                    position.z() + unshifted(box.minZ(), originOffset.z()) + offset.z(),
                    position.x() + unshifted(box.maxX(), originOffset.x()) + offset.x(),
                    position.y() + box.maxY(),
                    position.z() + unshifted(box.maxZ(), originOffset.z()) + offset.z()));
        }
        return List.copyOf(boxes);
    }

    /**
     * The bound a catalog shape had before the shift of position (0, 0). The catalog rounds the shifted bounds to six
     * decimals, which can leave a face a float step past the one the shape has; every unshifted bound is a sixteenth.
     */
    private static double unshifted(double catalogBound, float originOffset) {
        return Math.rint((catalogBound - originOffset) * 16.0D) / 16.0D;
    }

    private static PositionOffset positionOffset(int x, int z, float maxOffset) {
        long mixedPosition = (long) (x * 0x002fc20f) ^ (long) z * 0x06ebfff5L;
        long hashedPosition = (mixedPosition * 0x0285b825L + 0x0bL) * mixedPosition;
        long seed = (long) (int) (hashedPosition >>> 16) ^ SEED_XOR;
        long state0 = mix(seed);
        long state1 = mix(seed + GOLDEN_GAMMA);
        if ((state0 | state1) == 0L) {
            state0 = GOLDEN_GAMMA;
            state1 = SEED_XOR;
        }

        float offsetX = quantizedOffset(output(state0, state1), maxOffset);
        long xor = state1 ^ state0;
        state0 = Long.rotateLeft(state0, 49) ^ xor ^ (xor << 21);
        state1 = Long.rotateLeft(xor, 28);
        // vanilla advances once for each Vec3 component. Y is fixed at zero for
        // these blocks, but its state transition still occurs before Z is sampled.
        xor = state1 ^ state0;
        state0 = Long.rotateLeft(state0, 49) ^ xor ^ (xor << 21);
        state1 = Long.rotateLeft(xor, 28);
        float offsetZ = quantizedOffset(output(state0, state1), maxOffset);
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

    private static float quantizedOffset(int random24, float maxOffset) {
        float unit = random24 * 0x1.0p-24F;
        float step = (maxOffset + maxOffset) / (OFFSET_POSITIONS - 1);
        return -maxOffset + (float) Math.floor(unit * OFFSET_POSITIONS) * step;
    }

    private record PositionOffset(float x, float z) {}
}
