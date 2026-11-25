package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import java.util.ArrayList;
import java.util.List;

/**
 * A fishing hook, tracked only as far as FishingHook#pullEntity reads it: the pull adds (owner - hook) * 0.1 from the
 * client's own hook position. Once that hook has followed its hooked entity, the position is exact. Before then, and
 * whenever the hook may be BOBBING (which never follows), the client moved the hook itself, so the pull reads a bounded
 * region around where the server last put it instead of a replay of that flight.
 */
public class PacketEntityHook extends PacketEntity {
    // FishingHook's 0.03 gravity under its 0.92 inertia never falls faster than this.
    private static final double TERMINAL_FALL = 0.03 * 0.92 / (1 - 0.92);
    // ProjectileUtil#getHitResultOnMoveVector widens a hook's target by at most 0.3.
    private static final double HIT_MARGIN = 0.3;
    // A hook that may be anywhere in more blocks than this may be in water; they are not scanned.
    private static final int MAX_WATER_SCAN = 4096;

    public int owner;
    public int attached = -1;
    private final PacketEntity clientOwner;
    private PacketEntity hookedIn;
    private boolean discarded;
    // HOOKED_IN_ENTITY: the first tick that sees hookedIn enters it without moving the hook.
    private boolean hookedState;
    // Where the last HOOKED_IN_ENTITY tick put the hook, or null before one did.
    private List<Vec3> followPositions;
    // Offsets around a followed self position that a block-entity pass after travel left unproven.
    private SimpleCollisionBox followRange;
    // Offsets a block entity may have shoved the followed hook by before its next tick.
    private SimpleCollisionBox pushOffsets;
    private PacketEntity followTarget;
    private boolean followsBeforeTarget;
    // A FLYING hook enters BOBBING in water, and a BOBBING hook never follows a hooked entity.
    private boolean mayBeBobbing;
    // DATA_BITING drags a BOBBING hook further down.
    private boolean bit;
    // The fastest velocity the server has given the hook.
    private double fastest;

    /** Where FishingHook#pullEntity may read the hook: each position, and the offsets around it (null when exact). */
    public record PullSource(List<Vec3> positions, SimpleCollisionBox range) {}

    public PacketEntityHook(CultPlayer player, int entityId, int type, double x, double y, double z, int owner) {
        super(player, entityId, type, x, y, z);
        this.owner = owner;
        clientOwner = player.compensatedEntities.getEntity(owner);
        // FishingHook#recreateFromPacket discards a hook whose owner is not a client-visible player.
        discarded = clientOwner == null || clientOwner.type != EntityTypeIds.PLAYER;
    }

    public void setAttached(CultPlayer player, int id) {
        attached = id;
        // onSyncedDataUpdated resolves the reference once, not when the missing entity later spawns.
        hookedIn = player.compensatedEntities.getEntity(id);
    }

    /** onSyncedDataUpdated(DATA_BITING): the server sets it only from FishingHook#catchingFish, while BOBBING. */
    public void setBiting(boolean biting) {
        if (!biting) return;
        mayBeBobbing = true;
        bit = true;
    }

    public PacketEntity hookedIn() {
        return discarded ? null : hookedIn;
    }

    public PacketEntity clientOwner(CultPlayer player) {
        return discarded || player.compensatedEntities.getEntity(owner) != clientOwner ? null : clientOwner;
    }

