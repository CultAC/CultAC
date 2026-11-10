package ac.cult.cultac.bedrock.bridge;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.session.GeyserSession;

/** Reads final entity transforms; the shared entity tracker remains their only consumer. */
final class GeyserEntityPositions {
    // Partial packets refer to the last written position, which may not be acknowledged yet.
    private final Map<Long, Position> written = new HashMap<>();
    // Updates written since the last latency marker; the next marker proves them.
    private ArrayList<Runnable> pending = new ArrayList<>();
    // Actors whose last written flags carry COLLIDABLE: only their flag updates can clear it.
    private final LongOpenHashSet collidable = new LongOpenHashSet();

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
        Size spawnSize = null;
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
        if (packet instanceof SetEntityDataPacket data) {
            Size size = Size.read(data.getMetadata());
            Position previous = written.get(data.getRuntimeEntityId());
            if (size != null
                    && previous != null
                    && (writesCollidable(data.getRuntimeEntityId(), size)
                            || size.sized()
                            || isBat(player, previous.javaId()))) {
                long id = data.getRuntimeEntityId();
                pending.add(() -> player.latencyUtils.addRealTimeTask(previous.creationTransaction(), () -> {
                    PacketEntity entity = player.compensatedEntities.getEntity(previous.javaId());
                    if (entity == null || entity.bedrockRuntimeId != id) return;
                    size.apply(entity);
                    if (size.sized()) entity.refreshDimensions(player);
                }));
            }
            return;
        }
        if (packet instanceof RemoveEntityPacket remove) {
            long removedId = remove.getUniqueEntityId();
            collidable.remove(removedId);
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
            spawnSize = Size.read(add.getMetadata());
        } else if (packet instanceof AddPlayerPacket add) {
            runtimeId = add.getRuntimeEntityId();
            position = add.getPosition();
            yaw = add.getRotation().getY();
            pitch = add.getRotation().getX();
            ground = false;
            teleport = true;
            spawn = true;
            motion = add.getMotion();
            spawnSize = Size.read(add.getMetadata());
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
        Size size = spawnSize;
        if (size != null) writesCollidable(runtimeId, size);
        float offset = clientOffset(translated);
        Vec3 feet = coordinates.toWorld(new Vec3(position.getX(), position.getY() - offset, position.getZ()));
        Vec3 spawnVelocity = motion == null ? null : new Vec3(motion.getX(), motion.getY(), motion.getZ());
        boolean moveLocal = forceLocal;
        // The marker written after the move proves the client handled it, and the move takes effect whole there.
        pending.add(() -> player.latencyUtils.addRealTimeTask(creationTransaction, () -> {
            PacketEntity entity = player.compensatedEntities.getEntity(javaId);
            if (entity == null || !spawn && entity.bedrockRuntimeId != runtimeId) return;
            if (spawn) entity.bedrockRuntimeId = runtimeId;
            if (size != null) size.apply(entity);
            if (spawnVelocity != null) entity.deltaMovement = spawnVelocity;
            if (entity == BedrockVehicleControl.controlledVehicle(player) && !spawn && !moveLocal) return;
            boolean ridden = entity == player.compensatedEntities.getSelf().getRiding();
            if (!spawn && (teleport || moveLocal) && ridden) {
                player.checkManager
                        .getListener(ac.cult.cultac.checks.impl.bedrock.BedrockMovement.class)
                        .onVehicleTeleport();
            }
            entity.onGround = ground;
            entity.bedrockPositionOffset = offset;
            boolean direct = placedDirectly(player, entity);
            if (spawn || direct && (teleport || moveLocal)) {
                // A spawn places the actor, and the player's own ride snaps to a teleport for the ride's prediction.
                entity.oldPacketLocation = null;
                entity.setPositionRaw(
                        GetBoundingBox.getPacketEntityBoundingBox(player, feet.x, feet.y, feet.z, entity),
                        yaw - boatYaw(entity),
                        pitch);
            } else {
                entity.handleBedrockMove(player, feet, yaw - boatYaw(entity), pitch, moveLocal, teleport, ground);
            }
            if (moveLocal && entity.bedrockPrediction != null) {
                ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState.rebase(
                        entity,
                        player.getSetbackTeleportUtil().bedrockTeleports().getActiveBedrockCoordinateFrame());
            }
        }));
    }

