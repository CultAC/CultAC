package ac.cult.cultac.utils.collisions.datatypes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.utils.nmsutil.ClientShapeBoxes;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoxelShapeCollisionBoxBoundsTest {
    @Test
    void constructorBoundsMatchBoxScan() {
        for (VoxelShape shape : List.of(
                Shapes.block(),
                Shapes.create(0, 0, 0, 1, 1, 1),
                Shapes.create(0, 0, 0, 1, 0.5, 1),
                Shapes.create(0.375, 0, 0.375, 0.625, 1.5, 0.625))) {
            for (boolean fullBlock : new boolean[] {false, true}) {
                assertEquals(reference(shape), describe(new VoxelShapeCollisionBox(shape, fullBlock)));
            }
        }
    }

    private static String reference(VoxelShape shape) {
        double[] bounds = {
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        int[] count = {0};
        ClientShapeBoxes.forEach(shape, (x1, y1, z1, x2, y2, z2) -> {
            bounds[0] = Math.min(bounds[0], x1);
            bounds[1] = Math.min(bounds[1], y1);
            bounds[2] = Math.min(bounds[2], z1);
            bounds[3] = Math.max(bounds[3], x2);
            bounds[4] = Math.max(bounds[4], y2);
            bounds[5] = Math.max(bounds[5], z2);
            count[0]++;
        });
        boolean full = count[0] == 1
                && bounds[0] == 0
                && bounds[1] == 0
                && bounds[2] == 0
                && bounds[3] == 1
                && bounds[4] == 1
                && bounds[5] == 1;
        return bits(bounds) + full;
    }

    private static String describe(VoxelShapeCollisionBox box) {
        return bits(new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}) + box.isFullBlock;
    }

    private static String bits(double[] values) {
        var result = new StringBuilder();
        for (double value : values)
            result.append(Long.toHexString(Double.doubleToRawLongBits(value))).append(' ');
        return result.toString();
    }
}
