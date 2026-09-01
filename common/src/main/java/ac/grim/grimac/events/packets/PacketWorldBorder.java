package ac.grim.grimac.events.packets;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;

public class PacketWorldBorder extends GrimProcessor implements CheckListener {
    double centerX;
    double centerZ;
    double oldDiameter;
    double newDiameter;
    double absoluteMaxSize;
    long startTime = 1;
    long endTime = 1;

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
        double d0 = (double) (System.currentTimeMillis() - this.startTime) / ((double) this.endTime - this.startTime);
        return d0 < 1.0D ? GrimMath.lerp(d0, oldDiameter, newDiameter) : newDiameter;
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
        });
    }

    private void setLerp(double oldDiameter, double newDiameter, long length) {
        player.latencyUtils.addRealTimeTaskNow(() -> { this.oldDiameter = oldDiameter;
            this.newDiameter = newDiameter;
            this.startTime = System.currentTimeMillis();
            this.endTime = this.startTime + length;
        });
    }

    private void setAbsoluteMaxSize(double absoluteMaxSize) {
        player.latencyUtils.addRealTimeTaskNow(() -> { this.absoluteMaxSize = absoluteMaxSize;
        });
    }

    public double getAbsoluteMaxSize() {
        return absoluteMaxSize;
    }
}
