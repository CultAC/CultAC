package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;

@CheckData(name = "NoSwingBreak", stableKey = "grim.breaking.no_swing_break", description = "Did not swing while breaking block", experimental = true)
public class NoSwingBreak extends Check implements BlockBreakListener {
    private boolean sentAnimation;
    private boolean sentBreak;

    public NoSwingBreak(GrimPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) { // PE DiggingAction.CANCELLED_DIGGING
            sentBreak = true;
        }
    }


    @GrimPacketHandler
    public void onSwing(PacketReceiveEvent event, GrimPlayer player, ServerboundSwingPacket packet) {
        sentAnimation = true;
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.packetStateData.lastPacketWasTeleport) {
            onTickPacket();
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
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
