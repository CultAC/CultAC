package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

@CheckData(name = "BadPacketsH", stableKey = "grim.badpackets.unexpected_sequence", description = "Sent unexpected sequence id", experimental = true)
public class BadPacketsH extends BlockPlaceCheck implements BlockBreakListener {
    private static final Verbose V = Verbose.of("expected={sint}, id={sint}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private int lastSequence;

    public BadPacketsH(final GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_19)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_19); // PE ServerVersion.V_1_19
    }


    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        if (!isApplicable()) return;
        if (shouldCancel(NmsPacketUtil.readUseItem(packet).sequence())) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        if (shouldCancel(place.sequence) && shouldCancel()) {
            place.resync();
        }
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        switch (blockBreak.action) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                if (shouldCancel(blockBreak.sequence)) {
                    blockBreak.cancel();
                }
            }
            case ABORT_DESTROY_BLOCK -> {
                if (blockBreak.sequence != 0
                        && flagSequence(0, blockBreak.sequence)
                        && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            }
            default -> {
                // Other player-action packets are checked by BadPacketsL.
            }
        }
    }

    public boolean shouldCancel(int sequence) {
        int expected = lastSequence + 1;
        lastSequence = sequence;
        return sequence != expected
                && flagSequence(expected, sequence)
                && shouldModifyPackets();
    }

    private boolean flagSequence(int expected, int sequence) {
        return flag(V.write(verbose()).sint(expected).sint(sequence));
    }

    public void onWorldChange() {
        lastSequence = 0;
    }

}
