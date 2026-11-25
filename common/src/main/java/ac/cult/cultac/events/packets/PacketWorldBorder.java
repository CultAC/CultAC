package ac.cult.cultac.events.packets;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundInitializeBorder;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderCenter;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderLerpSize;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetBorderSize;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.function.IntConsumer;

public class PacketWorldBorder extends CultProcessor implements CheckListener, ClientTickEndListener {
    /** Minecraft#runTick runs at most this many client ticks a frame. */
    private static final long FRAME_TICKS = 10L;
    /**
     * The most ticks DeltaTracker.Timer gives per millisecond of Util#getMillis: one per
     * Minecraft#getTickTargetMillis, at least 50 ms, and under 2^-23 more from the rounding of its float sums.
     */
    private static final double TICKS_PER_MILLI = 1.0D / 50.0D + 0x1p-23;

    double centerX;
    double centerZ;
    // WorldBorder's initial client state before an initialize-border packet.
    double oldDiameter = 59_999_968;
    double newDiameter = 59_999_968;
    double absoluteMaxSize = 29_999_984;
    long startTime = 1;
    long endTime = 1;
    long lerpDurationTicks;
    long lerpElapsedTicks;
    boolean tickBasedLerp;
    // The first transaction the client answers after a border packet sent without a flush proof.
    private volatile int unprovenUntilTransaction;
    // The transaction the client answers only after handling the clock lerp, and the client ticks ended since.
    private int clockLerpProof;
    private long clockLerpTicks;
    /** The gather a prediction simulates where the border the client holds leaves it open. */
    private boolean uncertainGather;
    /** Whether a collision read {@link #uncertainGather} since {@link #watchUncertainGather}. */
    private boolean uncertainGatherRead;

