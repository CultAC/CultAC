package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockEntityInterpolation;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.session.GeyserSession;

/** Reads final entity transforms; the shared entity tracker remains their only consumer. */
final class GeyserEntityPositions {
    // Partial packets refer to the last written position, which may not be acknowledged yet.
    private final Map<Long, Position> written = new HashMap<>();
    private final ArrayList<Runnable> pending = new ArrayList<>();

    static boolean hasPosition(BedrockPacket packet) {
        return packet instanceof AddEntityPacket || packet instanceof AddPlayerPacket
                || packet instanceof MoveEntityAbsolutePacket || packet instanceof MoveEntityDeltaPacket
                || packet instanceof MovePlayerPacket;
    }

    void capture(GeyserSession session, CultPlayer player, BedrockPacket packet, BedrockCoordinateFrame coordinates) {
        long runtimeId;
        Vector3f position;
        float yaw, pitch;
        boolean ground, teleport, spawn;
        Vector3f motion = null;
        boolean forceLocal = false;
        boolean forceCompletion = false;
        if (packet instanceof SetEntityMotionPacket move) {
            Position previous = written.get(move.getRuntimeEntityId());
            if (previous != null) {
                long id = move.getRuntimeEntityId();
                Vec3 velocity = new Vec3(move.getMotion().getX(), move.getMotion().getY(), move.getMotion().getZ());
                pending.add(() -> player.latencyUtils.addRealTimeTask(previous.creationTransaction(), () -> {
                    PacketEntity entity = player.compensatedEntities.getEntity(previous.javaId());
                    if (entity != null && entity.bedrockRuntimeId == id
                            && !player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(entity, velocity))
                        entity.deltaMovement = velocity;
                }));
            }
            return;
        }
        if (packet instanceof RemoveEntityPacket remove) {
            long removedId = remove.getUniqueEntityId();
            Position removed = written.remove(removedId);
            if (removed != null) pending.add(() -> player.latencyUtils.addRealTimeTask(removed.creationTransaction(), () -> {
                PacketEntity entity = player.compensatedEntities.getEntity(removed.javaId());
                if (entity != null && entity.bedrockRuntimeId == removedId)
                    player.compensatedEntities.removeEntity(removed.javaId());
            }));
            return;
        }
        if (packet instanceof AddEntityPacket add) {
            runtimeId = add.getRuntimeEntityId();
            position = add.getPosition(); yaw = add.getRotation().getY(); pitch = add.getRotation().getX();
            ground = false; teleport = true; spawn = true; motion = add.getMotion();
        } else if (packet instanceof AddPlayerPacket add) {
            runtimeId = add.getRuntimeEntityId();
            position = add.getPosition(); yaw = add.getRotation().getY(); pitch = add.getRotation().getX();
            ground = false; teleport = true; spawn = true; motion = add.getMotion();
        } else if (packet instanceof MoveEntityAbsolutePacket move) {
            runtimeId = move.getRuntimeEntityId();
            position = move.getPosition(); yaw = byteAngle(move.getRotation().getY()); pitch = byteAngle(move.getRotation().getX());
            ground = move.isOnGround(); teleport = move.isTeleported(); spawn = false;
            forceLocal = move.isForceMove();
            forceCompletion = move.isForceCompletion();
        } else if (packet instanceof MovePlayerPacket move) {
            runtimeId = move.getRuntimeEntityId();
            position = move.getPosition(); yaw = move.getRotation().getY(); pitch = move.getRotation().getX();
            ground = move.isOnGround(); teleport = move.getMode() != MovePlayerPacket.Mode.NORMAL; spawn = false;
        } else if (packet instanceof MoveEntityDeltaPacket move) {
            runtimeId = move.getRuntimeEntityId();
            Position previous = written.get(runtimeId);
            if (previous == null) return;
            var flags = move.getFlags();
            Vec3 local = coordinates.toLocal(previous.world());
            position = Vector3f.from(flags.contains(MoveEntityDeltaPacket.Flag.HAS_X) ? move.getX() : (float) local.x,
                    flags.contains(MoveEntityDeltaPacket.Flag.HAS_Y) ? move.getY() : (float) local.y,
                    flags.contains(MoveEntityDeltaPacket.Flag.HAS_Z) ? move.getZ() : (float) local.z);
            yaw = flags.contains(MoveEntityDeltaPacket.Flag.HAS_YAW) ? byteAngle(move.getYaw()) : previous.yaw();
            pitch = flags.contains(MoveEntityDeltaPacket.Flag.HAS_PITCH) ? byteAngle(move.getPitch()) : previous.pitch();
            ground = flags.contains(MoveEntityDeltaPacket.Flag.ON_GROUND);
            teleport = flags.contains(MoveEntityDeltaPacket.Flag.TELEPORTING); spawn = false;
            forceLocal = flags.contains(MoveEntityDeltaPacket.Flag.FORCE_MOVE_LOCAL_ENTITY);
            forceCompletion = flags.contains(MoveEntityDeltaPacket.Flag.FORCE_COMPLETION);
        } else return;

        if (runtimeId == session.getPlayerEntity().geyserId()) return;
        var translated = session.getEntityCache().getEntityByGeyserId(runtimeId);
        if (translated == null) return;
        int javaId = translated.getEntityId();
        var tracker = player.compensatedEntities.getTrackedEntity(javaId);
        if (tracker == null) return;
        int creationTransaction = tracker.getLastTransactionHung();
        Vec3 world = coordinates.toWorld(new Vec3(position.getX(), position.getY(), position.getZ()));
        written.put(runtimeId, new Position(javaId, creationTransaction, world, yaw, pitch));
        float offset = translated.getOffset();
        Vec3 feet = coordinates.toWorld(new Vec3(position.getX(), position.getY() - offset, position.getZ()));
        Vec3 spawnVelocity = motion == null ? null : new Vec3(motion.getX(), motion.getY(), motion.getZ());
        boolean moveLocal = forceLocal;
        boolean complete = forceCompletion;
        pending.add(() -> player.latencyUtils.addRealTimeTask(creationTransaction, () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(javaId);
            if (entity == null || !spawn && entity.bedrockRuntimeId != runtimeId) return;
            if (spawn) entity.bedrockRuntimeId = runtimeId;
            if (spawnVelocity != null) entity.deltaMovement = spawnVelocity;
            if (entity == BedrockVehicleControl.controlledVehicle(player)
                    && !spawn && !moveLocal) return;
            entity.onGround = ground;
            if (teleport || moveLocal || !entity.isLivingEntity() && !entity.isBoat()) {
                entity.bedrockInterpolation = null;
                entity.oldPacketLocation = null;
                entity.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player, feet.x, feet.y, feet.z, entity),
                        yaw - (entity.isBoat() ? 90.0F : 0.0F), pitch);
                if (moveLocal && entity.bedrockPrediction != null) {
                    ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState.rebase(
                            entity, player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame());
                }
            } else {
                var target = new BedrockEntityInterpolation.Target(world, yaw, pitch, offset, complete);
                if (entity.bedrockInterpolation == null) entity.bedrockInterpolation =
                        new BedrockEntityInterpolation(target);
                else entity.bedrockInterpolation.update(target);
            }
        }));
        if (pending.size() > 8192) throw new IllegalStateException("Entity updates lack a latency boundary");
    }

    void boundary(CultPlayer player, CultPlayer.BedrockTransaction transaction) {
        if (pending.isEmpty() || transaction == null) return;
        var batch = java.util.List.copyOf(pending);
        pending.clear();
        player.addBedrockTransactionTask(transaction, () -> batch.forEach(Runnable::run));
    }

    void clear() { written.clear(); pending.clear(); }

    private static float byteAngle(float angle) {
        return (byte) (angle / (360.0F / 256.0F)) / 256.0F * 360.0F;
    }

    private record Position(int javaId, int creationTransaction, Vec3 world, float yaw, float pitch) { }
}
