package ac.grim.grimac.events.packets;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.type.ClientTickEndListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;

public class PacketWorldBorder extends GrimProcessor implements CheckListener, ClientTickEndListener {
    double centerX;
    double centerZ;
    double oldDiameter;
    double newDiameter;
    double absoluteMaxSize;
    long startTime = 1;
    long endTime = 1;
    long lerpDurationTicks;
    long lerpElapsedTicks;
    boolean tickBasedLerp;

    public PacketWorldBorder(GrimPlayer playerData) {
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
            double progress = Math.min(1.0D, (double) lerpElapsedTicks / (double) lerpDurationTicks);
            return GrimMath.lerp(progress, oldDiameter, newDiameter);
        }
        double d0 = (double) (System.currentTimeMillis() - this.startTime) / ((double) this.endTime - this.startTime);
        return d0 < 1.0D ? GrimMath.lerp(d0, oldDiameter, newDiameter) : newDiameter;
    }

    @Override
    public void onPlayerTickEnd(ac.grim.grimac.network.event.PacketReceiveEvent event) {
        if (tickBasedLerp && lerpElapsedTicks < lerpDurationTicks) {
            lerpElapsedTicks++;
            if (lerpElapsedTicks >= lerpDurationTicks) {
                oldDiameter = newDiameter;
                tickBasedLerp = false;
            }
        }
    }

    @GrimPacketHandler
    public void onInitializeBorder(PacketSendEvent event, GrimPlayer player, ClientboundInitializeBorderPacket packet) {
        player.sendTransaction();
        setCenter(packet.getNewCenterX(), packet.getNewCenterZ());
        setLerp(packet.getOldSize(), packet.getNewSize(), packet.getLerpTime());
        setAbsoluteMaxSize(packet.getNewAbsoluteMaxSize());
    }

    @GrimPacketHandler
    public void onSetBorderCenter(PacketSendEvent event, GrimPlayer player, ClientboundSetBorderCenterPacket packet) {
        player.sendTransaction();
        setCenter(packet.getNewCenterX(), packet.getNewCenterZ());
    }

    @GrimPacketHandler
    public void onSetBorderSize(PacketSendEvent event, GrimPlayer player, ClientboundSetBorderSizePacket packet) {
        player.sendTransaction();
        setSize(packet.getSize());
    }

    @GrimPacketHandler
    public void onSetBorderLerpSize(PacketSendEvent event, GrimPlayer player, ClientboundSetBorderLerpSizePacket packet) {
        player.sendTransaction();
        setLerp(packet.getOldSize(), packet.getNewSize(), packet.getLerpTime());
    }

    private void setCenter(double x, double z) {
        player.latencyUtils.addRealTimeTaskNow(() -> { centerX = x;
            centerZ = z;
        });
    }

    private void setSize(double size) {
        player.latencyUtils.addRealTimeTaskNow(() -> { oldDiameter = size;
            newDiameter = size;
            tickBasedLerp = false;
            lerpDurationTicks = 0L;
            lerpElapsedTicks = 0L;
        });
    }

    private void setLerp(double oldDiameter, double newDiameter, long length) {
        player.latencyUtils.addRealTimeTaskNow(() -> { this.oldDiameter = oldDiameter;
            this.newDiameter = newDiameter;
            this.tickBasedLerp = usesTickBasedLerp(player.getClientVersion(), oldDiameter, newDiameter, length);
            if (this.tickBasedLerp) {
                this.lerpDurationTicks = length;
                this.lerpElapsedTicks = 0L;
            } else {
                // The 26.2 server packet expresses the duration in ticks. Via's
                // older-client codec presents the corresponding real-time border.
                this.startTime = System.currentTimeMillis();
                this.endTime = this.startTime + Math.max(0L, length) * 50L;
            }
        });
    }

    static boolean usesTickBasedLerp(
            ac.grim.grimac.network.protocol.ClientVersion version,
            double oldDiameter,
            double newDiameter,
            long length
    ) {
        return version.isNewerThanOrEquals(ac.grim.grimac.network.protocol.ClientVersion.V_1_21_11)
                && length > 0L && oldDiameter != newDiameter;
    }

    private void setAbsoluteMaxSize(double absoluteMaxSize) {
        player.latencyUtils.addRealTimeTaskNow(() -> { this.absoluteMaxSize = absoluteMaxSize;
        });
    }

    public double getAbsoluteMaxSize() {
        return absoluteMaxSize;
    }
}
