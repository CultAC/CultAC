package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "CrashA", stableKey = "cult.crash.large_position", description = "Sent a position outside the valid world bounds")
public class CrashA extends Check implements CheckListener {
    private static final double HARD_CODED_BORDER = 2.9999999E7D;

    public CrashA(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
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
