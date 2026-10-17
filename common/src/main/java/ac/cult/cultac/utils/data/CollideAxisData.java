package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@AllArgsConstructor
@Data
@ToString
public class CollideAxisData {
    CollideResult x;
    CollideResult yPos;
    CollideResult yNeg;
    CollideResult z;
    List<SimpleCollisionBox> unknown;

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
    public static class CollideResult {
        boolean isLikelyCollide;
        double result;
        // Direction (+1/-1) the horizontal result was probed in, since a flush result of 0 has no sign; 0 if unknown.
        int side;

        public CollideResult(boolean isLikelyCollide, double result) {
            this(isLikelyCollide, result, 0);
        }

        @Override
        public String toString() {
            return isLikelyCollide + " " + result;
        }

        public CollideResult combine(CollideResult newResult) {
            return combine(newResult.isLikelyCollide, newResult.result, newResult.side);
        }

        CollideResult combine(boolean newLikelyCollide, double newResult, int newSide) {
            isLikelyCollide = isLikelyCollide || newLikelyCollide;
            boolean areWeGreater = Math.abs(result) > Math.abs(newResult);
            if (areWeGreater) {
                side = newSide;
            } else if (Math.abs(result) == Math.abs(newResult) && side != newSide) {
                side = 0;
            }
            result = areWeGreater ? newResult : result;
            return this;
        }

        public double inMovementSpace(double stuckSpeedMultiplier) {
            return result * stuckSpeedMultiplier;
        }
    }
}
