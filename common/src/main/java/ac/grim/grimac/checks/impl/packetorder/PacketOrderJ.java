package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

@CheckData(name = "PacketOrderJ", stableKey = "grim.packetorder.attack_interact_use_order", description = "Sent use item after attacking without the expected interaction packet", experimental = true)
public class PacketOrderJ extends Check implements PostPredictionListener {
    public PacketOrderJ(final GrimPlayer player) {
        super(player);
    }

    private int invalid;


    @GrimPacketHandler
    public void onUseItemOn(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemOnPacket packet) {
        onUse(event, player);
    }


    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        onUse(event, player);
    }

    private void onUse(PacketReceiveEvent event, GrimPlayer player) {
        // we don't check stabbing here because you don't need to target an entity to stab
        if (player.packetOrderProcessor.isAttacking() && !player.packetOrderProcessor.isInteracting()) {
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
