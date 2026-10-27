package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFishingHookFlight;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

public class PacketEntityHook extends PacketEntity {
    public int owner;
    public int attached = -1;
    private final PacketEntity clientOwner;
    private PacketEntity hookedIn;
    private boolean hookedState;
    private boolean discarded;
    private List<Vec3> followPositions;
    private Vec3 codecAtFollow;
    private PacketEntity followTarget;
    private boolean followsBeforeTarget;
    private boolean flyingTick;
    private boolean bobbing;
    // deltaMovement when FLYING became BOBBING, and whether DATA_BITING was set while bobbing.
    private Vec3 bobbingEntryVelocity = Vec3.ZERO;
    private boolean bitBobbing;
    // X/Z offsets block entities may have shoved the BOBBING hook by, as its hidden Y decides whether they reached.
    private SimpleCollisionBox pushOffsets;
    // Offsets around a followed self position that a block-entity pass after travel left unproven.
    private SimpleCollisionBox followRange;
    private boolean leftOwner;
    private int age;
    private int life;
    private Long synchronizedSeed;
    private int synchronizedDraws;
    private final HookPhysicsPaths paths = new HookPhysicsPaths();

    record State(JavaPistonEntityStates.Snapshot physics, PacketEntity hookedIn, boolean hookedState,
            boolean discarded, List<Vec3> positions, Vec3 codec, PacketEntity target, boolean before,
            boolean flyingTick, boolean bobbing, boolean leftOwner, int age, int life, Long seed, int draws) {
        boolean sameSettledState(State other) {
            return physics.oldLocation() == null && other.physics.oldLocation() == null
                    && !physics.location().hasActiveInterpolationTarget()
                    && !other.physics.location().hasActiveInterpolationTarget()
                    && Objects.equals(physics.physicalPosition(), other.physics.physicalPosition())
                    && Objects.equals(physics.velocity(), other.physics.velocity())
                    && physics.ground() == other.physics.ground()
                    && Objects.equals(physics.pass(), other.physics.pass())
                    && hookedIn == other.hookedIn && hookedState == other.hookedState && discarded == other.discarded
                    && Objects.equals(positions, other.positions) && Objects.equals(codec, other.codec)
                    && target == other.target && before == other.before && flyingTick == other.flyingTick
                    && bobbing == other.bobbing && leftOwner == other.leftOwner && age == other.age && life == other.life
                    && Objects.equals(seed, other.seed) && draws == other.draws;
        }
    }

    State captureState() {
        return new State(capturePistonPhysics(true), hookedIn, hookedState, discarded, followPositions, codecAtFollow,
                followTarget, followsBeforeTarget, flyingTick, bobbing, leftOwner, age, life, synchronizedSeed, synchronizedDraws);
    }

    void restoreState(State state) {
        restorePistonPhysics(state.physics());
        hookedIn = state.hookedIn();
        hookedState = state.hookedState();
        discarded = state.discarded();
        followPositions = state.positions();
        codecAtFollow = state.codec();
        followTarget = state.target();
        followsBeforeTarget = state.before();
        flyingTick = state.flyingTick();
        bobbing = state.bobbing();
        leftOwner = state.leftOwner();
        age = state.age();
        life = state.life();
        synchronizedSeed = state.seed();
        synchronizedDraws = state.draws();
    }

    @Override
    public boolean runPhysicsPaths(Runnable operation) {
        return paths != null && paths.run(this, operation);
    }

    public PacketEntityHook(CultPlayer player, int entityId, int type, double x, double y, double z, int owner) {
        super(player, entityId, type, x, y, z);
        this.owner = owner;
        clientOwner = player.compensatedEntities.getEntity(owner);
        // FishingHook#recreateFromPacket discards a hook whose owner is not a client-visible player.
        discarded = clientOwner == null || clientOwner.type != EntityTypeIds.PLAYER;
    }

    public void setAttached(CultPlayer player, int id) {
        if (runPhysicsPaths(() -> setAttached(player, id))) return;
        attached = id;
        // onSyncedDataUpdated resolves the reference once, not when the missing entity later spawns.
        hookedIn = player.compensatedEntities.getEntity(id);
    }

