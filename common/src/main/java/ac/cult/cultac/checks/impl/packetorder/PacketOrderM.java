package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "PacketOrderM",
        stableKey = "cult.packetorder.interact_use_order",
        description = "Sent use item and entity interaction packets in an invalid order",
        experimental = true)
public class PacketOrderM extends Check implements PostPredictionListener {
    public PacketOrderM(final CultPlayer player) {
        super(player);
    }

    private int invalid;
    private boolean usingWithoutInteract, interacting;

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if ((packet.action() == ac.cult.cultac.protocol.value.InteractAction.ATTACK
                && ProtocolVersion.V26_3.protocol() >= ac.cult.cultac.protocol.ProtocolVersion.V26_1.protocol()))
            return;
        if (packet.action() != InteractAction.ATTACK) {
            interacting = true;
            if (usingWithoutInteract) {
                if (!player.canSkipTicks()) {
                    if (flag() && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                } else {
                    invalid++;
                }
            }
        }

        if (!player.cameraEntity.isSelf()) {
            usingWithoutInteract = interacting = false;
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!interacting) {
            usingWithoutInteract = true;
        }

        interacting = false;

        if (!player.cameraEntity.isSelf()) {
            usingWithoutInteract = interacting = false;
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            usingWithoutInteract = interacting = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.cameraEntity.isSelf()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                        && !player.packetStateData.receivedMovementThisClientTick)) {
            usingWithoutInteract = interacting = false;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (; invalid >= 1; invalid--) {
                flag();
            }
        }

        invalid = 0;
    }
}
