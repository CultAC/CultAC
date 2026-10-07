/*
 * Integer coordinates and packed-long layout adapted from PacketEvents Vector3i
 * at 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1; GPL-3.0-or-later.
 * Copyright (C) 2022 retrooper and contributors; https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.value;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** Owned block coordinates; only the short-lived cursor subtype is mutable. */
public class BlockPos implements Comparable<BlockPos> {
    public static final BlockPos ZERO = new BlockPos(0, 0, 0);
    private final int x, y, z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int x() {
        return getX();
    }

    public int y() {
        return getY();
    }

    public int z() {
        return getZ();
    }

    public BlockPos immutable() {
        return this;
    }

    public long asLong() {
        return asLong(getX(), getY(), getZ());
    }

    public static long asLong(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 4095L;
    }

    public static BlockPos of(long packed) {
        return new BlockPos((int) (packed >> 38), (int) (packed << 52 >> 52), (int) (packed << 26 >> 38));
    }

    public static BlockPos containing(double x, double y, double z) {
        return new BlockPos(floor(x), floor(y), floor(z));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
    /** PacketEvents' signed 22/20/22-bit section axes and local X/Z/Y nibbles. */
    public static BlockPos fromSection(long section, int local) {
        int x = (int) (section >> 42), y = (int) (section << 44 >> 44), z = (int) (section << 22 >> 42);
        return new BlockPos((x << 4) + (local >>> 8 & 15), (y << 4) + (local & 15), (z << 4) + (local >>> 4 & 15));
    }

    public BlockPos offset(int x, int y, int z) {
        return x == 0 && y == 0 && z == 0 ? immutable() : new BlockPos(getX() + x, getY() + y, getZ() + z);
    }

    public BlockPos offset(BlockPos other) {
        return offset(other.getX(), other.getY(), other.getZ());
    }

    public BlockPos subtract(BlockPos other) {
        return offset(-other.getX(), -other.getY(), -other.getZ());
    }

    public BlockPos relative(Direction face) {
        return relative(face, 1);
    }

    public BlockPos relative(Direction face, int steps) {
        return offset(face.getModX() * steps, face.getModY() * steps, face.getModZ() * steps);
    }

    public BlockPos relative(Direction.Axis axis, int steps) {
        return offset(
                axis == Direction.Axis.X ? steps : 0,
                axis == Direction.Axis.Y ? steps : 0,
                axis == Direction.Axis.Z ? steps : 0);
    }

    public BlockPos atY(int y) {
        return new BlockPos(getX(), y, getZ());
    }

    public BlockPos above() {
        return above(1);
    }

    public BlockPos above(int steps) {
        return offset(0, steps, 0);
    }

    public BlockPos below() {
        return below(1);
    }

    public BlockPos below(int steps) {
        return offset(0, -steps, 0);
    }

    public BlockPos north() {
        return offset(0, 0, -1);
    }

    public BlockPos south() {
        return offset(0, 0, 1);
    }

    public BlockPos east() {
        return offset(1, 0, 0);
    }

    public BlockPos west() {
        return offset(-1, 0, 0);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        return other instanceof BlockPos pos && getX() == pos.getX() && getY() == pos.getY() && getZ() == pos.getZ();
    }

    @Override
    public int hashCode() {
        return (getY() + getZ() * 31) * 31 + getX();
    }

    @Override
    public int compareTo(BlockPos pos) {
        if (getY() != pos.getY()) return getY() - pos.getY();
        return getZ() != pos.getZ() ? getZ() - pos.getZ() : getX() - pos.getX();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{x=" + getX() + ", y=" + getY() + ", z=" + getZ() + "}";
    }
    /** Pinned BlockPos.betweenClosed visits X, then Y, then Z, reusing a cursor. */
    public static Iterable<BlockPos> betweenClosed(BlockPos first, BlockPos last) {
        int minX = Math.min(first.getX(), last.getX()),
                minY = Math.min(first.getY(), last.getY()),
                minZ = Math.min(first.getZ(), last.getZ());
        int width = Math.max(first.getX(), last.getX()) - minX + 1;
        int height = Math.max(first.getY(), last.getY()) - minY + 1;
        int depth = Math.max(first.getZ(), last.getZ()) - minZ + 1;
        int end = width * height * depth;
        return () -> new Iterator<>() {
            private final MutableBlockPos cursor = new MutableBlockPos();
            private int index;
            private boolean ready, finished;

            @Override
            public boolean hasNext() {
                if (!ready && !finished) {
                    if (index == end) finished = true;
                    else {
                        int slice = index / width;
                        cursor.set(minX + index % width, minY + slice % height, minZ + slice / height);
                        index++;
                        ready = true;
                    }
                }
                return ready;
            }

            @Override
            public BlockPos next() {
                if (!hasNext()) throw new NoSuchElementException();
                ready = false;
                return cursor;
            }
        };
    }

    public static final class MutableBlockPos extends BlockPos {
        private int mutableX, mutableY, mutableZ;

        public MutableBlockPos() {
            this(0, 0, 0);
        }

        public MutableBlockPos(int x, int y, int z) {
            super(x, y, z);
            set(x, y, z);
        }

        @Override
        public int getX() {
            return mutableX;
        }

        @Override
        public int getY() {
            return mutableY;
        }

        @Override
        public int getZ() {
            return mutableZ;
        }

        @Override
        public BlockPos immutable() {
            return new BlockPos(getX(), getY(), getZ());
        }

        public MutableBlockPos set(int x, int y, int z) {
            mutableX = x;
            mutableY = y;
            mutableZ = z;
            return this;
        }

        public MutableBlockPos set(double x, double y, double z) {
            return set(floor(x), floor(y), floor(z));
        }

        public MutableBlockPos set(BlockPos pos) {
            return set(pos.getX(), pos.getY(), pos.getZ());
        }

        public MutableBlockPos setWithOffset(BlockPos pos, int x, int y, int z) {
            return set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
        }
    }
}
