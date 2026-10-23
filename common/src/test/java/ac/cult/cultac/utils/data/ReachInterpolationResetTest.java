package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ReachInterpolationResetTest {
    private static final SimpleCollisionBox FIRST = new SimpleCollisionBox(0, 64, 0, 0.6, 65.8, 0.6);
    private static final SimpleCollisionBox SECOND = new SimpleCollisionBox(-3.25, 70.5, 9.75, -2.65, 72.3, 10.35);

    @Test
    void aWornFinishedStateResetsToTheStateItsConstructorBuilds() {
        ReachInterpolationData worn = new ReachInterpolationData(FIRST);
        wear(worn);
        assertTrue(worn.resetFinished(SECOND));
        assertSameState(new ReachInterpolationData(SECOND), worn);
    }

    @Test
    void aCopyOfAFinishedStateResetsLikeTheOriginal() {
        ReachInterpolationData original = new ReachInterpolationData(FIRST);
        wear(original);
        ReachInterpolationData copy = original.copy();
        assertTrue(copy.resetFinished(SECOND));
        assertSameState(new ReachInterpolationData(SECOND), copy);
    }

    @Test
    void theResetStateHoldsNoReferenceToItsArgument() {
        SimpleCollisionBox location = SECOND.copy();
        ReachInterpolationData data = new ReachInterpolationData(FIRST);
        assertTrue(data.resetFinished(location));
        location.offset(1, 1, 1);
        assertSameState(new ReachInterpolationData(SECOND), data);
        assertNotSame(location, data.getTargetLocation());
    }

    private static void wear(ReachInterpolationData data) {
        data.getPossibleMovementLocationCombined(new SimpleCollisionBox());
        data.tickMovement(true, true);
        data.setCurrentAndTargetRotation(35.0F, -12.5F);
        data.offset(feet -> new Vec3(0.125, -0.5, 0.25));
        data.resize(0.9F, 1.4F);
        data.updatePossibleStartingLocation(new SimpleCollisionBox(-1, 63, -1, 0.5, 64, 0.5));
        data.setExactMovementLocation(new SimpleCollisionBox(0.2, 64, 0.2, 1.1, 65.4, 1.1));
        data.getPossibleMovementLocationCombined(new SimpleCollisionBox());
        if (data.beginIdleProbe()) data.settleIfIdleTickUnchanged();
        data.markPositionInexact();
    }

    private static void assertSameState(ReachInterpolationData expected, ReachInterpolationData actual) {
        int compared = 0;
        for (Field field : ReachInterpolationData.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object want;
            Object got;
            try {
                want = field.get(expected);
                got = field.get(actual);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            if (want instanceof SimpleCollisionBox wantBox && got instanceof SimpleCollisionBox gotBox) {
                assertEquals(boxBits(wantBox), boxBits(gotBox), field.getName());
            } else if (want instanceof Double || want instanceof Float) {
                assertEquals(want, got, field.getName());
            } else {
                assertTrue(Objects.equals(want, got), field.getName() + ": " + want + " != " + got);
            }
            compared++;
        }
        assertFalse(compared == 0);
    }

    private static String boxBits(SimpleCollisionBox box) {
        return Double.doubleToRawLongBits(box.minX) + "," + Double.doubleToRawLongBits(box.minY) + ","
                + Double.doubleToRawLongBits(box.minZ) + "," + Double.doubleToRawLongBits(box.maxX) + ","
                + Double.doubleToRawLongBits(box.maxY) + "," + Double.doubleToRawLongBits(box.maxZ) + ","
                + box.isFullBlock();
    }
}