    private static float boatYaw(PacketEntity entity) {
        return entity.isBoat() ? 90.0F : 0.0F;
    }

    // The player's own ride is placed directly when the move is proven, not interpolated in two halves.
    private static boolean placedDirectly(CultPlayer player, PacketEntity entity) {
        return entity == BedrockVehicleControl.controlledVehicle(player)
                || entity == player.compensatedEntities.getSelf().getRiding();
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    void boundary(CultPlayer player, CultPlayer.BedrockTransaction transaction) {
        if (transaction == null) return;
        if (pending.isEmpty()) return;
        var batch = pending;
        pending = new ArrayList<>();
        player.addBedrockTransactionTask(transaction, () -> batch.forEach(Runnable::run));
    }

    void clear() {
        written.clear();
        pending.clear();
        collidable.clear();
    }

    /** Records the COLLIDABLE flag {@code size} writes for the actor; whether it changes what was last written. */
    private boolean writesCollidable(long runtimeId, Size size) {
        if (size.collidable() == null) return false;
        return size.collidable() ? collidable.add(runtimeId) : collidable.remove(runtimeId);
    }

    private static float byteAngle(float angle) {
        return (byte) (angle / (360.0F / 256.0F)) / 256.0F * 360.0F;
    }

    private record Position(int javaId, int creationTransaction, Vec3 world, float yaw, float pitch) {}

    /** The box size data in an actor's entity data; null when it carries none. */
    private static boolean isBat(CultPlayer player, int javaId) {
        var tracker = player.compensatedEntities.getTrackedEntity(javaId);
        return tracker != null && tracker.getEntityType() == EntityTypeIds.BAT;
    }

    // How far below the packet position Cult places the actor's feet. Geyser 2.12 AvatarEntity#getOffset writes a
    // player sleeping in a bed 0.2 above its feet; a remote player's feet still lie its eye offset below the packet
    // position.
    private static float clientOffset(org.geysermc.geyser.entity.type.Entity translated) {
        if (translated instanceof org.geysermc.geyser.entity.type.player.AvatarEntity
                && translated.getFlag(EntityFlag.SLEEPING)
                && translated.getOffset() == SLEEPING_PLAYER_OFFSET) return PLAYER_OFFSET;
        return translated.getOffset();
    }

    private static final float SLEEPING_PLAYER_OFFSET = 0.2F;
    private static final float PLAYER_OFFSET = 1.62001F;

    // The actor data that shapes the box the client holds for the actor: its size, the RESTING flag a bat hangs by,
    // and the COLLIDABLE flag that makes the box solid for other actors.
    private record Size(Float width, Float height, Float scale, Boolean resting, Boolean collidable) {
        static Size read(EntityDataMap data) {
            if (data == null) return null;
            Float width = data.get(EntityDataTypes.WIDTH);
            Float height = data.get(EntityDataTypes.HEIGHT);
            Float scale = data.get(EntityDataTypes.SCALE);
            boolean flags = data.get(EntityDataTypes.FLAGS) != null;
            Boolean resting = flags ? data.getFlag(EntityFlag.RESTING) : null;
            Boolean collidable = flags ? data.getFlag(EntityFlag.COLLIDABLE) : null;
            return width == null && height == null && scale == null && !flags
                    ? null
                    : new Size(width, height, scale, resting, collidable);
        }

        boolean sized() {
            return width != null || height != null || scale != null;
        }

        void apply(PacketEntity entity) {
            entity.setBedrockSize(width, height, scale);
            if (resting != null) entity.setBedrockResting(resting);
            if (collidable != null) entity.bedrockCollidable = collidable;
        }
    }
}
