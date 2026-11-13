package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.List;

/**
 * Current-tick contact states from the existing moving-actor position budget. A translated actor is swept normally;
 * its minimum translation and every selected endpoint share the same distance budget. Blocks are never translated.
 */
final class BedrockSolidActorPositions {
    private BedrockSolidActorPositions() {}

    static int axes(
            List<BlockCollision> shapes,
            WorldCollisionBox start,
            Vec3d request,
            Vec3d nominalEnd,
            int nominal,
            BedrockCoordinateFrame frame) {
        int axes = 0;
        for (int index = shapes.size() - 1; index >= 0; index--) {
            BlockCollision shape = shapes.get(index);
            if (!BedrockCollisionClipper.isSolidActor(shape)) break;
            var face = BedrockCollisionClipper.bufferedFace(shape, start, frame);
            if (face == null) continue;
            float landmark = landmark((nominal & 1 << face.axis()) != 0 ? request : nominalEnd, request, face.axis());
            boolean forced = (nominal & 1 << face.axis()) == 0 && forcesPush(face, component(request, face.axis()));
            if ((forced || towards(landmark, component(request, face.axis())))
                    && Math.abs(face.edge() - landmark) <= face.budget()) axes |= 1 << face.axis();
        }
        return axes;
    }

    static Placement place(
            List<BlockCollision> shapes,
            WorldCollisionBox start,
            Vec3d request,
            Vec3d nominalEnd,
            BedrockCoordinateFrame frame,
            int changed,
            int nominal) {
        if (changed == 0) return null;
        int[] nearest = {-1, -1, -1};
        float[] nearestDistance = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        int firstActor = shapes.size();
        while (firstActor > 0 && BedrockCollisionClipper.isSolidActor(shapes.get(firstActor - 1))) firstActor--;
        for (int index = firstActor; index < shapes.size(); index++) {
            var face = BedrockCollisionClipper.bufferedFace(shapes.get(index), start, frame);
            if (face == null || (changed & 1 << face.axis()) == 0 || (nominal & 1 << face.axis()) != 0) continue;
            float landmark = landmark(nominalEnd, request, face.axis());
            if (!forcesPush(face, component(request, face.axis()))
                    && !towards(landmark, component(request, face.axis()))) continue;
            float distance = Math.abs(face.edge() - landmark);
            if (distance < nearestDistance[face.axis()] && distance <= face.budget()) {
                nearest[face.axis()] = index;
                nearestDistance[face.axis()] = distance;
            }
        }
        List<BlockCollision> placed = new ArrayList<>(shapes);
        double[] reference = new double[3];
        float budget = Float.POSITIVE_INFINITY;
        int applied = 0;
        int positiveFaces = 0;
        for (int index = firstActor; index < shapes.size(); index++) {
            BlockCollision shape = shapes.get(index);
            var face = BedrockCollisionClipper.bufferedFace(shape, start, frame);
            if (face == null || (changed & 1 << face.axis()) == 0) continue;
            int axis = face.axis();
            float move = component(request, axis);
            boolean positive = face.direction() > 0;
            boolean release = (nominal & 1 << axis) != 0;
            boolean forced = !release && forcesPush(face, move);
            boolean clips = positive ? face.edge() < move : face.edge() > move;
            if (release ? !clips : nearest[axis] != index) continue;
            float leading = leading(start, axis, positive, frame);
            float landmark = release ? move : landmark(nominalEnd, request, axis);
            if (!forced && !towards(landmark, move)) return null;
            float endFace = leading + landmark;
            // The face's float ULP, rather than the much smaller delta ULP, decides the contact bit.
            float wanted = release == positive ? Math.nextUp(endFace) : Math.nextDown(endFace);
            if (forced) {
                // A shove against a zero or away move must survive the existing float contact cleanup.
                float inset = Math.max((float) BedrockCollisionSweep.CONTACT_EPSILON, Math.ulp(endFace));
                wanted = positive ? Math.nextDown(endFace - inset) : Math.nextUp(endFace + inset);
            }
            if (!release && !forced && !towards(wanted - leading, move)) return null;
            float original = leading(shape.box(), axis, !positive, frame);
            double shift = (double) wanted - original;
            if (Math.abs(shift) > face.budget()) return null;
            WorldCollisionBox box =
                    shape.box().move(axis == 0 ? shift : 0, axis == 1 ? shift : 0, axis == 2 ? shift : 0);
            placed.set(index, new BlockCollision(shape.block(), box));
            if (Math.abs(shift) > Math.abs(reference[axis])) reference[axis] = -shift;
            budget = Math.min(budget, face.budget());
            applied |= 1 << axis;
            if (positive) positiveFaces |= 1 << axis;
        }
        if (applied != changed) return null;
        Vec3d offset = new Vec3d(reference[0], reference[1], reference[2]);
        if (offset.length() > budget) return null;
        return new Placement(placed, offset, budget, changed, nominal, positiveFaces);
    }

