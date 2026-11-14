package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.debug.DebugText;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Setter;

@Data
public class CollideAxisData implements DebugText.Recordable {
    CollideResult x;
    CollideResult yPos;
    CollideResult yNeg;
    CollideResult z;
    List<SimpleCollisionBox> unknown;
    // Each horizontal direction on its own: Entity#collide clips an axis only against shapes ahead of the move.
    @Setter(AccessLevel.NONE)
    CollideResult xPos;

    @Setter(AccessLevel.NONE)
    CollideResult xNeg;

    @Setter(AccessLevel.NONE)
    CollideResult zPos;

    @Setter(AccessLevel.NONE)
    CollideResult zNeg;

    /** Each direction of X and Z holds the combined result when that result can lie in it. */
    public CollideAxisData(
            CollideResult x,
            CollideResult yPos,
            CollideResult yNeg,
            CollideResult z,
            List<SimpleCollisionBox> unknown) {
        this.x = x;
        this.yPos = yPos;
        this.yNeg = yNeg;
        this.z = z;
        this.unknown = unknown;
        this.xPos = inDirection(x, 1);
        this.xNeg = inDirection(x, -1);
        this.zPos = inDirection(z, 1);
        this.zNeg = inDirection(z, -1);
    }

    private static CollideResult inDirection(CollideResult result, int direction) {
        if (result == null) return null;
        int side = result.side != 0 ? result.side : (int) Math.signum(result.result);
        if (side != 0 && side != direction) return null;
        return result instanceof ImmutableCollideResult
                ? result
                : new CollideResult(result.isLikelyCollide, result.result, direction);
    }

    @Override
    public void appendTo(DebugText.Builder sb) {
        sb.append("CollideAxisData(x=")
                .append(x)
                .append(", yPos=")
                .append(yPos)
                .append(", yNeg=")
                .append(yNeg)
                .append(", z=")
                .append(z)
                .append(", unknown=")
                .appendAll(unknown)
                .append(')');
    }

    @Override
    public String toString() {
        return DebugText.print(this);
    }

    public void setX(CollideResult newX) {
        x = x == null ? newX : x.combine(newX);
    }

    public void setYPos(CollideResult newYPos) {
        yPos = yPos == null ? newYPos : yPos.combine(newYPos);
    }

    public void setYNeg(CollideResult newYNeg) {
        yNeg = yNeg == null ? newYNeg : yNeg.combine(newYNeg);
    }

    public void setZ(CollideResult newZ) {
        z = z == null ? newZ : z.combine(newZ);
    }

    /** {@link #setX} with {@code new CollideResult(likelyCollide, result, side)}, allocating only for the first. */
    public void combineX(boolean likelyCollide, double result, int side) {
        if (x == null) x = new CollideResult(likelyCollide, result, side);
        else x.combine(likelyCollide, result, side);
    }

    public void combineYPos(boolean likelyCollide, double result) {
        if (yPos == null) yPos = new CollideResult(likelyCollide, result);
        else yPos.combine(likelyCollide, result, 0);
    }

    public void combineYNeg(boolean likelyCollide, double result) {
        if (yNeg == null) yNeg = new CollideResult(likelyCollide, result);
        else yNeg.combine(likelyCollide, result, 0);
    }

    public void combineZ(boolean likelyCollide, double result, int side) {
        if (z == null) z = new CollideResult(likelyCollide, result, side);
        else z.combine(likelyCollide, result, side);
    }

    /** Combines a probe of X towards {@code direction} (+1/-1) into that direction's result. */
    public void combineX(int direction, boolean likelyCollide, double result) {
        if (direction > 0) xPos = combined(xPos, likelyCollide, result, direction);
        else xNeg = combined(xNeg, likelyCollide, result, direction);
    }

    /** Combines a probe of Z towards {@code direction} (+1/-1) into that direction's result. */
    public void combineZ(int direction, boolean likelyCollide, double result) {
        if (direction > 0) zPos = combined(zPos, likelyCollide, result, direction);
        else zNeg = combined(zNeg, likelyCollide, result, direction);
    }

    private static CollideResult combined(CollideResult current, boolean likelyCollide, double result, int direction) {
        if (current == null) return new CollideResult(likelyCollide, result, direction);
        return current.combine(likelyCollide, result, direction);
    }

    /** The result of X's probes towards the sign of {@code movement}; null when it is zero or none probed there. */
    public CollideResult xTowards(double movement) {
        return movement > 0.0D ? xPos : movement < 0.0D ? xNeg : null;
    }

    /** The result of Z's probes towards the sign of {@code movement}; null when it is zero or none probed there. */
    public CollideResult zTowards(double movement) {
        return movement > 0.0D ? zPos : movement < 0.0D ? zNeg : null;
    }

    public boolean couldCollideHorizontally() {
        return x.isLikelyCollide() || z.isLikelyCollide();
    }

    /**
     * Collision data safe to share between players: every mutator of it and of its results throws, and its unknown
     * list is empty and unmodifiable.
     */
    public static CollideAxisData immutable(CollideResult x, CollideResult yPos, CollideResult yNeg, CollideResult z) {
        return new ImmutableCollideAxisData(
                ImmutableCollideResult.of(x),
                ImmutableCollideResult.of(yPos),
                ImmutableCollideResult.of(yNeg),
                ImmutableCollideResult.of(z));
    }

