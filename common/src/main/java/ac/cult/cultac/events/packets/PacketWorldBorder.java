package ac.cult.cultac.events.packets;

import ac.cult.cultac.checks.CultProcessor;
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
import java.util.function.IntConsumer;

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

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
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
        change.accept(transaction > 0 ? transaction : player.lastTransactionSent.get());
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
        });
    }

    private void setLerp(int transaction, double oldDiameter, double newDiameter, long length) {
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            this.oldDiameter = oldDiameter;
            this.newDiameter = newDiameter;
            long clientLength = clientLerpDuration(
                    ClientVersion.fromProtocolVersion(
                            player.getObservedProtocol().protocol()),
                    player.getClientVersion(),
                    length);
            this.tickBasedLerp = usesTickBasedLerp(player.getClientVersion(), oldDiameter, newDiameter, clientLength);
            if (this.tickBasedLerp) {
                this.lerpDurationTicks = clientLength;
                this.lerpElapsedTicks = 0L;
            } else {
                this.startTime = System.currentTimeMillis();
                this.endTime = this.startTime + Math.max(0L, clientLength);
            }
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
}
