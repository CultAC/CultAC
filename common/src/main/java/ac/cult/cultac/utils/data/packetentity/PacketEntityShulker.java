package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.ShulkerGeometry;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass.ShulkerPeek;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A Shulker as a Java client ticks it. Shulker#makeBoundingBox opens the box from the attach face by the physical peek
 * of currentPeekAmount, which Shulker#tick steps 0.05 towards DATA_PEEK_ID each client tick; a growing peek moves every
 * entity in the newly opened part with MoverType.SHULKER (Shulker#onPeekAmountChange). Shulker#setPos puts a root on
 * its block's bottom centre. A shulker riding outside the local player's riding trees ticks its peek alone, at the seat
 * its vehicle chain gives it, since nothing positions it. Bedrock clients keep the plain entity box.
 */
public class PacketEntityShulker extends PacketEntity {
    /** Shulker#getAttachFace (DATA_ATTACH_FACE_ID). */
    public Direction facing = Direction.DOWN;

    private final CultPlayer player;
    // DATA_PEEK_ID as the client holds it; Shulker#getRawPeekAmount widens the signed byte.
    private byte rawPeek;
    // Shulker#currentPeekAmount and currentPeekAmountO as the last tick this model ran left them.
    private float peek;
    private float peekO;
    // Entity#tickCount != 0, which lets Shulker#setPos clear DATA_PEEK_ID on a block change.
    private boolean ticked;
    // PacketStateData#acceptedClientTick of the last tick this model ran.
    private int tickedAt = Integer.MIN_VALUE;
    // The block the last tick saw the shulker in, and whether DATA_PEEK_ID was handled since.
    private long block;
    private boolean peekDataSinceBlock;

    public PacketEntityShulker(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
        this.player = player;
        this.block = blockOf(clientPosition());
    }

    /** Shulker#canBeCollidedWith: Entity#isAlive. */
    public boolean collidable() {
        return actionAlive && !isDead;
    }

    /** DATA_ATTACH_FACE_ID and DATA_PEEK_ID of one handled entity data packet; either may be absent. */
    public void onShapeData(@Nullable Direction attachFace, @Nullable Byte peekData) {
        // Shulker#onSyncedDataUpdated rebuilds the box on DATA_ATTACH_FACE_ID; the peek only moves in Shulker#tick.
        if (attachFace != null) facing = attachFace;
        if (peekData != null) {
            rawPeek = peekData;
            peekDataSinceBlock = true;
        }
    }

    @Override
    public void mount(PacketEntity vehicle) {
        super.mount(vehicle);
        // Shulker#startRiding attaches to the floor on the client as well.
        facing = Direction.DOWN;
    }

    @Override
    public void onMovement(CultPlayer player) {
        super.onMovement(player);
        if (modelled()) tickPeek();
    }

    /** Shulker#tick's updatePeekAmount, after any Shulker#setPos block change since the last tick. */
    private void tickPeek() {
        rawPeek = rawPeekForTick();
        if (oldPacketLocation == null) {
            block = blockOf(clientPosition());
            peekDataSinceBlock = false;
        }
        stepPeek();
    }

    /**
     * Shulker#tick through Entity#rideTick for a shulker riding outside the local player's riding trees, which no
     * entity pass ticks or positions: updatePeekAmount at the seat the last Entity#positionRider gave it. A closed
     * shulker with nothing to open towards needs no seat, so it allocates nothing.
     */
    public void tickAsUntrackedRider() {
        if (!modelled()) return;
        Vec3 position = closedRider() ? null : riderTickPosition();
        if (!ridesInClientTick(position)) return;
        rawPeek = rawPeekForRiderTick(position);
        if (position != null) {
            block = blockOf(position);
            peekDataSinceBlock = false;
        }
        stepPeek();
    }

    private void stepPeek() {
        peekO = peek;
        peek = step(peek, rawPeek);
        ticked = true;
        tickedAt = player.packetStateData.acceptedClientTick;
    }

    /**
     * DATA_PEEK_ID as this tick's updatePeekAmount reads it: Shulker#setPos sets it to 0 when a ticked shulker
     * changes block, unless the server's own value was handled after that move. A move the client may not have
     * handled yet leaves it.
     */
    private byte rawPeekForTick() {
        if (!ticked || peekDataSinceBlock || oldPacketLocation != null) return rawPeek;
        return blockOf(clientPosition()) == block ? rawPeek : 0;
    }

    /** {@link #rawPeekForTick()} for an untracked rider at {@code position}, null when its seat is not one position. */
    private byte rawPeekForRiderTick(@Nullable Vec3 position) {
        if (!ticked || peekDataSinceBlock || position == null) return rawPeek;
        return blockOf(position) == block ? rawPeek : 0;
    }

    private boolean untrackedRider() {
        return riding != null && player.compensatedEntities.isUntrackedRider(this);
    }

    // A closed peek stays closed and needs no position: neither its box nor DATA_PEEK_ID's block reset can change.
    private boolean closedRider() {
        return rawPeek == 0 && peek == 0.0F && peekO == 0.0F;
    }

    /**
     * Where this untracked rider's own tick finds it: the seat the last Entity#positionRider gave it, on the place its
     * root held before the client tick in progress stepped it. Null when that is not one position. Allocates.
     */
    private @Nullable Vec3 riderTickPosition() {
        Vec3 position = null;
        for (SimpleCollisionBox seat : player.compensatedEntities.untrackedRiderSeatsBeforeTick(this)) {
            Vec3 candidate = clientPosition(seat);
            if (position == null) position = candidate;
            else if (!position.equals(candidate)) return null;
        }
        return position;
    }

    /**
     * Whether ClientLevel#tickPassenger runs this untracked rider's Entity#rideTick in the client tick in progress: its
     * root ticks, and from 1.17 the rider's own chunk at {@code position} ticks entities. A seat that is not one
     * position is taken to tick with its root.
     */
    private boolean ridesInClientTick(@Nullable Vec3 position) {
        CompensatedEntities entities = player.compensatedEntities;
        PacketEntity root = entities.rootOf(this);
        if (root == null || !entities.ticksInClientTick(root)) return false;
        return position == null
                || player.getClientVersion().isOlderThan(ac.cult.cultac.network.protocol.ClientVersion.V_1_17)
                || player.compensatedWorld.isClientEntityTickingChunk(
                        CultMath.floor(position.x) >> 4, CultMath.floor(position.z) >> 4);
    }

    /** Shulker#updatePeekAmount's step of {@code peek} towards DATA_PEEK_ID {@code raw}. */
    private static float step(float peek, byte raw) {
        float target = raw * 0.01F;
        if (peek == target) return peek;
        return peek > target ? CultMath.clamp(peek - 0.05F, target, 1.0F) : CultMath.clamp(peek + 0.05F, 0.0F, target);
    }

    private boolean tickedThisClientTick() {
        return tickedAt == player.packetStateData.acceptedClientTick;
    }

    /**
     * currentPeekAmount where the client tick in progress reads it: after this shulker's tick when
     * {@link CompensatedEntities#tickedThroughOrder()} reaches its ClientLevel#tickEntities place, else before it.
     */
    private float peekRead() {
        CompensatedEntities entities = player.compensatedEntities;
        boolean stepped = entities.clientTickOrder(this) <= entities.tickedThroughOrder();
        if (tickedThisClientTick()) return stepped ? peek : peekO;
        if (!stepped) return peek;
        if (!untrackedRider()) return entities.ticksInClientTick(this) ? step(peek, rawPeekForTick()) : peek;
        if (closedRider()) return peek;
        Vec3 position = riderTickPosition();
        return ridesInClientTick(position) ? step(peek, rawPeekForRiderTick(position)) : peek;
    }

    /**
     * Shulker#onPeekAmountChange in the client tick in progress: the part the peek opened and the move it gives every
     * entity whose box meets it, or null when the peek does not grow there or the client's position is not one
     * position.
     */
    public @Nullable ShulkerPeek peekMove() {
        if (!modelled()) return null;
        boolean rider = untrackedRider();
        // An untracked rider opens from where its rideTick begins, before positionRider reseats it.
        if (rider && closedRider()) return null;
        Vec3 riderPosition = rider ? riderTickPosition() : null;
        float from;
        float to;
        if (tickedThisClientTick()) {
            from = peekO;
            to = peek;
        } else if (rider) {
            if (!ridesInClientTick(riderPosition)) return null;
            from = peek;
            to = step(peek, rawPeekForRiderTick(riderPosition));
        } else {
            if (!player.compensatedEntities.ticksInClientTick(this)) return null;
            from = peek;
            to = step(peek, rawPeekForTick());
        }
        float physicalTo = physicalPeek(to);
        float physicalFrom = physicalPeek(from);
        float size = size();
        float push = (physicalTo - physicalFrom) * size;
        if (push <= 0.0F) return null;
        Vec3 position = rider ? riderPosition : singleClientPosition();
        if (position == null) return null;
        Direction direction = facing.getOpposite();
        Box area = ShulkerGeometry.progressDeltaBounds(
                size, blockSim(direction), physicalFrom, physicalTo, blockSim(position));
        // Before 1.21.4 onPeekAmountChange translates origin-based bounds by x/z - .5,
        // even though makeBoundingBox already centers the physical shell by its scaled width.
        double shift = player.getClientVersion().isOlderThan(ac.cult.cultac.network.protocol.ClientVersion.V_1_21_4)
                ? (size - 1.0D) * 0.5D
                : 0.0D;
        return new ShulkerPeek(
                new SimpleCollisionBox(
                        area.minX() + shift,
                        area.minY(),
                        area.minZ() + shift,
                        area.maxX() + shift,
                        area.maxY(),
                        area.maxZ() + shift),
                new Vec3(push * direction.getStepX(), push * direction.getStepY(), push * direction.getStepZ()),
                player.compensatedEntities.clientTickOrder(this));
    }

    @Override
    public SimpleCollisionBox possibleMovementCollisionBoxCandidate(int index, SimpleCollisionBox out) {
        super.possibleMovementCollisionBoxCandidate(index, out);
        return modelled() ? shape(out, peekRead()) : out;
    }

    /** LivingEntity#pushEntities runs in super.tick, before Shulker#updatePeekAmount opens the shell. */
    public List<Vec3> livingPushImpulses(SimpleCollisionBox playerBox, Vec3 playerPosition) {
        var impulses = new ArrayList<Vec3>();
        float beforePeek = tickedThisClientTick() ? peekO : peek;
        for (int i = 0, count = possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
            var raw = super.possibleMovementCollisionBoxCandidate(i, new SimpleCollisionBox());
            Vec3 position = clientPosition(raw);
            var box = shape(raw, beforePeek);
            Vec3 impulse = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes.intersects(
                            box, playerBox)
                    ? ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes.impulse(
                            position, playerPosition)
                    : Vec3.ZERO;
            if (!impulses.contains(impulse)) impulses.add(impulse);
        }
        return impulses;
    }

    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        if (!modelled()) return super.getPossibleMovementCollisionBoxCandidates();
        float peek = peekRead();
        int count = possibleMovementCollisionBoxCandidateCount();
        List<SimpleCollisionBox> boxes = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            boxes.add(shape(super.possibleMovementCollisionBoxCandidate(i, new SimpleCollisionBox()), peek));
        return boxes;
    }

    @Override
    public SimpleCollisionBox getPossibleMovementCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        if (!modelled()) return super.getPossibleMovementCollisionBoxes(out, scratch);
        float peek = peekRead();
        for (int i = 0, count = possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
            SimpleCollisionBox box = shape(super.possibleMovementCollisionBoxCandidate(i, scratch), peek);
            if (i == 0) out.set(box);
            else
                out.set(
                        Math.min(out.minX, box.minX),
                        Math.min(out.minY, box.minY),
                        Math.min(out.minZ, box.minZ),
                        Math.max(out.maxX, box.maxX),
                        Math.max(out.maxY, box.maxY),
                        Math.max(out.maxZ, box.maxZ));
        }
        return out;
    }

    /** The movement boxes widened by the reach margin the position boxes carry. */
    @Override
    public SimpleCollisionBox getPossibleCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        if (!modelled()) return super.getPossibleCollisionBoxes(out, scratch);
        SimpleCollisionBox reach = super.getPossibleCollisionBoxes(new SimpleCollisionBox(), scratch);
        SimpleCollisionBox positions = super.getPossibleMovementCollisionBoxes(new SimpleCollisionBox(), scratch);
        getPossibleMovementCollisionBoxes(out, scratch);
        return out.set(
                out.minX - (positions.minX - reach.minX),
                out.minY - (positions.minY - reach.minY),
                out.minZ - (positions.minZ - reach.minZ),
                out.maxX + (reach.maxX - positions.maxX),
                out.maxY + (reach.maxY - positions.maxY),
                out.maxZ + (reach.maxZ - positions.maxZ));
    }

    @Override
    public SimpleCollisionBox getExactMovementCollisionBox() {
        SimpleCollisionBox position = super.getExactMovementCollisionBox();
        return position == null || !modelled() ? position : shape(position.copy(), peekRead());
    }

    @Override
    public SimpleCollisionBox movementBoxAt(SimpleCollisionBox positionBox) {
        return modelled() ? shape(positionBox, peekRead()) : positionBox;
    }

    /** Shulker#makeBoundingBox with currentPeekAmount {@code peek} at the position {@code positionBox} holds. */
    private SimpleCollisionBox shape(SimpleCollisionBox positionBox, float peek) {
        Vec3 position = clientPosition(positionBox);
        Box box = ShulkerGeometry.progressBounds(
                size(), blockSim(facing.getOpposite()), physicalPeek(peek), blockSim(position));
        return positionBox.set(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    /** Shulker#getPhysicalPeek, with the client's Mth#sin. */
    private float physicalPeek(float amount) {
        return 0.5F - player.trigHandler.clientSin((0.5F + amount) * (float) Math.PI) * 0.5F;
    }

    /** LivingEntity#getScale through Shulker#sanitizeScale. */
    private float size() {
        return Math.min(scale, 3.0F);
    }

    /** The position the client gives a shulker whose box is {@code box}: Shulker#setPos snaps a root. */
    private Vec3 clientPosition(SimpleCollisionBox box) {
        double x = (box.maxX - box.minX) / 2 + box.minX;
        double z = (box.maxZ - box.minZ) / 2 + box.minZ;
        return snap(x, box.minY, z);
    }

    private Vec3 clientPosition() {
        Vec3 position = clientPhysicalPosition;
        return snap(position.x, position.y, position.z);
    }

    private Vec3 snap(double x, double y, double z) {
        if (riding != null) return new Vec3(x, y, z);
        return new Vec3(CultMath.floor(x) + 0.5, CultMath.floor(y + 0.5), CultMath.floor(z) + 0.5);
    }

    /** The one position every position box gives the client, or null when they differ. */
    private @Nullable Vec3 singleClientPosition() {
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        Vec3 position = null;
        for (int i = 0, count = possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
            Vec3 candidate = clientPosition(super.possibleMovementCollisionBoxCandidate(i, scratch));
            if (position == null) position = candidate;
            else if (!position.equals(candidate)) return null;
        }
        return position;
    }

    private static long blockOf(Vec3 position) {
        return BlockPos.containing(position.x, position.y, position.z).asLong();
    }

    // PacketEntity's constructor runs before this one assigns the player.
    private boolean modelled() {
        return player != null && !player.isBedrockMovement();
    }

    private static ac.cult.blocksim.engine.Direction blockSim(Direction direction) {
        return ac.cult.blocksim.engine.Direction.values()[direction.ordinal()];
    }

    private static ac.cult.blocksim.engine.Vec3 blockSim(Vec3 position) {
        return new ac.cult.blocksim.engine.Vec3(position.x, position.y, position.z);
    }
}
