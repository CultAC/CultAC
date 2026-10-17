package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CollideAxisDataImmutableTest {
    @Test
    void sharedDataRejectsEveryWrite() {
        CollideResult source = new CollideResult(true, -0.25);
        CollideAxisData data = CollideAxisData.immutable(source, null, null, new CollideResult(false, 0));
        CollideResult x = data.getX();

        assertThrows(UnsupportedOperationException.class, () -> data.setX(new CollideResult(false, 1)));
        assertThrows(UnsupportedOperationException.class, () -> data.setYPos(new CollideResult(false, 1)));
        assertThrows(UnsupportedOperationException.class, () -> data.setYNeg(new CollideResult(false, 1)));
        assertThrows(UnsupportedOperationException.class, () -> data.setZ(new CollideResult(false, 1)));
        assertThrows(UnsupportedOperationException.class, () -> data.setUnknown(new ArrayList<>()));
        assertThrows(UnsupportedOperationException.class, () -> data.combineX(true, 1, 1));
        assertThrows(UnsupportedOperationException.class, () -> data.combineYPos(true, 1));
        assertThrows(UnsupportedOperationException.class, () -> data.combineYNeg(true, 1));
        assertThrows(UnsupportedOperationException.class, () -> data.combineZ(true, 1, 1));
        assertThrows(
                UnsupportedOperationException.class, () -> data.getUnknown().add(new SimpleCollisionBox()));
        assertThrows(UnsupportedOperationException.class, () -> x.setResult(1));
        assertThrows(UnsupportedOperationException.class, () -> x.setLikelyCollide(false));
        assertThrows(UnsupportedOperationException.class, () -> x.setSide(1));
        assertThrows(UnsupportedOperationException.class, () -> x.combine(new CollideResult(false, 0)));
        assertThrows(UnsupportedOperationException.class, () -> x.combine(false, 0, 0));

        assertTrue(x.isLikelyCollide());
        assertEquals(Double.doubleToLongBits(-0.25), Double.doubleToLongBits(x.getResult()));
        assertFalse(data.getZ().isLikelyCollide());
        assertNull(data.getYPos());
        assertNull(data.getYNeg());
        assertTrue(data.getUnknown().isEmpty());

        source.setResult(5);
        assertEquals(
                Double.doubleToLongBits(-0.25),
                Double.doubleToLongBits(data.getX().getResult()));
    }
}
