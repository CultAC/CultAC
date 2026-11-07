package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.integration.BedrockEntityInterpolation;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.session.GeyserSession;

/** Reads final entity transforms; the shared entity tracker remains their only consumer. */
final class GeyserEntityPositions {
    // Bedrock 1.26.60: only projectiles and physics actors play back.
    private static final int PLAYBACK_LIMITED_PROTOCOL = 2225;
    // Partial packets refer to the last written position, which may not be acknowledged yet.
    private final Map<Long, Position> written = new HashMap<>();
    // Updates written since the last latency marker; the next marker proves them.
    private ArrayList<Runnable> pending = new ArrayList<>();
    private long moves;

    static boolean hasPosition(BedrockPacket packet) {
        return packet instanceof AddEntityPacket
                || packet instanceof AddPlayerPacket
                || packet instanceof MoveEntityAbsolutePacket
                || packet instanceof MoveEntityDeltaPacket
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
                Vec3 velocity = new Vec3(
                        move.getMotion().getX(),
                        move.getMotion().getY(),
                        move.getMotion().getZ());
                pending.add(() -> player.latencyUtils.addRealTimeTask(previous.creationTransaction(), () -> {
                    PacketEntity entity = player.compensatedEntities.getEntity(previous.javaId());
                    if (entity != null
                            && entity.bedrockRuntimeId == id
                            && !player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(entity, velocity))
                        entity.deltaMovement = velocity;
                }));
            }
            return;
        }
        if (packet instanceof RemoveEntityPacket remove) {
            long removedId = remove.getUniqueEntityId();
            Position removed = written.remove(removedId);
            if (removed != null)
                pending.add(() -> player.latencyUtils.addRealTimeTask(removed.creationTransaction(), () -> {
                    PacketEntity entity = player.compensatedEntities.getEntity(removed.javaId());
                    if (entity != null && entity.bedrockRuntimeId == removedId)
                        player.compensatedEntities.removeEntity(removed.javaId());
                }));
            return;
        }
        if (packet instanceof AddEntityPacket add) {
            runtimeId = add.getRuntimeEntityId();
            position = add.getPosition();
            yaw = add.getRotation().getY();
            pitch = add.getRotation().getX();
            ground = false;
            teleport = true;
            spawn = true;
            motion = add.getMotion();
        } else if (packet instanceof AddPlayerPacket add) {
            runtimeId = add.getRuntimeEntityId();
            position = add.getPosition();
            yaw = add.getRotation().getY();
            pitch = add.getRotation().getX();
            ground = false;
            teleport = true;
            spawn = true;
            motion = add.getMotion();
        } else if (packet instanceof MoveEntityAbsolutePacket move) {
            runtimeId = move.getRuntimeEntityId();
            position = move.getPosition();
            yaw = byteAngle(move.getRotation().getY());
            pitch = byteAngle(move.getRotation().getX());
            ground = move.isOnGround();
            teleport = move.isTeleported();
            spawn = false;
            forceLocal = move.isForceMove();
            forceCompletion = move.isForceCompletion();
        } else if (packet instanceof MovePlayerPacket move) {
            runtimeId = move.getRuntimeEntityId();
            position = move.getPosition();
            yaw = move.getRotation().getY();
            pitch = move.getRotation().getX();
            ground = move.isOnGround();
            teleport = move.getMode() != MovePlayerPacket.Mode.NORMAL;
            spawn = false;
        } else if (packet instanceof MoveEntityDeltaPacket move) {
            runtimeId = move.getRuntimeEntityId();
            Position previous = written.get(runtimeId);
            if (previous == null) return;
            var flags = move.getFlags();
            Vec3 local = coordinates.toLocal(previous.world());
            position = Vector3f.from(
                    flags.contains(MoveEntityDeltaPacket.Flag.HAS_X) ? move.getX() : (float) local.x,
                    flags.contains(MoveEntityDeltaPacket.Flag.HAS_Y) ? move.getY() : (float) local.y,
                    flags.contains(MoveEntityDeltaPacket.Flag.HAS_Z) ? move.getZ() : (float) local.z);
            yaw = flags.contains(MoveEntityDeltaPacket.Flag.HAS_YAW) ? byteAngle(move.getYaw()) : previous.yaw();
            pitch = flags.contains(MoveEntityDeltaPacket.Flag.HAS_PITCH)
                    ? byteAngle(move.getPitch())
                    : previous.pitch();
            var semantics = GeyserEntityDeltaFlags.read(move, session.protocolVersion());
            ground = semantics.onGround();
            teleport = semantics.teleported();
            spawn = false;
            forceLocal = semantics.forceLocal();
            forceCompletion = semantics.forceCompletion();
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
        long sequence = ++moves;
        var move = new BedrockEntityInterpolation.Move(
                new BedrockEntityInterpolation.Target(world, yaw, pitch, offset, forceCompletion), teleport);
        boolean playsBack = playsBack(session, translated);
        if (!spawn && !moveLocal) {
            // The client may handle the move once it has answered the marker written before it.
            player.addBedrockTransactionTask(
                    player.getLastClientboundBedrockTransaction(),
                    () -> player.latencyUtils.addRealTimeTask(creationTransaction, () -> {
                        PacketEntity entity = player.compensatedEntities.getEntity(javaId);
                        if (entity == null || entity.bedrockRuntimeId != runtimeId || placedDirectly(player, entity))
                            return;
                        interpolation(entity, playsBack).begin(player, entity, sequence, move);
                    }));
        }
        // The marker written after the move proves the client handled it.
        pending.add(() -> player.latencyUtils.addRealTimeTask(creationTransaction, () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(javaId);
            if (entity == null || !spawn && entity.bedrockRuntimeId != runtimeId) return;
            if (spawn) entity.bedrockRuntimeId = runtimeId;
            if (spawnVelocity != null) entity.deltaMovement = spawnVelocity;
            if (entity == BedrockVehicleControl.controlledVehicle(player) && !spawn && !moveLocal) return;
            boolean ridden = entity == player.compensatedEntities.getSelf().getRiding();
            if (!spawn && (teleport || moveLocal) && ridden) {
                player.checkManager
                        .getListener(ac.cult.cultac.checks.impl.bedrock.BedrockMovement.class)
                        .onVehicleTeleport();
            }
            entity.onGround = ground;
            // A spawn and a forced local move place the actor. The player's own vehicle keeps snapping on a
            // teleport for the ride's prediction; every other actor lerps or plays back as its client does.
            if (spawn || moveLocal || ridden && teleport) {
                entity.bedrockInterpolation = null;
                entity.oldPacketLocation = null;
                entity.setPositionRaw(
                        GetBoundingBox.getPacketEntityBoundingBox(player, feet.x, feet.y, feet.z, entity),
                        yaw - (entity.isBoat() ? 90.0F : 0.0F),
                        pitch);
                if (moveLocal && entity.bedrockPrediction != null) {
                    ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState.rebase(
                            entity, player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame());
                }
            } else {
                interpolation(entity, playsBack).settle(player, entity, sequence, move);
            }
        }));
    }

    /** The interpolation of {@code entity}, from where it is shown now when it has none. */
    private static BedrockEntityInterpolation interpolation(PacketEntity entity, boolean playsBack) {
        if (entity.bedrockInterpolation == null) {
            entity.bedrockInterpolation = new BedrockEntityInterpolation(entity, entity.isAvatar(), playsBack);
        }
        return entity.bedrockInterpolation;
    }

    // The player's own ride is placed directly when the move is proven, not interpolated in two halves.
    private static boolean placedDirectly(CultPlayer player, PacketEntity entity) {
        return entity == BedrockVehicleControl.controlledVehicle(player)
                || entity == player.compensatedEntities.getSelf().getRiding();
    }

    /**
     * Whether the client draws the actor by time-based playback: every mob and other non-player actor up to 26.52
     * unless Mojang's flighting turns it off, and from 26.60 the actors whose predicted-movement gate is set, such as
     * arrows, tridents and falling blocks. Minecarts, end crystals and boats lerp. Other non-living actors are counted
     * as playing back, which can only widen reach.
     */
    private static boolean playsBack(GeyserSession session, org.geysermc.geyser.entity.type.Entity translated) {
        if (translated instanceof org.geysermc.geyser.entity.type.player.AvatarEntity) return false;
        if (session.protocolVersion() < PLAYBACK_LIMITED_PROTOCOL) return true;
        return !(translated instanceof org.geysermc.geyser.entity.type.LivingEntity)
                && !(translated instanceof org.geysermc.geyser.entity.type.BoatEntity)
                && !(translated instanceof org.geysermc.geyser.entity.type.MinecartEntity)
                && !(translated instanceof org.geysermc.geyser.entity.type.EnderCrystalEntity);
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    void boundary(CultPlayer player, CultPlayer.BedrockTransaction transaction) {
        if (pending.isEmpty() || transaction == null) return;
        var batch = pending;
        pending = new ArrayList<>();
        player.addBedrockTransactionTask(transaction, () -> batch.forEach(Runnable::run));
    }

    void clear() {
        written.clear();
        pending.clear();
    }

    private static float byteAngle(float angle) {
        return (byte) (angle / (360.0F / 256.0F)) / 256.0F * 360.0F;
    }

    private record Position(int javaId, int creationTransaction, Vec3 world, float yaw, float pitch) {}
}
