/*
 * Pose ordering adapted from PacketEvents EntityPose,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.value;

/** Entity metadata poses in every supported release (1.21.3 through 26.3). */
public enum EntityPose {
    STANDING,
    FALL_FLYING,
    SLEEPING,
    SWIMMING,
    SPIN_ATTACK,
    CROUCHING,
    LONG_JUMPING,
    DYING,
    CROAKING,
    USING_TONGUE,
    SITTING,
    ROARING,
    SNIFFING,
    EMERGING,
    DIGGING,
    SLIDING,
    SHOOTING,
    INHALING;

    private static final EntityPose[] VALUES = values();

    /** The pinned client's pose codec selects standing for an out-of-range ID. */
    public static EntityPose byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : STANDING;
    }
}
