package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.ShulkerGeometry;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMath;
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
 * its vehicle chain gives it, since nothing positions it.
 *
 * <p>For a Bedrock player the same peek steps at the end of each client tick and shoves nothing: the shulker is a
 * collider 0.98 tall that the physical peek opens away from its attach face ({@link #bedrockShownBox}). While riding,
 * the peek still animates, but positioning retains the last collision dimensions and depenetration vector.
 */
public class PacketEntityShulker extends PacketEntity {
    /** The half width of a Bedrock shulker's collider and its height while closed. */
    private static final float BEDROCK_HALF_WIDTH = 0.5F;

    private static final float BEDROCK_CLOSED_HEIGHT = 0.98F;
    /** How far a Bedrock shulker's collider stands in from each of its X and Z faces. */
    private static final float BEDROCK_SIDE_INSET = 1.0E-4F;

    private static final float BEDROCK_DEPENETRATION_PER_PEEK = 1.1F;

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
    private SimpleCollisionBox reachBounds;
    private SimpleCollisionBox reachPositions;
    // Spawn starts with the ordinary actor dimensions. Only an unmounted normal tick computes shell bounds.
    private boolean bedrockBoundsComputed;
    private BedrockRidingShape bedrockRidingShape;

    private record BedrockRidingShape(float width, float height, BedrockDepenetrationLimit limit) {}

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
        if (player.isBedrockMovement() && riding == null) {
            SimpleCollisionBox box = bedrockShownBox(player, new SimpleCollisionBox());
            // Passenger positioning refreshes an ordinary AABB from the last shape's width and height. The mounted
            // shulker's normal tick advances the peek alone; it does not recompute the shell or its solid-mob limit.
            bedrockRidingShape = new BedrockRidingShape(
                    (float) Math.max(box.maxX - box.minX, box.maxZ - box.minZ),
                    (float) (box.maxY - box.minY),
                    bedrockDepenetrationLimit());
        }
        super.mount(vehicle);
        // Shulker#startRiding attaches to the floor on the client as well.
        facing = Direction.DOWN;
    }

    @Override
    public void onMovement(CultPlayer player) {
        // Bedrock entities step once a client tick's move is predicted: the next tick's move meets this step.
        if (player.isBedrockMovement()) {
            stepPeek();
            // A dismount changes no bounds until the first unmounted normal tick rebuilds them.
            if (riding == null) {
                bedrockBoundsComputed = true;
                bedrockRidingShape = null;
            }
        }
        if (untrackedRider()) {
            tickAsUntrackedRider();
            return;
        }
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
     * The committed currentPeekAmount for packet and action reads. Movement and replay reads use the side of this
     * shulker's tick that {@link CompensatedEntities#tickedThroughOrder()} places them on.
     */
    private float peekRead() {
        CompensatedEntities entities = player.compensatedEntities;
        if (!entities.readingMovementPositions()) return peek;
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

    /**
     * LivingEntity#pushEntities runs in super.tick, before Shulker#updatePeekAmount opens the shell. It pushes from
     * {@code riderPosition} when the shulker rides, else from its newest position box.
     */
    public Vec3 livingPushImpulse(
            @Nullable SimpleCollisionBox riderPosition, SimpleCollisionBox playerBox, Vec3 playerPosition) {
        float beforePeek = tickedThisClientTick() ? peekO : peek;
        var raw = riderPosition == null
                ? super.possibleMovementCollisionBoxCandidate(
                        possibleMovementCollisionBoxCandidateCount() - 1, new SimpleCollisionBox())
                : riderPosition.copy();
        Vec3 position = clientPosition(raw);
        var box = shape(raw, beforePeek);
        return ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes.intersects(box, playerBox)
                ? ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes.impulse(
                        position, playerPosition)
                : Vec3.ZERO;
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
    public List<SimpleCollisionBox> getPositionCollisionBoxCandidates() {
        int count = possibleMovementCollisionBoxCandidateCount();
        List<SimpleCollisionBox> boxes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) boxes.add(positionCollisionBoxCandidate(i, new SimpleCollisionBox()));
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
        if (reachBounds == null) {
            reachBounds = new SimpleCollisionBox();
            reachPositions = new SimpleCollisionBox();
        }
        SimpleCollisionBox reach = super.getPossibleCollisionBoxes(reachBounds, scratch);
        SimpleCollisionBox positions = super.getPossibleMovementCollisionBoxes(reachPositions, scratch);
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
    public SimpleCollisionBox getPossiblePositionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        return super.getPossibleMovementCollisionBoxes(out, scratch);
    }

    @Override
    public SimpleCollisionBox getClientPositionBox() {
        SimpleCollisionBox exact = super.getExactMovementCollisionBox();
        if (exact != null) return exact;
        return positionCollisionBoxCandidate(
                possibleMovementCollisionBoxCandidateCount() - 1, new SimpleCollisionBox());
    }

    /**
     * Shulker#getDefaultDimensions scales a floor shulker's height, and so its passenger attachment, by
     * 1 + currentPeekAmount while it is open; LivingEntity#getPassengerRidingPosition reads those dimensions afresh.
     */
    public float passengerHeightScale() {
        if (!modelled() || facing != Direction.DOWN) return 1.0F;
        float peek = peekRead();
        return peek > 0.0F ? 1.0F + peek : 1.0F;
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
        // Shulker#getProgressAabb: the unit shell extends by physicalPeek * size along the open face.
        double x = (positionBox.maxX - positionBox.minX) / 2 + positionBox.minX;
        double y = positionBox.minY;
        double z = (positionBox.maxZ - positionBox.minZ) / 2 + positionBox.minZ;
        if (riding == null) {
            x = CultMath.floor(x) + 0.5;
            y = CultMath.floor(y + 0.5);
            z = CultMath.floor(z) + 0.5;
        }
        float size = size();
        double half = size * 0.5;
        Direction open = facing.getOpposite();
        double extent = (double) physicalPeek(peek) * size;
        double dx = open.getStepX() * extent, dy = open.getStepY() * extent, dz = open.getStepZ() * extent;
        return positionBox.set(
                x - half + Math.min(0, dx),
                y + Math.min(0, dy),
                z - half + Math.min(0, dz),
                x + half + Math.max(0, dx),
                y + size + Math.max(0, dy),
                z + half + Math.max(0, dz));
    }

    /**
     * The collider a Bedrock client gives the shulker at its shown position: {@link #BEDROCK_HALF_WIDTH} each side of
     * the feet and {@link #BEDROCK_CLOSED_HEIGHT} tall, its face opposite the attach face moved out by the physical
     * peek, then {@link #BEDROCK_SIDE_INSET} in from each X and Z face. A passenger keeps the dimensions of the last
     * such box until its own bounds update resumes on dismount.
     */
    @Override
    public SimpleCollisionBox bedrockShownBox(CultPlayer player, SimpleCollisionBox out) {
        super.bedrockShownBox(player, out);
        if (!bedrockBoundsComputed && bedrockRidingShape == null) return out;
        double x = (out.minX + out.maxX) / 2;
        double y = out.minY;
        double z = (out.minZ + out.maxZ) / 2;
        if (bedrockRidingShape != null) {
            float half = bedrockRidingShape.width() * 0.5F;
            return out.set(x - half, y, z - half, x + half, y + bedrockRidingShape.height(), z + half);
        }
        double minX = x - BEDROCK_HALF_WIDTH, minY = y, minZ = z - BEDROCK_HALF_WIDTH;
        double maxX = x + BEDROCK_HALF_WIDTH, maxY = y + BEDROCK_CLOSED_HEIGHT, maxZ = z + BEDROCK_HALF_WIDTH;
        float open = bedrockPhysicalPeek(peek);
        switch (facing) {
            case DOWN -> maxY += open;
            case UP -> minY -= open;
            case NORTH -> maxZ += open;
            case SOUTH -> minZ -= open;
            case WEST -> maxX += open;
            case EAST -> minX -= open;
        }
        return out.set(
                minX + BEDROCK_SIDE_INSET,
                minY,
                minZ + BEDROCK_SIDE_INSET,
                maxX - BEDROCK_SIDE_INSET,
                maxY,
                maxZ - BEDROCK_SIDE_INSET);
    }

    /**
     * How deep a Bedrock move that collects this shulker may overlap a shape and still leave it: along the axis its
     * lid opens on only, {@link #BEDROCK_DEPENETRATION_PER_PEEK} times the physical peek.
     */
    public BedrockDepenetrationLimit bedrockDepenetrationLimit() {
        if (bedrockRidingShape != null) return bedrockRidingShape.limit();
        if (!bedrockBoundsComputed) return BedrockDepenetrationLimit.SOLID_ACTOR;
        float depth = BEDROCK_DEPENETRATION_PER_PEEK * bedrockPhysicalPeek(peek);
        return new BedrockDepenetrationLimit(
                Math.abs(facing.getStepX()) * depth,
                Math.abs(facing.getStepY()) * depth,
                Math.abs(facing.getStepZ()) * depth);
    }

    /** How far the lid of a Bedrock shulker at peek {@code amount} stands out from the closed collider. */
    private static float bedrockPhysicalPeek(float amount) {
        return 0.5F - BedrockMath.sin((amount + 0.5F) * BedrockMath.PI) * 0.5F;
    }

    /** Shulker#getPhysicalPeek, with the client's Mth#sin. */
    private float physicalPeek(float amount) {
        return 0.5F - player.trigHandler.clientSin((0.5F + amount) * (float) Math.PI) * 0.5F;
    }

    @Override
    protected float sanitizeScale(float scale) {
        return player.isBedrockMovement() ? scale : Math.min(scale, 3.0F);
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
