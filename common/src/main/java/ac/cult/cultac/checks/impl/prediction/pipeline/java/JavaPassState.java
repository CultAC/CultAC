package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;

/**
 * The local player's Entity fields a block entity's Entity#move reads and writes: position, deltaMovement, onGround,
 * mainSupportingBlockPos with onGroundNoBlocks, stuckSpeedMultiplier (null when zero), fallDistance and the piston
 * deltas, plus the powder contact accumulated since this tick's Entity#baseTick cleared it. Water/lava contact is
 * the fluid refresh state left by travel and each reached virtual fall callback, not a fresh position query.
 * The player eye cache belongs to the local player even when this pass moves its root vehicle.
 */
public record JavaPassState(
        Vec3 position,
        Vec3 velocity,
        boolean onGround,
        MainSupportingBlockData support,
        Vec3 stuckSpeed,
        double fallDistance,
        ClientPistonDeltas pistonDeltas,
        boolean powderContact,
        boolean inWater,
        boolean inLava,
        int playerEyeFluidContact) {

    /** WATER membership at refresh; queries across tag updates use playerEyeFluidContact. */
    public boolean playerEyeInWater() {
        return (playerEyeFluidContact & 1) != 0;
    }

    public JavaPassState(
            Vec3 position,
            Vec3 velocity,
            boolean onGround,
            MainSupportingBlockData support,
            Vec3 stuckSpeed,
            double fallDistance,
            ClientPistonDeltas pistonDeltas,
            boolean powderContact,
            boolean inWater,
            boolean inLava,
            boolean playerEyeInWater) {
        this(
                position,
                velocity,
                onGround,
                support,
                stuckSpeed,
                fallDistance,
                pistonDeltas,
                powderContact,
                inWater,
                inLava,
                ac.cult.cultac.utils.nmsutil.ClientFluidQueries.eyeContactFromWater(playerEyeInWater));
    }

    public JavaPassState(
            Vec3 position,
            Vec3 velocity,
            boolean onGround,
            MainSupportingBlockData support,
            Vec3 stuckSpeed,
            double fallDistance,
            ClientPistonDeltas pistonDeltas,
            boolean powderContact,
            boolean inWater,
            boolean inLava) {
        this(
                position,
                velocity,
                onGround,
                support,
                stuckSpeed,
                fallDistance,
                pistonDeltas,
                powderContact,
                inWater,
                inLava,
                0);
    }

    public JavaPassState(
            Vec3 position,
            Vec3 velocity,
            boolean onGround,
            MainSupportingBlockData support,
            Vec3 stuckSpeed,
            double fallDistance,
            ClientPistonDeltas pistonDeltas,
            boolean powderContact) {
        this(
                position,
                velocity,
                onGround,
                support,
                stuckSpeed,
                fallDistance,
                pistonDeltas,
                powderContact,
                false,
                false);
    }

    public JavaPassState(
            Vec3 position,
            Vec3 velocity,
            boolean onGround,
            MainSupportingBlockData support,
            Vec3 stuckSpeed,
            double fallDistance,
            ClientPistonDeltas pistonDeltas) {
        this(position, velocity, onGround, support, stuckSpeed, fallDistance, pistonDeltas, false);
    }

    public JavaPassState withVelocity(Vec3 velocity) {
        return new JavaPassState(
                position,
                velocity,
                onGround,
                support,
                stuckSpeed,
                fallDistance,
                pistonDeltas,
                powderContact,
                inWater,
                inLava,
                playerEyeFluidContact);
    }

    public JavaPassState withPistonDeltas(ClientPistonDeltas pistonDeltas) {
        return new JavaPassState(
                position,
                velocity,
                onGround,
                support,
                stuckSpeed,
                fallDistance,
                pistonDeltas,
                powderContact,
                inWater,
                inLava,
                playerEyeFluidContact);
    }
}
