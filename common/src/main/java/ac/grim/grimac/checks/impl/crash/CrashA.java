package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "CrashA", stableKey = "grim.crash.large_position", description = "Sent a position outside the valid world bounds")
public class CrashA extends Check implements CheckListener {
    private static final double HARD_CODED_BORDER = 2.9999999E7D;

    public CrashA(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.packetStateData.lastPacketWasTeleport) return;
        if (!packet.hasPosition()) return;

        // Y technically is uncapped, but no player will reach these values legit
        if (Math.abs(packet.getX(0)) > HARD_CODED_BORDER || Math.abs(packet.getZ(0)) > HARD_CODED_BORDER || Math.abs(packet.getY(0)) > Integer.MAX_VALUE) {
            flag(); // Ban
            executeViolationSetback();
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