    private static final class ImmutableCollideAxisData extends CollideAxisData {
        ImmutableCollideAxisData(CollideResult x, CollideResult yPos, CollideResult yNeg, CollideResult z) {
            super(x, yPos, yNeg, z, List.of());
        }

        @Override
        public void setX(CollideResult newX) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setYPos(CollideResult newYPos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setYNeg(CollideResult newYNeg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setZ(CollideResult newZ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setUnknown(List<SimpleCollisionBox> unknown) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void combineX(boolean likelyCollide, double result, int side) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void combineYPos(boolean likelyCollide, double result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void combineYNeg(boolean likelyCollide, double result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void combineZ(boolean likelyCollide, double result, int side) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void combineX(int direction, boolean likelyCollide, double result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void combineZ(int direction, boolean likelyCollide, double result) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Collision data its owner fills again for each probe: a result it combines into the first time stands in for a new
     * one, so nothing read from it may outlive the next {@link #reset}.
     */
    public static final class Reusable extends CollideAxisData {
        private final CollideResult spareX = new CollideResult(false, 0.0D);
        private final CollideResult spareYPos = new CollideResult(false, 0.0D);
        private final CollideResult spareYNeg = new CollideResult(false, 0.0D);
        private final CollideResult spareZ = new CollideResult(false, 0.0D);

        public Reusable() {
            super(null, null, null, null, List.of());
        }

        /** This with every axis unset and no unknown boxes, as {@code new CollideAxisData(null, ..., List.of())}. */
        public Reusable reset() {
            x = null;
            yPos = null;
            yNeg = null;
            z = null;
            unknown = List.of();
            xPos = null;
            xNeg = null;
            zPos = null;
            zNeg = null;
            return this;
        }

        @Override
        public void combineX(boolean likelyCollide, double result, int side) {
            if (x == null) x = spareX.reset(likelyCollide, result, side);
            else x.combine(likelyCollide, result, side);
        }

        @Override
        public void combineYPos(boolean likelyCollide, double result) {
            if (yPos == null) yPos = spareYPos.reset(likelyCollide, result, 0);
            else yPos.combine(likelyCollide, result, 0);
        }

        @Override
        public void combineYNeg(boolean likelyCollide, double result) {
            if (yNeg == null) yNeg = spareYNeg.reset(likelyCollide, result, 0);
            else yNeg.combine(likelyCollide, result, 0);
        }

        @Override
        public void combineZ(boolean likelyCollide, double result, int side) {
            if (z == null) z = spareZ.reset(likelyCollide, result, side);
            else z.combine(likelyCollide, result, side);
        }
    }

    private static final class ImmutableCollideResult extends CollideResult {
        private ImmutableCollideResult(boolean isLikelyCollide, double result, int side) {
            super(isLikelyCollide, result, side);
        }

        static CollideResult of(CollideResult result) {
            return result == null
                    ? null
                    : new ImmutableCollideResult(result.isLikelyCollide, result.result, result.side);
        }

        @Override
        public void setLikelyCollide(boolean isLikelyCollide) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setResult(double result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSide(int side) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CollideResult combine(CollideResult newResult) {
            throw new UnsupportedOperationException();
        }

        @Override
        CollideResult combine(boolean newLikelyCollide, double newResult, int newSide) {
            throw new UnsupportedOperationException();
        }
    }

    @AllArgsConstructor
    @Data
    public static class CollideResult implements DebugText.Recordable {
        boolean isLikelyCollide;
        double result;
        // Direction (+1/-1) the horizontal result was probed in, since a flush result of 0 has no sign; 0 if unknown.
        int side;

        public CollideResult(boolean isLikelyCollide, double result) {
            this(isLikelyCollide, result, 0);
        }

        @Override
        public void appendTo(DebugText.Builder sb) {
            sb.append(isLikelyCollide).append(' ').append(result);
        }

        @Override
        public String toString() {
            return isLikelyCollide + " " + result;
        }

        public CollideResult combine(CollideResult newResult) {
            return combine(newResult.isLikelyCollide, newResult.result, newResult.side);
        }

        private CollideResult reset(boolean isLikelyCollide, double result, int side) {
            this.isLikelyCollide = isLikelyCollide;
            this.result = result;
            this.side = side;
            return this;
        }

        CollideResult combine(boolean newLikelyCollide, double newResult, int newSide) {
            boolean ourSideCollided = side != 0 || isLikelyCollide;
            boolean newSideCollided = newSide != 0 || newLikelyCollide;
            isLikelyCollide = isLikelyCollide || newLikelyCollide;
            boolean areWeGreater = Math.abs(result) > Math.abs(newResult);
            if (areWeGreater) {
                side = newSide;
            } else if (Math.abs(result) == Math.abs(newResult) && side != newSide) {
                // A probe that stopped at no face gives no side; only two faces of the same reach conflict.
                if (!ourSideCollided) side = newSide;
                else if (newSideCollided) side = 0;
            }
            result = areWeGreater ? newResult : result;
            return this;
        }

        public double inMovementSpace(double stuckSpeedMultiplier) {
            return result * stuckSpeedMultiplier;
        }
    }
}
