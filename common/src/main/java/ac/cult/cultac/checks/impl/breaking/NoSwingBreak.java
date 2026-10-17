package ac.cult.cultac.checks.impl.breaking;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;

@CheckData(
        name = "NoSwingBreak",
        stableKey = "cult.breaking.no_swing_break",
        description = "Did not swing while breaking block",
        experimental = true)
public class NoSwingBreak extends Check implements BlockBreakListener {
    private boolean sentAnimation;
    private boolean sentBreak;

    public NoSwingBreak(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action != PlayerAction.ABORT_DESTROY_BLOCK) { // PE DiggingAction.CANCELLED_DIGGING
            sentBreak = true;
        }
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        sentAnimation = true;
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.packetStateData.lastPacketWasTeleport) {
            onTickPacket();
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.packetStateData.receivedMovementThisClientTick) {
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
