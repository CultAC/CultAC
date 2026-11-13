package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SightProofTest {
    private static final SimpleCollisionBox BLOCK = box(0, 1, 6, 1, 2, 7);

    @Test
    void aWallHidesAPointAndABlockFromAWholePath() {
        SightEyes eyes = path(0.2, 1.6, 0.5, 0.8, 1.6, 0.5);
        List<SimpleCollisionBox> wall = List.of(box(-3, 0, 3, 4, 4, 4));
        assertTrue(SightProof.hidden(eyes, BLOCK, wall));
        assertTrue(SightProof.hidden(eyes, point(0.5, 1.5, 6), wall));
        assertFalse(SightProof.hidden(eyes, BLOCK, List.of(box(-3, 0, 8, 4, 4, 9))));
        assertFalse(SightProof.hidden(eyes, point(0.5, 1.5, 6), List.of(box(-3, 0, 8, 4, 4, 9))));
    }

    @Test
    void aGapSeenFromOneEndOfThePathKeepsTheTargetSeen() {
        List<SimpleCollisionBox> slit = List.of(box(-3, 0, 3, 0.9, 4, 4), box(1.0, 0, 3, 4, 4, 4));
        SimpleCollisionBox behind = point(0.95, 1.6, 6);
        assertTrue(SightProof.hidden(path(0.2, 1.6, 0.5, 0.5, 1.6, 0.5), behind, slit));
        assertFalse(SightProof.hidden(path(0.2, 1.6, 0.5, 0.95, 1.6, 0.5), behind, slit));
    }

    @Test
    void blockersAtDifferentDepthsHideAPointFromDifferentEyes() {
        SightEyes eyes = path(-0.5, 1.6, 0.5, 1.5, 1.6, 0.5);
        SimpleCollisionBox near = box(-2, 0, 2, 0.6, 4, 3), far = box(0.4, 0, 4, 3, 4, 5);
        SimpleCollisionBox behind = point(0.5, 1.6, 6);
        assertFalse(SightProof.hidden(eyes, behind, List.of(near)));
        assertFalse(SightProof.hidden(eyes, behind, List.of(far)));
        assertTrue(SightProof.hidden(eyes, behind, List.of(near, far)));
    }

    @Test
    void blockersMayHideAWholeBlockFromDifferentEyes() {
        SightEyes eyes = path(-0.5, 1.6, 0.5, 1.5, 1.6, 0.5);
        SimpleCollisionBox near = box(-2, 0, 2, 1.2, 4, 3), far = box(0.3, 0, 4, 3, 4, 5);
        assertFalse(SightProof.hidden(eyes, BLOCK, List.of(near)));
        assertFalse(SightProof.hidden(eyes, BLOCK, List.of(far)));
        assertTrue(SightProof.hidden(eyes, BLOCK, List.of(near, far)));
    }

    @Test
    void blockersMayHideDifferentPartsOfABlock() {
        SightEyes eyes = path(0.4, 1.6, 0.5, 0.6, 1.6, 0.5);
        SimpleCollisionBox left = box(-1, 0, 3, 0.55, 4, 4), right = box(0.45, 0, 4.2, 2, 4, 5);
        assertFalse(SightProof.hidden(eyes, BLOCK, List.of(left)));
        assertFalse(SightProof.hidden(eyes, BLOCK, List.of(right)));
        assertTrue(SightProof.hidden(eyes, BLOCK, List.of(left, right)));
    }

    @Test
    void aBandOfEyeHeightsIsHiddenOnlyWhenEveryHeightIs() {
        List<SimpleCollisionBox> raised = List.of(box(-2, 1.4, 3, 3, 4, 4));
        SimpleCollisionBox behind = point(0.5, 1.5, 6);
        Vec3 move = new Vec3(0.1, 0, 0);
        assertTrue(
                SightProof.hidden(new SightEyes(new Vec3(0.5, 1.45, 0.5), move, new Vec3(0, 0.5, 0)), behind, raised));
        assertFalse(
                SightProof.hidden(new SightEyes(new Vec3(0.5, 1.0, 0.5), move, new Vec3(0, 1.0, 0)), behind, raised));
        // The lowest eyes see the block's lower part under the blocker too.
        assertFalse(
                SightProof.hidden(new SightEyes(new Vec3(0.5, 1.0, 0.5), move, new Vec3(0, 1.0, 0)), BLOCK, raised));
    }

    @Test
    void anEyeThatDoesNotMoveIsJudgedAsOneEye() {
        SightEyes still = path(0.5, 1.6, 0.5, 0.5, 1.6, 0.5);
        assertTrue(SightProof.hidden(still, BLOCK, List.of(box(-3, 0, 3, 4, 4, 4))));
        assertTrue(SightProof.hidden(still, point(0.5, 1.5, 6), List.of(box(0, 1, 3, 1, 2, 4))));
        assertFalse(SightProof.hidden(still, point(0.5, 1.5, 6), List.of(box(1, 1, 3, 2, 2, 4))));
    }

    @Test
    void eyesInsideTheTargetSeeIt() {
        List<SimpleCollisionBox> around = List.of(box(-3, 0, 5, 4, 4, 5.9));
        assertFalse(SightProof.hidden(path(0.4, 1.5, 6.5, 0.6, 1.5, 6.5), BLOCK, around));
        assertFalse(SightProof.hidden(path(0.5, 1.5, 5.95, 0.5, 1.5, 6.5), BLOCK, around));
    }

    @Test
    void aBlockerGivesUpItsRim() {
        // Sight lines along a blocker's very edge are not counted as met.
        SightEyes eyes = path(0.5, 1.6, 0.5, 0.5, 1.6, 0.5);
        assertFalse(SightProof.hidden(
                eyes, point(0.5, 1.6, 6), List.of(box(-3, 0, 3, 0.5, 4, 4), box(0.5, 0, 4, 4, 4, 5))));
    }

    @Test
    void nothingIsHiddenThatASampledSightLineReaches() {
        Random random = new Random(20261109);
        int hidden = 0, sampledHidden = 0;
        for (int scene = 0; scene < 600; scene++) {
            boolean band = (scene & 1) != 0, block = (scene & 2) != 0;
            Vec3 feet = new Vec3(random.nextDouble(), 64, random.nextDouble());
            double speed = random.nextInt(4) == 0 ? 0 : 0.05 + random.nextDouble() * 0.55,
                    yaw = random.nextDouble() * Math.PI * 2;
            Vec3 move = new Vec3(
                    Math.cos(yaw) * speed,
                    random.nextInt(3) == 0 ? (random.nextDouble() - 0.5) * 0.9 : 0,
                    Math.sin(yaw) * speed);
            SightEyes eyes = band
                    ? new SightEyes(feet.add(0, 0.4, 0), move, new Vec3(0, 1.22, 0))
                    : new SightEyes(feet.add(0, 1.62, 0), move, Vec3.ZERO);
            int tx, ty, tz;
            do {
                double distance = 1.5 + random.nextDouble() * 5, heading = random.nextDouble() * Math.PI * 2;
                double pitch = (random.nextDouble() - 0.5) * 2.2;
                tx = (int) Math.floor(feet.x + Math.cos(heading) * Math.cos(pitch) * distance);
                ty = (int) Math.floor(feet.y + 1.5 + Math.sin(pitch) * distance);
                tz = (int) Math.floor(feet.z + Math.sin(heading) * Math.cos(pitch) * distance);
            } while (Math.abs(tx) <= 1 && Math.abs(tz) <= 1 && ty >= 63 && ty <= 67);
            SimpleCollisionBox target = block
                    ? box(tx, ty, tz, tx + 1, ty + 1, tz + 1)
                    : point(tx + random.nextDouble(), ty + random.nextDouble(), tz + random.nextDouble());

            // Blocks all around, but none in the player's own space or the target's.
            double density = 0.1 + random.nextDouble() * 0.4;
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            for (int x = Math.min(tx, -1) - 1; x <= Math.max(tx, 1) + 1; x++) {
                for (int y = Math.min(ty, 63) - 1; y <= Math.max(ty, 66) + 1; y++) {
                    for (int z = Math.min(tz, -1) - 1; z <= Math.max(tz, 1) + 1; z++) {
                        if (x == tx && y == ty && z == tz) continue;
                        if (Math.abs(x) <= 1 && Math.abs(z) <= 1 && y >= 63 && y <= 67) continue;
                        if (random.nextDouble() >= density) continue;
                        boxes.add(
                                switch (random.nextInt(6)) {
                                    case 0 -> box(x, y, z, x + 1, y + 0.5, z + 1);
                                    case 1 -> box(x + 0.375, y, z + 0.375, x + 0.625, y + 1, z + 0.625);
                                    default -> box(x, y, z, x + 1, y + 1, z + 1);
                                });
                    }
                }
            }
            List<SimpleCollisionBox> merged = SightBeam.merge(boxes, 64);
            boolean seen = sampledSeen(eyes, target, boxes);
            if (!seen) sampledHidden++;
            if (!SightProof.hidden(eyes, target, merged)) continue;
            hidden++;
            assertFalse(seen, "scene " + scene + " is seen but was proved hidden");
        }
        // The proof is not vacuous: most of what no sampled line reaches is proved hidden.
        assertTrue(hidden * 10 >= sampledHidden * 6, hidden + " proved of " + sampledHidden);
    }

    private static boolean sampledSeen(SightEyes eyes, SimpleCollisionBox target, List<SimpleCollisionBox> boxes) {
        int grid = target.maxX - target.minX < 0.1 ? 1 : 9;
        for (int t = 0; t <= 16; t++) {
            for (int h = 0; h <= (eyes.band() ? 8 : 0); h++) {
                Vec3 eye = eyes.at(t / 16.0, h / 8.0);
                for (int a = 0; a < grid; a++) {
                    for (int b = 0; b < grid; b++) {
                        for (int face = 0; face < 6; face++) {
                            double[] p = {
                                target.minX + (target.maxX - target.minX) * (a + 0.5) / grid,
                                target.minY + (target.maxY - target.minY) * (a + 0.5) / grid,
                                target.minZ + (target.maxZ - target.minZ) * (a + 0.5) / grid
                            };
                            int axis = face >> 1, other = (axis + 1) % 3;
                            p[other] =
                                    min(target, other) + (max(target, other) - min(target, other)) * (b + 0.5) / grid;
                            p[axis] = (face & 1) == 0 ? min(target, axis) : max(target, axis);
                            if (clear(eye, new Vec3(p[0], p[1], p[2]), boxes)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // Whether the segment misses every box, each grown a hair so that a line along a face counts as blocked.
    private static boolean clear(Vec3 from, Vec3 to, List<SimpleCollisionBox> boxes) {
        double[] start = {from.x, from.y, from.z}, delta = {to.x - from.x, to.y - from.y, to.z - from.z};
        next:
        for (SimpleCollisionBox box : boxes) {
            double enter = 0, exit = 1;
            for (int axis = 0; axis < 3; axis++) {
                double low = min(box, axis) - 1.0E-9, high = max(box, axis) + 1.0E-9;
                if (delta[axis] == 0) {
                    if (start[axis] < low || start[axis] > high) continue next;
                    continue;
                }
                double a = (low - start[axis]) / delta[axis], b = (high - start[axis]) / delta[axis];
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
                if (enter > exit) continue next;
            }
            return false;
        }
        return true;
    }

    private static double min(SimpleCollisionBox box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double max(SimpleCollisionBox box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }

    private static SightEyes path(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new SightEyes(new Vec3(x0, y0, z0), new Vec3(x1 - x0, y1 - y0, z1 - z0), Vec3.ZERO);
    }

    private static SimpleCollisionBox point(double x, double y, double z) {
        double precision = 1.0E-6;
        return box(x - precision, y - precision, z - precision, x + precision, y + precision, z + precision);
    }

    private static SimpleCollisionBox box(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
