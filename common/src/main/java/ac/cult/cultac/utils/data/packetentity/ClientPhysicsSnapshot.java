package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.math.Vec3;

/** An entity's interpolation and physical fields, saved and restored around a detached replay of its packets. */
record ClientPhysicsSnapshot(
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
        boolean yawCurrent) {}
