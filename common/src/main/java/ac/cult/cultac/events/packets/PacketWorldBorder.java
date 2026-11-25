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

public class PacketWorldBorder extends CultProcessor implements CheckListener, ClientTickEndListener {
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
    int clockDependentReads;
    // Until the ping after the last border packet is answered, the client holds either the border before it or after.
    private int settledTransaction;
    private double pendingMinDiameter;
    private double pendingMaxDiameter;
    private boolean pendingPlacement;
    // World ticks the client's border has run, and the sizes of a tick lerp the client may have started later.
    private long worldTicks;
    private int extentChanges;
    private double unprovenMinDiameter;
    private double unprovenMaxDiameter;
    private long unprovenUntilWorldTick;
    private double clientMinDiameter;
    private double clientMaxDiameter;

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
            // 1.21.11/26.1 WorldBorder collision shapes use getMin/Max(0.0F):
            // the previous world-tick size, until MovingBorderExtent becomes static.
            long collisionTicks =
                    !player.isBedrockMovement() && player.getClientVersion().isOlderThan(ClientVersion.V_26_2)
                            ? Math.max(0L, lerpElapsedTicks - 1L)
                            : lerpElapsedTicks;
            double progress = Math.min(1.0D, (double) collisionTicks / (double) lerpDurationTicks);
            return CultMath.lerp(progress, oldDiameter, newDiameter);
        }
        double d0 = (double) (System.currentTimeMillis() - this.startTime) / ((double) this.endTime - this.startTime);
        double diameter = d0 < 1.0D ? CultMath.lerp(d0, oldDiameter, newDiameter) : newDiameter;
        if (diameter != newDiameter) clockDependentReads++;
        return diameter;
    }

    /**
     * Counts clock reads of {@link #getCurrentDiameter} that return anything but {@code newDiameter}; a cache of a
     * diameter-dependent result stores it only when this count is the same before and after computing it.
     */
    public int clockDependentReads() {
        return clockDependentReads;
    }

    /** True when {@link #getCurrentDiameter} ignores the clock, unless it steps back before {@code startTime}. */
    public boolean hasClockIndependentDiameter() {
        return tickBasedLerp || oldDiameter == newDiameter && newDiameter != 0.0D;
    }

    /**
     * True when {@link #getCurrentDiameter} gives the same value at this instant as at any other instant this returns
     * true, whatever the clock reads; a cache of a diameter-dependent result checks it both when storing and reusing.
     */
    public boolean hasClockIndependentDiameterNow() {
        if (tickBasedLerp) return true;
        if (oldDiameter != newDiameter || newDiameter == 0.0D || !Double.isFinite(newDiameter)) return false;
        // A zero lerp span gives newDiameter only while the clock is not behind startTime.
        return (double) this.endTime - this.startTime != 0.0D || System.currentTimeMillis() >= this.startTime;
    }

    /** 26.3 WorldBorder.isWithinBounds calls getMin/Max(0.0F), using the previous world-tick size. */
    public double getBlockInteractionDiameter() {
        if (!tickBasedLerp) return getCurrentDiameter();
        if (lerpDurationTicks <= 0L) return newDiameter;
        double progress = Math.min(1.0D, (double) Math.max(0L, lerpElapsedTicks - 1L) / (double) lerpDurationTicks);
        return CultMath.lerp(progress, oldDiameter, newDiameter);
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        // ClientLevel#tick advances the border only while world ticks run.
        if (player.isBedrockMovement()
                || player.packetStateData.serverTicksFrozen
                        && player.packetStateData.serverFrozenTickStepsRemaining == 0) return;
        worldTicks++;
        if (tickBasedLerp && lerpElapsedTicks < lerpDurationTicks) {
            lerpElapsedTicks++;
            if (lerpElapsedTicks >= lerpDurationTicks) {
                oldDiameter = newDiameter;
                tickBasedLerp = false;
            }
        }
    }

    @CultPacketHandler
    public void onInitializeBorder(
            PacketSendEvent<ClientboundInitializeBorder> event, CultPlayer player, ClientboundInitializeBorder packet) {
        player.sendTransaction();
        change(() -> {
            setCenter(packet.centerX(), packet.centerZ());
            setLerp(packet.oldSize(), packet.newSize(), packet.lerpTime());
            setAbsoluteMaxSize(packet.absoluteMaxSize());
        });
    }

    @CultPacketHandler
    public void onSetBorderCenter(
            PacketSendEvent<ClientboundSetBorderCenter> event, CultPlayer player, ClientboundSetBorderCenter packet) {
        player.sendTransaction();
        change(() -> setCenter(packet.centerX(), packet.centerZ()));
    }

    @CultPacketHandler
    public void onSetBorderSize(
            PacketSendEvent<ClientboundSetBorderSize> event, CultPlayer player, ClientboundSetBorderSize packet) {
        player.sendTransaction();
        change(() -> setSize(packet.size()));
    }

    @CultPacketHandler
    public void onSetBorderLerpSize(
            PacketSendEvent<ClientboundSetBorderLerpSize> event,
            CultPlayer player,
            ClientboundSetBorderLerpSize packet) {
        player.sendTransaction();
        change(() -> setLerp(packet.oldSize(), packet.newSize(), packet.lerpTime()));
    }

    /**
     * Applies a border packet once the ping before it is answered. The client applies the packet before it answers the
     * next ping, so until then it holds the border before {@code apply} or the one after it.
     */
    private void change(Runnable apply) {
        int settled = player.lastTransactionSent.get() + 1;
        player.latencyUtils.addRealTimeTaskNow(() -> {
            boolean placed = placeClientBorder(true);
            double minDiameter = clientMinDiameter;
            double maxDiameter = clientMaxDiameter;
            double x = centerX;
            double z = centerZ;
            double max = absoluteMaxSize;
            int extent = extentChanges;
            apply.run();
            placeClientBorder(false);
            pendingMinDiameter = Math.min(minDiameter, clientMinDiameter);
            pendingMaxDiameter = Math.max(maxDiameter, clientMaxDiameter);
            pendingPlacement = !placed || x != centerX || z != centerZ || max != absoluteMaxSize;
            settledTransaction = settled;
            if (extent != extentChanges && tickBasedLerp) {
                int lerp = extentChanges;
                long start = worldTicks;
                long end = start + lerpDurationTicks;
                // A lerp the client applied on the tick the engine started it advances on the same ticks.
                player.latencyUtils.addRealTimeTask(settled, () -> {
                    if (lerp != extentChanges) return;
                    long lag = worldTicks - start;
                    unprovenUntilWorldTick = lag == 0L ? 0L : end + lag;
                });
            }
        });
    }

    /**
     * Sets the smallest and largest diameter the client's border can have on this tick, the pending packets included
     * when {@code pending}; false when the client's center or absolute size is not known.
     */
    private boolean placeClientBorder(boolean pending) {
        if (pending && player.lastTransactionReceived.get() < settledTransaction) {
            clientMinDiameter = pendingMinDiameter;
            clientMaxDiameter = pendingMaxDiameter;
            return !pendingPlacement;
        }
        double min;
        double max;
        if (tickBasedLerp) {
            // MovingBorderExtent#getMinX(0.0F) reads the size before the last update, one of these two update counts.
            min = lerpDiameter(lerpElapsedTicks);
            max = lerpDiameter(Math.max(0L, lerpElapsedTicks - 1L));
        } else {
            min = oldDiameter;
            max = newDiameter;
        }
        if (worldTicks < unprovenUntilWorldTick) {
            min = Math.min(min, unprovenMinDiameter);
            max = Math.max(max, unprovenMaxDiameter);
        }
        clientMinDiameter = Math.min(min, max);
        clientMaxDiameter = Math.max(min, max);
        return true;
    }

    /** MovingBorderExtent#calculateSize after {@code updates} updates. */
    private double lerpDiameter(long updates) {
        if (lerpDurationTicks <= 0L) return newDiameter;
        double progress = (double) updates / (double) lerpDurationTicks;
        return progress < 1.0D ? CultMath.lerp(progress, oldDiameter, newDiameter) : newDiameter;
    }

    /**
     * WorldBorder#isInsideCloseToBorder for an entity at {@code x}, {@code z} whose bounding box, expanded towards its
     * movement, gives a bbMax between {@code bbMaxLow} and {@code bbMaxHigh}, for every border the client can hold.
     */
    public DesyncStatus isInsideCloseToBorder(double x, double z, double bbMaxLow, double bbMaxHigh) {
        if (!placeClientBorder(true)) return DesyncStatus.UNKNOWN;
        // The distance to the border and the bounds both grow with the diameter.
        if (distanceToBorder(x, z, clientMaxDiameter) < bbMaxLow * 2.0D
                && isWithinBounds(x, z, clientMinDiameter, bbMaxLow)) {
            return DesyncStatus.TRUE;
        }
        if (distanceToBorder(x, z, clientMinDiameter) >= bbMaxHigh * 2.0D
                || !isWithinBounds(x, z, clientMaxDiameter, bbMaxHigh)) {
            return DesyncStatus.FALSE;
        }
        return DesyncStatus.UNKNOWN;
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

    private void setCenter(double x, double z) {
        centerX = x;
        centerZ = z;
    }

    private void setSize(double size) {
        oldDiameter = size;
        newDiameter = size;
        tickBasedLerp = false;
        lerpDurationTicks = 0L;
        lerpElapsedTicks = 0L;
        extentChanges++;
        unprovenUntilWorldTick = 0L;
    }

    private void setLerp(double oldDiameter, double newDiameter, long length) {
        this.oldDiameter = oldDiameter;
        this.newDiameter = newDiameter;
        long clientLength = clientLerpDuration(
                ClientVersion.fromProtocolVersion(player.getObservedProtocol().protocol()),
                player.getClientVersion(),
                length);
        this.tickBasedLerp = usesTickBasedLerp(player.getClientVersion(), oldDiameter, newDiameter, clientLength);
        if (this.tickBasedLerp) {
            this.lerpDurationTicks = clientLength;
            this.lerpElapsedTicks = 0L;
        } else {
            this.startTime = System.currentTimeMillis();
            this.endTime = this.startTime
                    + (player.isBedrockMovement() ? Math.max(0L, length) * 50L : Math.max(0L, clientLength));
        }
        extentChanges++;
        unprovenMinDiameter = Math.min(oldDiameter, newDiameter);
        unprovenMaxDiameter = Math.max(oldDiameter, newDiameter);
        unprovenUntilWorldTick = this.tickBasedLerp ? Long.MAX_VALUE : 0L;
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

    private void setAbsoluteMaxSize(double absoluteMaxSize) {
        this.absoluteMaxSize = absoluteMaxSize;
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
