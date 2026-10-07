package ac.cult.cultac.events.packets;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
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
            long collisionTicks = !player.isBedrockMovement()
                            && player.getClientVersion()
                                    .isOlderThan(ac.cult.cultac.network.protocol.ClientVersion.V_26_2)
                    ? Math.max(0L, lerpElapsedTicks - 1L)
                    : lerpElapsedTicks;
            double progress = Math.min(1.0D, (double) collisionTicks / (double) lerpDurationTicks);
            return CultMath.lerp(progress, oldDiameter, newDiameter);
        }
        double d0 = (double) (System.currentTimeMillis() - this.startTime) / ((double) this.endTime - this.startTime);
        return d0 < 1.0D ? CultMath.lerp(d0, oldDiameter, newDiameter) : newDiameter;
    }

    /** 26.3 WorldBorder.isWithinBounds calls getMin/Max(0.0F), using the previous world-tick size. */
    public double getBlockInteractionDiameter() {
        if (!tickBasedLerp) return getCurrentDiameter();
        if (lerpDurationTicks <= 0L) return newDiameter;
        double progress = Math.min(1.0D, (double) Math.max(0L, lerpElapsedTicks - 1L) / (double) lerpDurationTicks);
        return CultMath.lerp(progress, oldDiameter, newDiameter);
    }

    @Override
    public void onPlayerTickEnd(ac.cult.cultac.network.event.PacketReceiveEvent event) {
        // ClientLevel#tick advances the border only while world ticks run.
        if (player.isBedrockMovement()
                || player.packetStateData.serverTicksFrozen
                        && player.packetStateData.serverFrozenTickStepsRemaining == 0) return;
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
        setCenter(packet.centerX(), packet.centerZ());
        setLerp(packet.oldSize(), packet.newSize(), packet.lerpTime());
        setAbsoluteMaxSize(packet.absoluteMaxSize());
    }

    @CultPacketHandler
    public void onSetBorderCenter(
            PacketSendEvent<ClientboundSetBorderCenter> event, CultPlayer player, ClientboundSetBorderCenter packet) {
        player.sendTransaction();
        setCenter(packet.centerX(), packet.centerZ());
    }

    @CultPacketHandler
    public void onSetBorderSize(
            PacketSendEvent<ClientboundSetBorderSize> event, CultPlayer player, ClientboundSetBorderSize packet) {
        player.sendTransaction();
        setSize(packet.size());
    }

    @CultPacketHandler
    public void onSetBorderLerpSize(
            PacketSendEvent<ClientboundSetBorderLerpSize> event,
            CultPlayer player,
            ClientboundSetBorderLerpSize packet) {
        player.sendTransaction();
        setLerp(packet.oldSize(), packet.newSize(), packet.lerpTime());
    }

    private void setCenter(double x, double z) {
        player.latencyUtils.addRealTimeTaskNow(() -> {
            centerX = x;
            centerZ = z;
        });
    }

    private void setSize(double size) {
        player.latencyUtils.addRealTimeTaskNow(() -> {
            oldDiameter = size;
            newDiameter = size;
            tickBasedLerp = false;
            lerpDurationTicks = 0L;
            lerpElapsedTicks = 0L;
        });
    }

    private void setLerp(double oldDiameter, double newDiameter, long length) {
        player.latencyUtils.addRealTimeTaskNow(() -> {
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
                this.endTime = this.startTime
                        + (player.isBedrockMovement() ? Math.max(0L, length) * 50L : Math.max(0L, clientLength));
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

    static boolean usesTickBasedLerp(
            ac.cult.cultac.network.protocol.ClientVersion version,
            double oldDiameter,
            double newDiameter,
            long length) {
        return version.isNewerThanOrEquals(ac.cult.cultac.network.protocol.ClientVersion.V_1_21_11)
                && length > 0L
                && oldDiameter != newDiameter;
    }

    private void setAbsoluteMaxSize(double absoluteMaxSize) {
        player.latencyUtils.addRealTimeTaskNow(() -> {
            this.absoluteMaxSize = absoluteMaxSize;
        });
    }

    public double getAbsoluteMaxSize() {
        return absoluteMaxSize;
    }
}
