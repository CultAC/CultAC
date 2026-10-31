package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** ClientLevel#getPushableEntities selects only LocalPlayer; the remote entity's tick calls player.push(pusher). */
public final class JavaLivingEntityPushes {
    private JavaLivingEntityPushes() {}

    public static boolean playerPushable(CultPlayer player, SimulationContext context, Vec3 position, boolean gliding) {
        return !player.isDead()
                && player.gamemode != GameMode.SPECTATOR
                && !context.getEntities().selfCertainlyCarriesPassenger()
                && !ac.cult.cultac.utils.nmsutil.Collisions.onClimbable(
                        player, position.x, position.y, position.z, gliding);
    }

    /**
     * A living pusher's LivingEntity#doPush calls player.push(pusher), and LivingEntity#push(Entity) returns while
     * the player isSleeping. AbstractBoat#tick calls boat.push(player) instead, so a boat still pushes a sleeper.
     */
    public static boolean pushedByLiving(CultPlayer player) {
        return !player.isInBed;
    }

    public static boolean ticksPush(PacketEntity entity) {
        // Bat and Parrot do not push players; ArmorStand pushes only rideable minecarts. Dragon's aiStep
        // implements its own server-side wing knockback rather than LivingEntity#pushEntities.
        return entity.isLivingEntity()
                && entity.type != EntityTypeIds.BAT
                && entity.type != EntityTypeIds.PARROT
                && entity.type != EntityTypeIds.ARMOR_STAND
                && entity.type != EntityTypeIds.ENDER_DRAGON;
    }

    public static boolean noPhysics(CultPlayer player, PacketEntity entity) {
        if (!(entity instanceof PacketEntityPlayer remote)) return false;
        if (!remote.actionPresent) return true;
        var info = remote.cachedInfo != null
                ? remote.cachedInfo
                : remote.profile == null ? null : player.compensatedEntities.clientPlayerModes.get(remote.profile);
        return info != null && info.mode() == GameMode.SPECTATOR;
    }

    /** Avatar pose metadata sizes its actual box; position snapshots themselves retain the type dimensions. */
    public static SimpleCollisionBox physicalBox(CultPlayer player, PacketEntity entity, SimpleCollisionBox position) {
        if (entity.isAvatar()) return avatarBox(entity, position, new SimpleCollisionBox());
        return entity.toBoundingBox(player, position.copy());
    }

    /**
     * Whether the {@link #physicalBox} of one of {@code entity}'s movement candidates intersects {@code playerBox}.
     * Without one, every {@link #impulses} entry is zero. Reads the candidates by index into {@code candidate} and
     * {@code physical}, so it allocates nothing. Only for entities whose candidate list is their indexed candidates,
     * which excludes shulkers.
     */
    static boolean anyCandidateIntersects(
            CultPlayer player,
            PacketEntity entity,
            SimpleCollisionBox playerBox,
            SimpleCollisionBox candidate,
            SimpleCollisionBox physical) {
        for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
            entity.possibleMovementCollisionBoxCandidate(i, candidate);
            SimpleCollisionBox box = entity.isAvatar()
                    ? avatarBox(entity, candidate, physical)
                    : entity.toBoundingBox(player, physical.set(candidate));
            if (intersects(box, playerBox)) return true;
        }
        return false;
    }

    private static SimpleCollisionBox avatarBox(
            PacketEntity entity, SimpleCollisionBox position, SimpleCollisionBox out) {
        double x = (position.minX + position.maxX) * 0.5D;
        double z = (position.minZ + position.maxZ) * 0.5D;
        return ac.cult.cultac.utils.nmsutil.GetBoundingBox.getBoundingBoxFromPosAndSize(
                out,
                x,
                position.minY,
                z,
                PacketEntity.avatarWidth(entity.actionPose, entity.scale),
                PacketEntity.avatarHeight(entity.actionPose, entity.scale));
    }

    public static List<Vec3> impulses(List<SimpleCollisionBox> boxes, SimpleCollisionBox playerBox, Vec3 position) {
        var impulses = new ArrayList<Vec3>();
        for (var box : boxes) {
            Vec3 impulse = intersects(box, playerBox) ? impulse(box, position) : Vec3.ZERO;
            if (!impulses.contains(impulse)) impulses.add(impulse);
        }
        return impulses;
    }

    public static boolean intersects(SimpleCollisionBox box, SimpleCollisionBox other) {
        return box.minX < other.maxX
                && box.maxX > other.minX
                && box.minY < other.maxY
                && box.maxY > other.minY
                && box.minZ < other.maxZ
                && box.maxZ > other.minZ;
    }

    /** Entity#push's operations, including its float 0.01 and 0.05 constants and their order. */
    public static Vec3 impulse(SimpleCollisionBox pusher, Vec3 position) {
        return impulse(
                new Vec3((pusher.minX + pusher.maxX) * 0.5D, pusher.minY, (pusher.minZ + pusher.maxZ) * 0.5D),
                position);
    }

    public static Vec3 impulse(Vec3 pusher, Vec3 position) {
        double x = pusher.x - position.x;
        double z = pusher.z - position.z;
        double max = Math.max(Math.abs(x), Math.abs(z));
        if (max < 0.01F) return Vec3.ZERO;
        double root = Math.sqrt(max);
        x /= root;
        z /= root;
        double scale = Math.min(1.0D / root, 1.0D);
        x *= scale;
        z *= scale;
        x *= 0.05F;
        z *= 0.05F;
        return new Vec3(-x, 0.0D, -z);
    }
}
