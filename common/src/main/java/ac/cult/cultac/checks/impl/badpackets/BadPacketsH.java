package ac.cult.cultac.checks.impl.badpackets;

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
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsH",
        stableKey = "cult.badpackets.unexpected_sequence",
        description = "Sent unexpected sequence id",
        experimental = true)
public class BadPacketsH extends BlockPlaceCheck implements BlockBreakListener {
    private static final Verbose V = Verbose.of("expected={sint}, id={sint}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private int lastSequence;

    public BadPacketsH(final CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_19)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_19); // PE ServerVersion.V_1_19
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!isApplicable()) return;
        if (shouldCancel(packet.sequence())) {
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
                if (blockBreak.sequence != 0 && flagSequence(0, blockBreak.sequence) && shouldModifyPackets()) {
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
        return sequence != expected && flagSequence(expected, sequence) && shouldModifyPackets();
    }

    private boolean flagSequence(int expected, int sequence) {
        return flag(V.write(verbose()).sint(expected).sint(sequence));
    }

    public void onWorldChange() {
        lastSequence = 0;
    }
}