    @Override
    protected boolean ignoresLerp(CultPlayer player) {
        return !player.isBedrockMovement() && player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5);
    }

    @Override
    protected boolean ticksInterpolation(CultPlayer player) {
        return player.isBedrockMovement() || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6);
    }

    @Override
    public void onMovement(CultPlayer player) {
        super.onMovement(player);
        if (player.isBedrockMovement() || discarded) return;
        if (clientOwner(player) == null) {
            discarded = true;
            return;
        }
        fastest = Math.max(fastest, deltaMovement.length());
        if (!hookedState) {
            // FLYING enters BOBBING in water, which may lie anywhere the client's flight reached.
            if (!mayBeBobbing) mayBeBobbing = mayReachWater(player);
            if (hookedIn == null) return;
            // FLYING enters HOOKED_IN_ENTITY without moving. BOBBING never does; pullSource keeps both.
            deltaMovement = Vec3.ZERO;
            hookedState = true;
            return;
        }
        if (hookedIn == null) return;
        if (player.compensatedEntities.getEntity(hookedIn.getEntityId()) != hookedIn
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_9)
                        && (hookedIn.isDead || !hookedIn.actionAlive
                                || hookedIn == player.compensatedEntities.getSelf()
                                        && player.gamemode == GameMode.SPECTATOR)) {
            // FishingHook#tick drops the entity and flies again from where it followed it.
            hookedIn = null;
            hookedState = false;
            followPositions = null;
            return;
        }
        followTarget = hookedIn;
        followsBeforeTarget = player.compensatedEntities.clientTickOrder(this)
                < player.compensatedEntities.clientTickOrder(hookedIn);
        // The local player's idle prediction has not run yet. Resolve its side after that prediction.
        player.compensatedEntities.fishingPulls.followed(this);
    }

    /** HOOKED_IN_ENTITY puts the hook at its entity's X, Y(0.8) and Z, as that entity stands at the hook's tick. */
    public void finishClientTick(CultPlayer player) {
        if (followTarget == null) return;
        var entities = player.compensatedEntities;
        var prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        var context = prediction == null ? null : prediction.getSimulationContext();
        followRange = null;
        pushOffsets = null;
        if (followTarget == entities.getSelf()) {
            Vec3 position = new Vec3(player.x, player.y, player.z);
            float height = player.getBukkitHeight();
            if (context != null && context.getVehicle() == null) {
                if (followsBeforeTarget) {
                    position = context.getStart();
                    height = poseHeight(context.getPose(), context.getScale());
                } else {
                    var pass = JavaMovementEngine.passAfterTravel(prediction);
                    position = pass == null ? context.getEnd() : pass.travel().position();
                    followRange = pass == null ? null : travelEndRange(prediction, position);
                }
            }
            followPositions = List.of(position.add(0, (double) height * 0.8, 0));
        } else if (context != null && context.getVehicle() == followTarget) {
            var pass = JavaMovementEngine.passAfterTravel(prediction);
            Vec3 position = followsBeforeTarget ? context.getStart()
                    : pass == null ? context.getEnd() : pass.travel().position();
            followPositions = List.of(position.add(0, (double) BoundingBoxSize.getHeight(player, followTarget) * 0.8, 0));
        } else {
            long previous = entities.passTickedThroughOrder();
            entities.beginMovementPositionReads();
            entities.setPassTickedThroughOrder(entities.clientTickOrder(this) - 1);
            try {
                var positions = new ArrayList<Vec3>();
                double height = (double) BoundingBoxSize.getHeight(player, followTarget) * 0.8;
                for (var box : followTarget.getPossibleMovementCollisionBoxCandidates()) {
                    Vec3 position = feet(box).add(0, height, 0);
                    if (!positions.contains(position)) positions.add(position);
                }
                followPositions = positions;
            } finally {
                entities.setPassTickedThroughOrder(previous);
                entities.endMovementPositionReads();
            }
        }
        followTarget = null;
    }

    public PullSource pullSource(CultPlayer player) {
        if (followPositions != null && !mayBeBobbing)
            return new PullSource(followPositions, pushOffsets == null ? followRange : sum(followRange, pushOffsets));
        SimpleCollisionBox region = followPositions == null ? flightRegion(player) : followedHull();
        if (mayBeBobbing) region.union(bobRegion(player));
        Vec3 centre = new Vec3((region.minX + region.maxX) / 2, (region.minY + region.maxY) / 2,
                (region.minZ + region.maxZ) / 2);
        return new PullSource(List.of(centre), region.offset(-centre.x, -centre.y, -centre.z));
    }

    /**
     * Where block entities may shove a followed hook from before its next tick, or null when the pull does not read
     * the followed positions exactly.
     */
    public SimpleCollisionBox followedPositions() {
        if (followPositions == null || mayBeBobbing) return null;
        return pushOffsets == null ? followedHull() : sum(followedHull(), pushOffsets);
    }

    /** A block entity may have shoved the followed hook by {@code movement}, or missed it. */
    public void addPush(Vec3 movement) {
        SimpleCollisionBox push = pushOffsets == null ? new SimpleCollisionBox(0, 0, 0, 0, 0, 0) : pushOffsets;
        pushOffsets = push.copy().union(push.copy().offset(movement));
    }

    /** The farthest the hook flies in a tick: its fastest velocity, plus all that gravity adds under its inertia. */
    private double travel() {
        return fastest + TERMINAL_FALL;
    }

    /**
     * Every position a FLYING hook may hold: around where the server last put it, and once the server hooked an entity,
     * anywhere FishingHook#checkCollision may have met that entity, since the hook still flies the whole tick.
     */
    private SimpleCollisionBox flightRegion(CultPlayer player) {
        Vec3 at = desyncClientPos;
        var region = new SimpleCollisionBox(at.x, at.y, at.z, at.x, at.y, at.z);
        if (hookedIn != null) region.union(targetBox(player).expand(HIT_MARGIN));
        return region.expand(travel());
    }

    /** The hooked entity's boxes; the local player's from where its last tick started to where it ended. */
    private SimpleCollisionBox targetBox(CultPlayer player) {
        if (hookedIn != player.compensatedEntities.getSelf()) return hookedIn.getPossibleMovementCollisionBoxes();
        return player.boundingBox.copy().expandToCoordinate(
                player.lastX - player.x, player.lastY - player.y, player.lastZ - player.z);
    }

    /**
     * Every position a BOBBING hook may hold. FishingHook#tick pulls its Y toward the water surface by Entity.random,
     * whose seed the client never sends, so it settles about -0.29..+0.21 around the top; a bite drags it lower.
     */
    private SimpleCollisionBox bobRegion(CultPlayer player) {
        Vec3 at = desyncClientPos;
        double top = waterTop(player, at);
        return new SimpleCollisionBox(at.x, Math.min(at.y, top) - (bit ? 3.0 : 0.35), at.z,
                        at.x, Math.max(at.y, top) + 0.25, at.z)
                .expand(travel(), 0, travel());
    }

    private SimpleCollisionBox followedHull() {
        SimpleCollisionBox hull = null;
        for (Vec3 position : followPositions) {
            var point = new SimpleCollisionBox(position.x, position.y, position.z, position.x, position.y, position.z);
            hull = hull == null ? point : hull.union(point);
        }
        return followRange == null ? hull : sum(hull, followRange);
    }

    /** {@code box} moved by every offset in {@code offsets}. */
    private static SimpleCollisionBox sum(SimpleCollisionBox box, SimpleCollisionBox offsets) {
        if (box == null) return offsets.copy();
        return new SimpleCollisionBox(box.minX + offsets.minX, box.minY + offsets.minY, box.minZ + offsets.minZ,
                box.maxX + offsets.maxX, box.maxY + offsets.maxY, box.maxZ + offsets.maxZ);
    }

    /** Whether the FLYING hook may have reached water: around the server's position, or past its hooked entity. */
    private boolean mayReachWater(CultPlayer player) {
        Vec3 at = desyncClientPos;
        var around = new SimpleCollisionBox(at.x, at.y, at.z, at.x, at.y, at.z).expand(travel());
        if (holdsWater(player, around)) return true;
        return hookedIn != null && holdsWater(player, targetBox(player).expand(HIT_MARGIN + travel()));
    }

    /** Whether a hook position in {@code region} may lie in water: FishingHook bobs where the fluid has height. */
    private static boolean holdsWater(CultPlayer player, SimpleCollisionBox region) {
        int minX = (int) Math.floor(region.minX), maxX = (int) Math.floor(region.maxX);
        int minY = (int) Math.floor(region.minY), maxY = (int) Math.floor(region.maxY);
        int minZ = (int) Math.floor(region.minZ), maxZ = (int) Math.floor(region.maxZ);
        if ((double) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > MAX_WATER_SCAN) return true;
        var world = player.compensatedWorld;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var fluid = ClientFluidQueries.fluidAt(world, pos);
                    if (!fluid.isEmpty() && ClientFluidQueries.is(player, fluid, FluidTags.WATER)
                            && ClientFluidQueries.height(world, pos, fluid) > 0.0F) return true;
                }
            }
        }
        return false;
    }

    /**
     * Where the other matching candidates of a ranged travel ended before the block-entity pass: the pass hid which
     * one the client ran, and FishingHook#tick read that travel's end.
     */
    private static SimpleCollisionBox travelEndRange(PredictionResult prediction, Vec3 position) {
        var origin = prediction.getInitialStartingVel() == null ? null : prediction.getInitialStartingVel().getRangeOrigin();
        if (origin == null || prediction.getRealities() == null) return null;
        SimpleCollisionBox range = null;
        for (var other : prediction.getRealities()) {
            if (other.getFlagSeverity() != 0.0 || other.getInitialStartingVel().getRangeOrigin() != origin) continue;
            var pass = JavaMovementEngine.passAfterTravel(other);
            if (pass == null) continue;
            Vec3 end = pass.travel().position().subtract(position);
            var box = new SimpleCollisionBox(end.x, end.y, end.z, end.x, end.y, end.z);
            range = range == null ? box.union(new SimpleCollisionBox(0, 0, 0, 0, 0, 0)) : range.union(box);
        }
        return range;
    }

    /** The surface the bobbing pulls toward: the top of the water column above the hook, or its air cell's floor. */
    private static double waterTop(CultPlayer player, Vec3 at) {
        BlockPos cell = BlockPos.containing(at.x, at.y, at.z);
        var fluid = ClientFluidQueries.fluidAt(player.compensatedWorld, cell);
        if (fluid.isEmpty()) return cell.getY();
        while (true) {
            BlockPos above = cell.above();
            var next = ClientFluidQueries.fluidAt(player.compensatedWorld, above);
            if (next.isEmpty() || above.getY() > cell.getY() + 64) break;
            cell = above;
            fluid = next;
        }
        return cell.getY() + ClientFluidQueries.height(player.compensatedWorld, cell, fluid);
    }

    private static float poseHeight(Pose pose, float scale) {
        return pose == Pose.SLEEPING || pose == Pose.DYING ? 0.2F : pose.height * scale;
    }

    public static Vec3 feet(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5);
    }
}
