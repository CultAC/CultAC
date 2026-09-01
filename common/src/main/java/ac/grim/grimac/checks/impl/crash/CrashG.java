package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

@CheckData(name = "CrashG", stableKey = "grim.crash.negative_sequence", description = "Sent negative sequence id")
public class CrashG extends BlockPlaceCheck implements BlockBreakListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());



    public CrashG(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_19)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_19);
    }

    @GrimPacketHandler
    public void onUseItem(final PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        if (!isApplicable()) return;
        if (packet.getSequence() < 0) {
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
