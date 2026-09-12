package ac.cult.cultac.checks.impl.breaking;

import net.minecraft.network.protocol.Packet;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

@CheckData(name = "NoSwingBreak", stableKey = "cult.breaking.no_swing_break", description = "Did not swing while breaking block", experimental = true)
public class NoSwingBreak extends Check implements BlockBreakListener {
    private boolean sentAnimation;
    private boolean sentBreak;

    public NoSwingBreak(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) { // PE DiggingAction.CANCELLED_DIGGING
            sentBreak = true;
        }
    }


    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundPunchPacket")
    public void onPunch(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSwing(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSwingPacket")
    public void onSwing(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        sentAnimation = true;
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.packetStateData.lastPacketWasTeleport) {
            onTickPacket();
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            onTickPacket();
        }
    }

    private void onTickPacket() {
        if (sentBreak && !sentAnimation) {
            flag();
        }

        sentAnimation = sentBreak = false;
    }
}
