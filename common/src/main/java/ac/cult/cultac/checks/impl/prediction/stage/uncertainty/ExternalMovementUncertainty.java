package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.math.Vec3;

public final class ExternalMovementUncertainty {
    private ExternalMovementUncertainty() {}

    public static Snapshot capture(PredictionResult result, PredictionResult lastResult) {
        return capture(result.getSimulationContext(), lastResult);
    }

    /** False exactly when every push box {@link #capture(SimulationContext, PredictionResult)} reads is empty. */
    public static boolean mayShove(SimulationContext context, PredictionResult lastResult) {
        PistonPushes lastPush = lastPushes(lastResult);
        PistonPushes currentPush = context.getWorldData().getPistonPushes();
        return present(lastPush, true)
                || present(lastPush, false)
                || present(currentPush, true)
                || present(currentPush, false)
                || currentPush != null && !currentPush.getShulkerBoxPush().isEmpty();
    }

    private static boolean present(PistonPushes pushes, boolean piston) {
        if (pushes == null) return false;
        SimpleCollisionBox box = piston ? pushes.getPistonPush() : pushes.getShulkerPush();
        return box != null && !box.isEmpty();
    }

    private static PistonPushes lastPushes(PredictionResult lastResult) {
        return lastResult == null
                        || lastResult.getSimulationContext() == null
                        || lastResult.getSimulationContext().getWorldData() == null
                ? null
                : lastResult.getSimulationContext().getWorldData().getPistonPushes();
    }

    public static Snapshot capture(SimulationContext context, PredictionResult lastResult) {
        PistonPushes lastPush = lastPushes(lastResult);
        PistonPushes currentPush = context.getWorldData().getPistonPushes();

        SimpleCollisionBox lastPiston = scalePistonPushForStuckSpeedTarget(push(lastPush, true), context);
        SimpleCollisionBox lastShulker = push(lastPush, false);
        // Minecraft#tick runs ClientLevel#tickEntities, then ClientLevel#tickBlockEntities,
        // and only after that sends ServerboundClientTickEndPacket. Cult's
        // CompensatedWorld already phases 1.21.5+ piston boxes to that tick-end
        // boundary before exposing them through WorldData#getPistonPushes(), so
        // the current envelope here is the exact shove visible to the packet
        // presently under prediction, not a future block-entity phase.
        SimpleCollisionBox currentPiston = scalePistonPushForStuckSpeedTarget(push(currentPush, true), context);
        // A packet carries the shulker box block shoves its own WorldData holds and no other packet's.
        SimpleCollisionBox currentShulker = push(currentPush, false);
        if (currentPush != null && !currentPush.getShulkerBoxPush().isEmpty())
            currentShulker.union(currentPush.getShulkerBoxPush());

        SimpleCollisionBox combined = null;
        combined = union(combined, lastPiston);
        combined = union(combined, lastShulker);
        combined = union(combined, currentPiston);
        combined = union(combined, currentShulker);
        if (combined == null) {
            combined = emptyBox();
        }

        return new Snapshot(
                lastPiston,
                lastShulker,
                currentPiston,
                currentShulker,
                combined,
                multiplyEnvelope(combined, context.getLastStuckSpeed()));
    }

    private static SimpleCollisionBox push(PistonPushes pushes, boolean piston) {
        if (pushes == null) {
            return emptyBox();
        }
        SimpleCollisionBox box = piston ? pushes.getPistonPush() : pushes.getShulkerPush();
        return box == null ? emptyBox() : box.copy();
    }

    private static SimpleCollisionBox scalePistonPushForStuckSpeedTarget(
            SimpleCollisionBox push, SimulationContext context) {
        if (push.isEmpty()) {
            return push;
        }

        // MCP-Reborn PistonMovingBlockEntity#moveEntityByPiston calls
        // Entity#move(MoverType.PISTON), and Entity#move explicitly does not
        // apply stuckSpeedMultiplier for MoverType.PISTON. Since
        // SimulationContext#target is divided by the last stuck-speed multiplier,
        // piston displacement must be expressed in that same pre-stuck space.
        double horizontalScalar = context.getTargetScalarHoriz();
        double verticalScalar = context.getTargetScalarVert();
        push.minX *= horizontalScalar;
        push.maxX *= horizontalScalar;
        push.minY *= verticalScalar;
        push.maxY *= verticalScalar;
        push.minZ *= horizontalScalar;
        push.maxZ *= horizontalScalar;
        return push.sort();
    }

    private static SimpleCollisionBox union(SimpleCollisionBox expanded, SimpleCollisionBox next) {
        if (next.isEmpty()) {
            return expanded;
        }
        return expanded == null ? next.copy() : expanded.union(next);
    }

    private static SimpleCollisionBox multiplyEnvelope(SimpleCollisionBox box, Vec3 scale) {
        if (box.isEmpty()) {
            return emptyBox();
        }

        double minX = box.minX * scale.x;
        double maxX = box.maxX * scale.x;
        double minY = box.minY * scale.y;
        double maxY = box.maxY * scale.y;
        double minZ = box.minZ * scale.z;
        double maxZ = box.maxZ * scale.z;

        return new SimpleCollisionBox(
                Math.min(minX, maxX),
                Math.min(minY, maxY),
                Math.min(minZ, maxZ),
                Math.max(minX, maxX),
                Math.max(minY, maxY),
                Math.max(minZ, maxZ));
    }

    private static SimpleCollisionBox emptyBox() {
        return new SimpleCollisionBox();
    }

    public static String formatBox(SimpleCollisionBox box) {
        if (box.isEmpty()) {
            return "empty";
        }
        return "x=[" + format(box.minX) + "," + format(box.maxX) + "]"
                + " y=[" + format(box.minY) + "," + format(box.maxY) + "]"
                + " z=[" + format(box.minZ) + "," + format(box.maxZ) + "]";
    }

    public static String formatVector(Vec3 vector) {
        return format(vector.x) + " " + format(vector.y) + " " + format(vector.z);
    }

    private static String format(double value) {
        return NumFormatter.formatNumberStandard(value).trim();
    }

    public record Snapshot(
            SimpleCollisionBox lastPistonTargetEnvelope,
            SimpleCollisionBox lastShulkerTargetEnvelope,
            SimpleCollisionBox currentPistonTargetEnvelope,
            SimpleCollisionBox currentShulkerTargetEnvelope,
            SimpleCollisionBox combinedTargetEnvelope,
            SimpleCollisionBox combinedClientPositionOnlyEnvelope) {
        public boolean hasExternalMove() {
            return !combinedTargetEnvelope.isEmpty();
        }

        public Vec3 maxAbsTargetDelta() {
            return maxAbs(combinedTargetEnvelope);
        }

        public Vec3 maxAbsClientPositionOnlyDelta() {
            return maxAbs(combinedClientPositionOnlyEnvelope);
        }

        public SimpleCollisionBox combinedTargetEnvelopeCopy() {
            return combinedTargetEnvelope.copy();
        }

        private Vec3 maxAbs(SimpleCollisionBox box) {
            if (box.isEmpty()) {
                return Vec3.ZERO;
            }
            return new Vec3(
                    Math.max(Math.abs(box.minX), Math.abs(box.maxX)),
                    Math.max(Math.abs(box.minY), Math.abs(box.maxY)),
                    Math.max(Math.abs(box.minZ), Math.abs(box.maxZ)));
        }
    }
}
