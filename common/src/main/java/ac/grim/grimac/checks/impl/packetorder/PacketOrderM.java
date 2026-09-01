package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

@CheckData(name = "PacketOrderM", stableKey = "grim.packetorder.interact_use_order", description = "Sent use item and entity interaction packets in an invalid order", experimental = true)
public class PacketOrderM extends Check implements PostPredictionListener {
    public PacketOrderM(final GrimPlayer player) {
        super(player);
    }

    private int invalid;
    private boolean usingWithoutInteract, interacting;


    @GrimPacketHandler
    public void onInteract(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        if (NmsPacketUtil.readInteract(packet).action() != NmsPacketUtil.InteractAction.ATTACK) {
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


    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        if (!interacting) {
            usingWithoutInteract = true;
        }

        interacting = false;

        if (!player.cameraEntity.isSelf()) {
            usingWithoutInteract = interacting = false;
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            usingWithoutInteract = interacting = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
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
