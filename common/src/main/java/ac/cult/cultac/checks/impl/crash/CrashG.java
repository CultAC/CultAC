package ac.cult.cultac.checks.impl.crash;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;

@CheckData(name = "CrashG", stableKey = "cult.crash.negative_sequence", description = "Sent negative sequence id")
public class CrashG extends BlockPlaceCheck implements BlockBreakListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    public CrashG(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_19)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_19);
    }

    @CultPacketHandler
    public void onUseItem(
            final PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!isApplicable()) return;
        if (packet.sequence() < 0) {
            flag();
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (!isApplicable()) return;
        if (blockBreak.sequence < 0) {
            flag();
            blockBreak.cancel();
        }
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        if (!isApplicable()) return;
        if (place.sequence < 0) {
            flag();
            place.resync();
        }
    }
}