    /** onSyncedDataUpdated(DATA_BITING): its RNG is known only after the hook's first tick seeded it. */
    public void setBiting(boolean biting) {
        if (runPhysicsPaths(() -> setBiting(biting))) return;
        if (biting && bobbing) bitBobbing = true;
        if (!biting || synchronizedSeed == null) return;
        Random random = new Random(synchronizedSeed);
        for (int i = 0; i < synchronizedDraws; i++) random.nextFloat();
        float next = 0.6F + random.nextFloat() * (1.0F - 0.6F);
        synchronizedDraws++;
        deltaMovement = new Vec3(deltaMovement.x, -0.4F * next, deltaMovement.z);
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
        if (runPhysicsPaths(() -> onMovement(player))) return;
        super.onMovement(player);
        if (player.isBedrockMovement() || discarded) return;
        if (clientOwner(player) == null) {
            discarded = true;
            return;
        }
        if (scoreboardName != null) {
            synchronizedSeed = UUID.fromString(scoreboardName).getLeastSignificantBits()
                    ^ player.compensatedWorld.endedTickGameTime();
            synchronizedDraws = 0;
        }
        if (clientPhysicalPosition.y < player.compensatedWorld.getMinHeight() - 64) {
            discarded = true;
            return;
        }
        age++;
        life = onGround ? life + 1 : 0;
        if (life >= 1200) {
            discarded = true;
            return;
        }
        if (!hookedState) {
            // BOBBING never enters the hooked state merely because the attachment metadata changes. Its
            // Entity.random draws are absent from the protocol, so pulls use pullRange around this position.
            if (bobbing) return;
            if (hookedIn != null) {
                deltaMovement = Vec3.ZERO;
                hookedState = true;
                followPositions = null;
                return;
            }
            flyingTick = true;
            player.compensatedEntities.fishingPulls.followed(this);
            return;
        }
        if (hookedIn == null) return;
        if (player.compensatedEntities.getEntity(hookedIn.getEntityId()) != hookedIn
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_9)
                        && (hookedIn.isDead || !hookedIn.actionAlive
                                || hookedIn == player.compensatedEntities.getSelf()
                                        && player.gamemode == GameMode.SPECTATOR)) {
            hookedIn = null;
            hookedState = false;
            return;
        }
        followTarget = hookedIn;
        followsBeforeTarget = player.compensatedEntities.clientTickOrder(this)
                < player.compensatedEntities.clientTickOrder(hookedIn);
        codecAtFollow = desyncClientPos;
        // The local player's idle prediction has not run yet. Resolve its side after that prediction.
        player.compensatedEntities.fishingPulls.followed(this);
    }

    public void finishClientTick(CultPlayer player) {
        if (runPhysicsPaths(() -> finishClientTick(player))) return;
        if (flyingTick) {
            flyingTick = false;
            var entities = player.compensatedEntities;
            long previous = entities.passTickedThroughOrder();
            entities.beginMovementPositionReads();
            entities.setPassTickedThroughOrder(entities.clientTickOrder(this) - 1);
            try {
                var results = JavaFishingHookFlight.tick(player, this, blockEntityState, leftOwner, age);
                State before = captureState();
                applyFlight(player, results.getFirst());
                State primary = captureState();
                for (int i = 1; i < results.size(); i++) {
                    restoreState(before);
                    applyFlight(player, results.get(i));
                    paths.branch(captureState());
                }
                restoreState(primary);
            } finally {
                entities.setPassTickedThroughOrder(previous);
                entities.endMovementPositionReads();
            }
            return;
        }
        if (followTarget == null) return;
        var entities = player.compensatedEntities;
        if (entities.isUntrackedRider(followTarget)) {
            followTarget = null;
            return;
        }
        var prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        var context = prediction == null ? null : prediction.getSimulationContext();
        followRange = null;
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
                followPositions = new ArrayList<>();
                var boxes = entities.isUntrackedRider(followTarget)
                        ? List.of(entities.currentMovementBox(followTarget))
                        : followTarget.getPossibleMovementCollisionBoxCandidates();
                double height = (double) BoundingBoxSize.getHeight(player, followTarget) * 0.8;
                for (var box : boxes) {
                    Vec3 position = feet(box).add(0, height, 0);
                    if (!followPositions.contains(position)) followPositions.add(position);
                }
            } finally {
                entities.setPassTickedThroughOrder(previous);
                entities.endMovementPositionReads();
            }
        }
        State before = captureState();
        List<Vec3> positions = followPositions;
        for (int i = 0; i < positions.size(); i++) {
            restoreState(before);
            Vec3 position = positions.get(i);
            applyClientPosition(player, GetBoundingBox.getPacketEntityBoundingBox(
                    player, position.x, position.y, position.z, this), null);
            followPositions = List.of(position);
            followTarget = null;
            if (i > 0) paths.branch(captureState());
        }
        if (positions.size() > 1) {
            restoreState(before);
            Vec3 position = positions.getFirst();
            applyClientPosition(player, GetBoundingBox.getPacketEntityBoundingBox(
                    player, position.x, position.y, position.z, this), null);
            followPositions = List.of(position);
        }
        followTarget = null;
    }

    private void applyFlight(CultPlayer player, JavaFishingHookFlight.Result result) {
        Vec3 position = result.state().position();
        applyClientPosition(player, GetBoundingBox.getPacketEntityBoundingBox(
                player, position.x, position.y, position.z, this), result.recordedMovement());
        blockEntityState = result.state();
        deltaMovement = result.state().velocity();
        onGround = result.state().onGround();
        leftOwner = result.leftOwner();
        if (result.bobbing() && !bobbing) {
            bobbingEntryVelocity = deltaMovement;
            bitBobbing = false;
            pushOffsets = null;
        }
        bobbing = result.bobbing();
        followPositions = List.of(position);
        codecAtFollow = desyncClientPos;
    }

    public List<Vec3> pullPositions(CultPlayer player) {
        return paths.values(this, () -> currentPullPositions(player));
    }

    /** Whether this hook is BOBBING, so its Y is hidden (see {@link #pullRange}). */
    public boolean isBobbing() {
        return bobbing && !hookedState && !discarded;
    }

    /**
     * The offsets around each pull position that a BOBBING hook may hold. FishingHook#tick pulls Y toward the
     * feet cell's water surface by Entity.random, whose seed the client never sends, so the hook rises through the
     * water column and settles about -0.29..+0.21 around its top; a bite (DATA_BITING) drags it down further. X/Z
     * only decay from the entry velocity (0.9 then 0.92 per tick), plus any shove a block entity may have given it.
     * This is a bounded approximation.
     */
    public SimpleCollisionBox pullRange(CultPlayer player) {
        if (hookedState && !discarded) return followRange;
        if (!isBobbing()) return null;
        Vec3 at = clientPhysicalPosition;
        double toSurface = waterTop(player, at) - at.y;
        double drift = 0.9 / (1 - 0.9 * 0.92);
        double dx = bobbingEntryVelocity.x * drift, dz = bobbingEntryVelocity.z * drift;
        SimpleCollisionBox push = pushOffsets == null ? new SimpleCollisionBox(0, 0, 0, 0, 0, 0) : pushOffsets;
        return new SimpleCollisionBox(
                Math.min(0, dx) - 0.02 + push.minX,
                Math.min(0, toSurface) - (bitBobbing ? 3.0 : 0.35),
                Math.min(0, dz) - 0.02 + push.minZ,
                Math.max(0, dx) + 0.02 + push.maxX,
                Math.max(0, toSurface) + 0.25,
                Math.max(0, dz) + 0.02 + push.maxZ);
    }

    /**
     * Where the other matching candidates of a ranged travel ended before the block-entity pass: the pass hid which
     * one the client ran, and FishingHook#tick read that travel's end.
     */
    private static SimpleCollisionBox travelEndRange(
            ac.cult.cultac.checks.impl.prediction.PredictionResult prediction, Vec3 position) {
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

    /** Every box the BOBBING hook may occupy, for whether a block entity can reach it. */
    public SimpleCollisionBox bobbingColumn(CultPlayer player) {
        SimpleCollisionBox range = pullRange(player);
        SimpleCollisionBox box = GetBoundingBox.getPacketEntityBoundingBox(
                player, clientPhysicalPosition.x, clientPhysicalPosition.y, clientPhysicalPosition.z, this);
        return new SimpleCollisionBox(box.minX + range.minX, box.minY + range.minY, box.minZ + range.minZ,
                box.maxX + range.maxX, box.maxY + range.maxY, box.maxZ + range.maxZ);
    }

    /** Runs {@code replay} and restores everything it changed on this hook. */
    public <T> T withoutCommitting(java.util.function.Supplier<T> replay) {
        State before = captureState();
        try {
            return replay.get();
        } finally {
            restoreState(before);
        }
    }

    /** A block entity may have shoved the hook by {@code movement}, or missed it. */
    public void addPush(Vec3 movement) {
        SimpleCollisionBox push = pushOffsets == null ? new SimpleCollisionBox(0, 0, 0, 0, 0, 0) : pushOffsets;
        pushOffsets = new SimpleCollisionBox(
                Math.min(push.minX, push.minX + movement.x), 0, Math.min(push.minZ, push.minZ + movement.z),
                Math.max(push.maxX, push.maxX + movement.x), 0, Math.max(push.maxZ, push.maxZ + movement.z));
    }

    private List<Vec3> currentPullPositions(CultPlayer player) {
        if (clientPhysicalPositionExact) return List.of(clientPhysicalPosition);
        var positions = new ArrayList<Vec3>();
        for (var box : super.getPossibleMovementCollisionBoxCandidates()) {
            Vec3 position = feet(box);
            if (!positions.contains(position)) positions.add(position);
        }
        return positions;
    }

    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        if (paths == null) return super.getPossibleMovementCollisionBoxCandidates();
        return paths.values(this, super::getPossibleMovementCollisionBoxCandidates);
    }

    @Override
    public SimpleCollisionBox getPossibleMovementCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        List<SimpleCollisionBox> boxes = getPossibleMovementCollisionBoxCandidates();
        out.set(boxes.getFirst());
        for (int i = 1; i < boxes.size(); i++) out.union(boxes.get(i));
        return out;
    }

    private static float poseHeight(Pose pose, float scale) {
        return pose == Pose.SLEEPING || pose == Pose.DYING ? 0.2F : pose.height * scale;
    }

    public static Vec3 feet(SimpleCollisionBox box) {
        return new Vec3((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5);
    }
}