    public PacketWorldBorder(CultPlayer playerData) {
        super(playerData);
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public double getCurrentDiameter() {
        if (tickBasedLerp) {
            if (lerpDurationTicks <= 0L) {
                return newDiameter;
            }
            // MovingBorderExtent#getCollisionShape uses getMin/Max(0.0F): the previous world-tick size, until
            // the extent becomes static. Minecraft#tick moves entities before ClientLevel#tick updates the border.
            long collisionTicks = Math.max(0L, lerpElapsedTicks - 1L);
            double progress = Math.min(1.0D, (double) collisionTicks / (double) lerpDurationTicks);
            return CultMath.lerp(progress, oldDiameter, newDiameter);
        }
        double d0 = (double) (System.currentTimeMillis() - this.startTime) / ((double) this.endTime - this.startTime);
        return d0 < 1.0D ? CultMath.lerp(d0, oldDiameter, newDiameter) : newDiameter;
    }

    /** True when {@link #getCurrentDiameter} ignores the clock, unless it steps back before {@code startTime}. */
    public boolean hasClockIndependentDiameter() {
        return tickBasedLerp || oldDiameter == newDiameter && newDiameter != 0.0D;
    }

    /** 26.3 WorldBorder.isWithinBounds calls getMin/Max(0.0F), using the previous world-tick size. */
    public double getBlockInteractionDiameter() {
        if (!tickBasedLerp) return getCurrentDiameter();
        if (lerpDurationTicks <= 0L) return newDiameter;
        double progress = Math.min(1.0D, (double) Math.max(0L, lerpElapsedTicks - 1L) / (double) lerpDurationTicks);
        return CultMath.lerp(progress, oldDiameter, newDiameter);
    }

    /** Whether the client may still run a MovingBorderExtent on its clock, as clients before 1.21.11 do. */
    public boolean holdsClockLerp() {
        return !tickBasedLerp && oldDiameter != newDiameter;
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (holdsClockLerp() && !event.isSynthetic() && player.lastTransactionReceived.get() >= clockLerpProof) {
            clockLerpTicks++;
            if (clockLerpEnded()) oldDiameter = newDiameter;
        }
        // ClientLevel#tick advances the border only while world ticks run.
        if (player.packetStateData.serverTicksFrozen && player.packetStateData.serverFrozenTickStepsRemaining == 0)
            return;
        if (tickBasedLerp && lerpElapsedTicks < lerpDurationTicks) {
            lerpElapsedTicks++;
            if (lerpElapsedTicks >= lerpDurationTicks) {
                oldDiameter = newDiameter;
                tickBasedLerp = false;
            }
        }
    }

    /**
     * Whether the client's MovingBorderExtent has reached its target by the client's next tick. The client sets
     * lerpBegin in one frame, before it answers {@link #clockLerpProof}. Apart from the ticks of that frame and the
     * next, the ticks ended since, with the next one, follow Util#getMillis time after lerpBegin and less than one
     * tick of residual that DeltaTracker.Timer carries in. {@link #getCurrentDiameter} must have reached it as well.
     */
    private boolean clockLerpEnded() {
        return clockLerpTicks - 2L * FRAME_TICKS >= (endTime - startTime) * TICKS_PER_MILLI
                && System.currentTimeMillis() >= endTime;
    }

    @CultPacketHandler
    public void onInitializeBorder(
            PacketSendEvent<ClientboundInitializeBorder> event, CultPlayer player, ClientboundInitializeBorder packet) {
        atProof(event, transaction -> {
            setCenter(transaction, packet.centerX(), packet.centerZ());
            setLerp(transaction, packet.oldSize(), packet.newSize(), packet.lerpTime());
            setAbsoluteMaxSize(transaction, packet.absoluteMaxSize());
        });
    }

    @CultPacketHandler
    public void onSetBorderCenter(
            PacketSendEvent<ClientboundSetBorderCenter> event, CultPlayer player, ClientboundSetBorderCenter packet) {
        atProof(event, transaction -> setCenter(transaction, packet.centerX(), packet.centerZ()));
    }

    @CultPacketHandler
    public void onSetBorderSize(
            PacketSendEvent<ClientboundSetBorderSize> event, CultPlayer player, ClientboundSetBorderSize packet) {
        atProof(event, transaction -> setSize(transaction, packet.size()));
    }

    @CultPacketHandler
    public void onSetBorderLerpSize(
            PacketSendEvent<ClientboundSetBorderLerpSize> event,
            CultPlayer player,
            ClientboundSetBorderLerpSize packet) {
        atProof(event, transaction -> setLerp(transaction, packet.oldSize(), packet.newSize(), packet.lerpTime()));
    }

    /**
     * Applies a border packet at the flush ping that proves it: ClientPacketListener applies it in the flush bundle's
     * task, as it does an entity or block update, so the pong puts it after the client tick before it and before the
     * next. A Bedrock client never has this border, which Geyser keeps to itself and marks with its own actors, so
     * for a Bedrock player the packet changes nothing and the border stays the initial one.
     */
    private void atProof(PacketSendEvent<?> event, IntConsumer change) {
        if (player.isBedrockMovement()) return;
        int transaction = player.appendFlushProofTransaction(event);
        if (transaction <= 0) unprovenUntilTransaction = player.lastTransactionSent.get() + 1;
        change.accept(transaction > 0 ? transaction : player.lastTransactionSent.get());
    }

    /**
     * WorldBorder#isInsideCloseToBorder for an entity at {@code x}, {@code z} whose bounding box, expanded towards its
     * movement, gives a bbMax between {@code bbMaxLow} and {@code bbMaxHigh}, for every border the client can hold.
     * While the client lerps the border on its clock, the answer follows {@link Collisions#addWorldBorder} for the query
     * from the player's last position: UNKNOWN where it adds the border's planes, FALSE elsewhere.
     */
    public DesyncStatus isInsideCloseToBorder(double x, double z, double bbMaxLow, double bbMaxHigh) {
        if (player.lastTransactionReceived.get() < unprovenUntilTransaction) return DesyncStatus.UNKNOWN;
        if (holdsClockLerp()) {
            return Collisions.gathersBorderWithinSixteen(this, getCurrentDiameter(), player.lastX, player.lastZ)
                    ? DesyncStatus.UNKNOWN
                    : DesyncStatus.FALSE;
        }
        double minDiameter;
        double maxDiameter;
        if (tickBasedLerp) {
            // MovingBorderExtent#getMinX(0.0F) reads the size before the last update, one of these two update counts.
            double updated = lerpDiameter(lerpElapsedTicks);
            double before = lerpDiameter(Math.max(0L, lerpElapsedTicks - 1L));
            minDiameter = Math.min(updated, before);
            maxDiameter = Math.max(updated, before);
        } else {
            minDiameter = newDiameter;
            maxDiameter = newDiameter;
        }
        // The distance to the border and the bounds both grow with the diameter.
        if (distanceToBorder(x, z, maxDiameter) < bbMaxLow * 2.0D && isWithinBounds(x, z, minDiameter, bbMaxLow)) {
            return DesyncStatus.TRUE;
        }
        if (distanceToBorder(x, z, minDiameter) >= bbMaxHigh * 2.0D || !isWithinBounds(x, z, maxDiameter, bbMaxHigh)) {
            return DesyncStatus.FALSE;
        }
        return DesyncStatus.UNKNOWN;
    }

    /**
     * Whether Entity#collectCollidersIgnoringWorldBorder and CollisionGetter#borderCollision add the border's shape
     * for an entity at {@code x}, {@code z} querying a box whose larger horizontal size is {@code size}. Where the
     * border the client holds leaves WorldBorder#isInsideCloseToBorder open, the gather the prediction simulates.
     */
    public boolean gathers(double x, double z, double size) {
        double bbMax = Math.max(size, 1.0D);
        DesyncStatus close = isInsideCloseToBorder(x, z, bbMax, bbMax);
        if (close != DesyncStatus.UNKNOWN) return close == DesyncStatus.TRUE;
        uncertainGatherRead = true;
        return uncertainGather;
    }

    /** The gather {@link #gathers} gives where the client's border leaves it open. */
    public boolean getUncertainGather() {
        return uncertainGather;
    }

    public void setUncertainGather(boolean gather) {
        uncertainGather = gather;
    }

    /** Starts watching for a collision whose gather the client's border leaves open. */
    public void watchUncertainGather() {
        uncertainGatherRead = false;
    }

    /** Whether {@link #gathers} gave the simulated gather since {@link #watchUncertainGather}. */
    public boolean readUncertainGather() {
        return uncertainGatherRead;
    }

    /** MovingBorderExtent#calculateSize after {@code updates} updates. */
    private double lerpDiameter(long updates) {
        if (lerpDurationTicks <= 0L) return newDiameter;
        double progress = (double) updates / (double) lerpDurationTicks;
        return progress < 1.0D ? CultMath.lerp(progress, oldDiameter, newDiameter) : newDiameter;
    }

    /** WorldBorder#getDistanceToBorder against a border of {@code diameter}. */
    private double distanceToBorder(double x, double z, double diameter) {
        double fromWest = x - borderMin(centerX, diameter);
        double fromEast = borderMax(centerX, diameter) - x;
        double fromNorth = z - borderMin(centerZ, diameter);
        double fromSouth = borderMax(centerZ, diameter) - z;
        return Math.min(Math.min(Math.min(fromWest, fromEast), fromNorth), fromSouth);
    }

    /** WorldBorder#isWithinBounds(x, z, margin) against a border of {@code diameter}. */
    private boolean isWithinBounds(double x, double z, double diameter, double margin) {
        return x >= borderMin(centerX, diameter) - margin
                && x < borderMax(centerX, diameter) + margin
                && z >= borderMin(centerZ, diameter) - margin
                && z < borderMax(centerZ, diameter) + margin;
    }

    private double borderMin(double center, double diameter) {
        return CultMath.clamp(center - diameter / 2.0D, -absoluteMaxSize, absoluteMaxSize);
    }

    private double borderMax(double center, double diameter) {
        return CultMath.clamp(center + diameter / 2.0D, -absoluteMaxSize, absoluteMaxSize);
    }

    private void setCenter(int transaction, double x, double z) {
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            centerX = x;
            centerZ = z;
        });
    }

    private void setSize(int transaction, double size) {
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            oldDiameter = size;
            newDiameter = size;
            tickBasedLerp = false;
            lerpDurationTicks = 0L;
            lerpElapsedTicks = 0L;
            uncertainGather = false;
        });
    }

    private void setLerp(int transaction, double oldDiameter, double newDiameter, long length) {
        // Without a flush proof the client handles the packet by the answer to the next transaction sent.
        int proof = Math.max(transaction, unprovenUntilTransaction);
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            long clientLength = clientLerpDuration(
                    ClientVersion.fromProtocolVersion(
                            player.getObservedProtocol().protocol()),
                    player.getClientVersion(),
                    length);
            // A MovingBorderExtent of no length has its target size from the start.
            this.oldDiameter = clientLength > 0L ? oldDiameter : newDiameter;
            this.newDiameter = newDiameter;
            this.tickBasedLerp = usesTickBasedLerp(player.getClientVersion(), oldDiameter, newDiameter, clientLength);
            if (this.tickBasedLerp) {
                this.lerpDurationTicks = clientLength;
                this.lerpElapsedTicks = 0L;
            } else {
                this.startTime = System.currentTimeMillis();
                this.endTime = this.startTime + Math.max(0L, clientLength);
                this.clockLerpProof = proof;
                this.clockLerpTicks = 0L;
            }
            this.uncertainGather = false;
        });
    }

    static long clientLerpDuration(ClientVersion observedVersion, ClientVersion clientVersion, long length) {
        // The pure packet record retains the observed duration units. Via may
        // still convert them on the path from this boundary to the actual client.
        boolean serverTicks = observedVersion.isNewerThanOrEquals(ClientVersion.V_1_21_11);
        boolean clientTicks = clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_11);
        if (serverTicks == clientTicks) return length;
        return serverTicks ? length * 50L : length / 50L;
    }

    static boolean usesTickBasedLerp(ClientVersion version, double oldDiameter, double newDiameter, long length) {
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_11) && length > 0L && oldDiameter != newDiameter;
    }

    private void setAbsoluteMaxSize(int transaction, double absoluteMaxSize) {
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            this.absoluteMaxSize = absoluteMaxSize;
        });
    }

    public double getAbsoluteMaxSize() {
        return absoluteMaxSize;
    }

    /** The diameter the border lerps towards, or holds when it does not move. */
    public double getTargetDiameter() {
        return newDiameter;
    }

    /** Client ticks the tick-based lerp has left, or -1 when the lerp runs on the clock. */
    public long getRemainingLerpTicks() {
        return tickBasedLerp ? Math.max(0L, lerpDurationTicks - lerpElapsedTicks) : -1L;
    }

    /** Milliseconds the clock-based lerp has left, or 0 when it has ended or runs on ticks. */
    public long getRemainingLerpMillis() {
        return tickBasedLerp ? 0L : Math.max(0L, endTime - System.currentTimeMillis());
    }
}
