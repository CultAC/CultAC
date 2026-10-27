package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Objects;

/** The two remote-entity physics paths of protocol 773, allocated only when a block-entity pass reaches a root. */
final class JavaPistonEntityStates {
    private Snapshot other;
    private boolean running;
    private JavaMovementPatch patch = JavaMovementPatch.V_1_21_9;

    JavaPistonEntityStates(PacketEntity entity) {
        other = entity.capturePistonPhysics(true);
    }

    boolean running() {
        return running;
    }

    JavaMovementPatch patch() {
        return patch;
    }

    Snapshot other() {
        return running ? null : other;
    }

    /** Once both completed handlers hold the same physical state, later packets start the same path. */
    boolean canMerge(PacketEntity entity) {
        Snapshot main = entity.capturePistonPhysics(false);
        return main.exact
                && other.exact
                && main.ground == other.ground
                && main.yaw == other.yaw
                && main.pitch == other.pitch
                && main.yawCurrent == other.yawCurrent
                && Objects.equals(main.physicalPosition, other.physicalPosition)
                && Objects.equals(main.codecPosition, other.codecPosition)
                && Objects.equals(main.velocity, other.velocity)
                && main.oldLocation == null
                && other.oldLocation == null
                && main.location.hasExactPosition()
                && other.location.hasExactPosition()
                && !main.location.hasActiveInterpolationTarget()
                && !other.location.hasActiveInterpolationTarget()
                && samePassFields(main.pass, other.pass);
    }

    private static boolean samePassFields(JavaPassState a, JavaPassState b) {
        if (a == null || b == null) return a == b;
        // Position, velocity and ground come from the current entity fields; only these fields outlive its lerp.
        return a.fallDistance() == b.fallDistance()
                && Objects.equals(a.stuckSpeed(), b.stuckSpeed())
                && Objects.equals(a.support(), b.support())
                && Objects.equals(a.pistonDeltas(), b.pistonDeltas());
    }

    /** Packet inputs and interpolation operate on each physical path, never on the union of its boxes. */
    void run(PacketEntity entity, Runnable operation) {
        Snapshot primary = entity.capturePistonPhysics(false);
        running = true;
        try {
            operation.run();
            primary = entity.capturePistonPhysics(false);
            entity.restorePistonPhysics(other);
            patch = JavaMovementPatch.V_1_21_10;
            operation.run();
            other = entity.capturePistonPhysics(false);
        } finally {
            entity.restorePistonPhysics(primary);
            patch = JavaMovementPatch.V_1_21_9;
            running = false;
        }
    }

    record Snapshot(
            ReachInterpolationData oldLocation,
            ReachInterpolationData location,
            JavaPassState pass,
            SimpleCollisionBox physicalBox,
            Vec3 physicalPosition,
            Vec3 codecPosition,
            Vec3 velocity,
            boolean exact,
            boolean ground,
            float yaw,
            float pitch,
            boolean yawCurrent) {
        SimpleCollisionBox bounds(boolean movement, SimpleCollisionBox out) {
            if (physicalBox != null) return out.set(physicalBox);
            if (movement) location.getPossibleMovementLocationCombined(out);
            else location.getPossibleLocationCombined(out);
            if (oldLocation == null) return out;
            SimpleCollisionBox old = new SimpleCollisionBox();
            if (movement) oldLocation.getPossibleMovementLocationCombined(old);
            else oldLocation.getPossibleLocationCombined(old);
            return ReachInterpolationData.combineCollisionBox(old, out, out);
        }

        int candidates() {
            if (physicalBox != null) return 1;
            return location.possibleMovementLocationCount()
                    + (oldLocation == null ? 0 : oldLocation.possibleMovementLocationCount());
        }

        SimpleCollisionBox candidate(int index, SimpleCollisionBox out) {
            if (physicalBox != null) return out.set(physicalBox);
            if (oldLocation != null) {
                int count = oldLocation.possibleMovementLocationCount();
                if (index < count) return oldLocation.possibleMovementLocation(index, out);
                index -= count;
            }
            return location.possibleMovementLocation(index, out);
        }

        SimpleCollisionBox exactBox() {
            if (physicalBox != null) return physicalBox.copy();
            if (oldLocation != null || location == null) return null;
            var boxes = location.getPossibleMovementLocations();
            return boxes.size() == 1 ? boxes.getFirst() : null;
        }
    }
}
