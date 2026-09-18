package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@ToString
public class CollideAxisData {
    CollideResult x;
    CollideResult yPos;
    CollideResult yNeg;
    CollideResult z;
    List<SimpleCollisionBox> unknown = new ArrayList<>();

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

    public boolean couldCollideHorizontally() {
        return x.isLikelyCollide() || z.isLikelyCollide();
    }

    @AllArgsConstructor
    @Data
    public static class CollideResult {
        boolean isLikelyCollide;
        double result;

        @Override
        public String toString() {
            return isLikelyCollide + " " + result;
        }

        public CollideResult combine(CollideResult newResult) {
            isLikelyCollide = isLikelyCollide || newResult.isLikelyCollide;
            boolean areWeGreater = Math.abs(result) > Math.abs(newResult.result);
            result = areWeGreater ? newResult.result : result;
            return this;
        }

        public double inMovementSpace(double stuckSpeedMultiplier) {
            return result * stuckSpeedMultiplier;
        }
    }
}
