package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Feasibility of VoxelShape.clip's initial sample, with its opposite dominant-axis face. */
final class NativeInsideSelection {
    private record Plane(ExactFraction[] normal, ExactFraction constant) {}

    private NativeInsideSelection() {}

    static boolean possible(
            Vec3 eye,
            SimpleCollisionBox box,
            Direction face,
            double error,
            double minimumSample,
            double maximumSample,
            double interactionRange) {
        double[] origin = {eye.x, eye.y, eye.z},
                low = {box.minX, box.minY, box.minZ},
                high = {box.maxX, box.maxY, box.maxZ};
        if (maximumSample <= 0) return false;
        List<Plane> planes = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            if (origin[axis] + maximumSample < low[axis] - error || origin[axis] - maximumSample > high[axis] + error)
                return false;
            planes.add(new Plane(
                    normal(axis, 1),
                    ExactFraction.of(origin[axis])
                            .subtract(ExactFraction.of(Math.min(high[axis] + error, origin[axis] + maximumSample)))));
            planes.add(new Plane(
                    normal(axis, -1),
                    ExactFraction.of(Math.max(low[axis] - error, origin[axis] - maximumSample))
                            .subtract(ExactFraction.of(origin[axis]))));
        }
        int dominant = switch (face) {
            case WEST, EAST -> 0;
            case DOWN, UP -> 1;
            case NORTH, SOUTH -> 2;
        };
        int sign = switch (face) {
            case WEST, DOWN, NORTH -> 1;
            case EAST, UP, SOUTH -> -1;
        };
        planes.add(new Plane(normal(dominant, -sign), ExactFraction.ZERO));
        for (int axis = 0; axis < 3; axis++)
            if (axis != dominant)
                for (int otherSign : new int[] {-1, 1}) {
                    ExactFraction[] n = normal(dominant, -sign);
                    n[axis] = ExactFraction.of(otherSign);
                    planes.add(new Plane(n, ExactFraction.ZERO));
                }
        ExactFraction minimum = feasible(planes, zero()) ? ExactFraction.ZERO : null, maximum = null;
        // A quadratic minimum lies on a face, edge or vertex; a maximum of the
        // norm over this bounded polytope lies at a vertex. Enumerate those active
        // constraints exactly instead of sampling directions.
        for (int i = 0; i < planes.size(); i++)
            for (int j = -1; j < i; j++)
                for (int k = -1; k < j || k == -1; k++) {
                    if (j == -1 && k != -1) continue;
                    List<Plane> active = new ArrayList<>();
                    active.add(planes.get(i));
                    if (j >= 0) active.add(planes.get(j));
                    if (k >= 0) active.add(planes.get(k));
                    ExactFraction[] point = project(active);
                    if (point == null || !feasible(planes, point)) continue;
                    ExactFraction norm = dot(point, point);
                    if (minimum == null || norm.compareTo(minimum) < 0) minimum = norm;
                    if (active.size() == 3 && (maximum == null || norm.compareTo(maximum) > 0)) maximum = norm;
                }
        if (minimum == null || maximum == null) return false;
        ExactFraction lo = ExactFraction.of(minimumSample),
                hi = ExactFraction.of(Math.min(interactionRange, Math.sqrt(3) * maximumSample));
        return minimum.compareTo(hi.multiply(hi)) <= 0 && maximum.compareTo(lo.multiply(lo)) >= 0;
    }

    private static ExactFraction[] project(List<Plane> planes) {
        int size = planes.size();
        ExactFraction[][] matrix = new ExactFraction[size][size + 1];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) matrix[i][j] = dot(planes.get(i).normal, planes.get(j).normal);
            matrix[i][size] = planes.get(i).constant.negate();
        }
        for (int col = 0; col < size; col++) {
            int pivot = col;
            while (pivot < size && matrix[pivot][col].signum() == 0) pivot++;
            if (pivot == size) return null;
            var row = matrix[pivot];
            matrix[pivot] = matrix[col];
            matrix[col] = row;
            ExactFraction divisor = matrix[col][col];
            for (int j = col; j <= size; j++) matrix[col][j] = matrix[col][j].divide(divisor);
            for (int i = 0; i < size; i++)
                if (i != col) {
                    ExactFraction factor = matrix[i][col];
                    for (int j = col; j <= size; j++)
                        matrix[i][j] = matrix[i][j].subtract(factor.multiply(matrix[col][j]));
                }
        }
        ExactFraction[] point = zero();
        for (int axis = 0; axis < 3; axis++)
            for (int i = 0; i < size; i++)
                point[axis] = point[axis].add(planes.get(i).normal[axis].multiply(matrix[i][size]));
        return point;
    }

    private static boolean feasible(List<Plane> planes, ExactFraction[] point) {
        for (Plane plane : planes)
            if (dot(plane.normal, point).add(plane.constant).signum() > 0) return false;
        return true;
    }

    private static ExactFraction dot(ExactFraction[] a, ExactFraction[] b) {
        ExactFraction sum = ExactFraction.ZERO;
        for (int i = 0; i < 3; i++) sum = sum.add(a[i].multiply(b[i]));
        return sum;
    }

    private static ExactFraction[] zero() {
        return new ExactFraction[] {ExactFraction.ZERO, ExactFraction.ZERO, ExactFraction.ZERO};
    }

    private static ExactFraction[] normal(int axis, int sign) {
        var n = zero();
        n[axis] = ExactFraction.of(sign);
        return n;
    }
}
