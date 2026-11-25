package ac.cult.cultac.utils.collisions.datatypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.blocksim.engine.shapes.Shapes;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CollisionBoxPoolBoxesTest {
    @Test
    void pooledBoxesMatchFreshCopiesBitForBit() {
        CollisionBoxPool pool = CollisionBoxPool.current();
        Random random = new Random(11);
        for (int run = 0; run < 2_000; run++) {
            int mark = pool.markBoxes();
            List<SimpleCollisionBox> taken = new ArrayList<>();
            int count = 1 + random.nextInt(300);
            for (int i = 0; i < count; i++) {
                SimpleCollisionBox from = new SimpleCollisionBox(
                        bits(random),
                        bits(random),
                        bits(random),
                        bits(random),
                        bits(random),
                        bits(random),
                        random.nextBoolean());
                boolean empty = random.nextInt(4) == 0;
                SimpleCollisionBox box = empty ? pool.box() : pool.copyOf(from);
                assertSameBox(empty ? new SimpleCollisionBox() : from.copy(), box);
                for (SimpleCollisionBox other : taken) assertNotSame(other, box);
                taken.add(box);
                box.offset(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            }
            pool.releaseBoxes(mark);
            assertEquals(mark, pool.markBoxes());
        }
    }

    @Test
    void aVoxelBoxIsCopiedAsItself() {
        CollisionBoxPool pool = CollisionBoxPool.current();
        int mark = pool.markBoxes();
        VoxelShapeCollisionBox voxel = new VoxelShapeCollisionBox(Shapes.block(), true);
        SimpleCollisionBox copy = pool.copyOf(voxel);
        assertSame(VoxelShapeCollisionBox.class, copy.getClass());
        assertSameBox(voxel.copy(), copy);
        pool.releaseBoxes(mark);
    }

    private static double bits(Random random) {
        return random.nextInt(8) == 0 ? -0.0 : random.nextGaussian() * 4;
    }

    private static void assertSameBox(SimpleCollisionBox expected, SimpleCollisionBox actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(Double.doubleToRawLongBits(expected.minX), Double.doubleToRawLongBits(actual.minX));
        assertEquals(Double.doubleToRawLongBits(expected.minY), Double.doubleToRawLongBits(actual.minY));
        assertEquals(Double.doubleToRawLongBits(expected.minZ), Double.doubleToRawLongBits(actual.minZ));
        assertEquals(Double.doubleToRawLongBits(expected.maxX), Double.doubleToRawLongBits(actual.maxX));
        assertEquals(Double.doubleToRawLongBits(expected.maxY), Double.doubleToRawLongBits(actual.maxY));
        assertEquals(Double.doubleToRawLongBits(expected.maxZ), Double.doubleToRawLongBits(actual.maxZ));
        assertEquals(expected.isFullBlock, actual.isFullBlock);
    }
}