    record Placement(
            List<BlockCollision> shapes, Vec3d reference, float budget, int changed, int nominal, int positiveFaces) {
        BedrockActorOrderRange range(BedrockActorOrderRange previous) {
            double[] low =
                    previous == null ? new double[3] : new double[] {previous.minX(), previous.minY(), previous.minZ()};
            double[] high =
                    previous == null ? new double[3] : new double[] {previous.maxX(), previous.maxY(), previous.maxZ()};
            BedrockActorOrderRange base = previous == null ? null : previous.unbuffered();
            double[] baseLow = base == null ? new double[3] : new double[] {base.minX(), base.minY(), base.minZ()};
            double[] baseHigh = base == null ? new double[3] : new double[] {base.maxX(), base.maxY(), base.maxZ()};
            for (int axis = 0; axis < 3; axis++) {
                if ((changed & 1 << axis) == 0) continue;
                if ((nominal & 1 << axis) != 0) low[axis] = high[axis] = 0.0D;
                else if ((positiveFaces & 1 << axis) != 0) high[axis] = Math.min(high[axis], 0.0D);
                else low[axis] = Math.max(low[axis], 0.0D);
                baseLow[axis] = baseHigh[axis] = axis == 0 ? reference.x() : axis == 1 ? reference.y() : reference.z();
            }
            float available = previous == null || previous.bufferedAxes() == 0
                    ? budget
                    : (float) Math.min(budget, previous.bufferDistance());
            return new BedrockActorOrderRange(
                    low[0],
                    low[1],
                    low[2],
                    high[0],
                    high[1],
                    high[2],
                    changed | (previous == null ? 0 : previous.bufferedAxes()),
                    available,
                    new BedrockActorOrderRange(
                            baseLow[0], baseLow[1], baseLow[2], baseHigh[0], baseHigh[1], baseHigh[2]));
        }
    }

    private static float component(Vec3d value, int axis) {
        return (float) (axis == 0 ? value.x() : axis == 1 ? value.y() : value.z());
    }

    private static boolean towards(float end, float request) {
        if (request == 0.0F) return end == 0.0F;
        return request > 0.0F ? end > 0.0F && end <= request : request < 0.0F && end < 0.0F && end >= request;
    }

    private static boolean forcesPush(BedrockCollisionClipper.BufferedFace face, float request) {
        return request == 0.0F || (request > 0.0F) != (face.direction() > 0);
    }

    private static float landmark(Vec3d end, Vec3d request, int axis) {
        float move = component(request, axis);
        // Rebuilding the float feet from the moved box can round past the requested delta.
        return Math.max(Math.min(0.0F, move), Math.min(Math.max(0.0F, move), component(end, axis)));
    }

    private static float leading(WorldCollisionBox box, int axis, boolean upper, BedrockCoordinateFrame frame) {
        return switch (axis) {
            case 0 -> frame.localX(upper ? box.maxX() : box.minX());
            case 1 -> (float) (upper ? box.maxY() : box.minY());
            default -> frame.localZ(upper ? box.maxZ() : box.minZ());
        };
    }
}
