package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.PlayerAction;
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

    private int lastSequence;
    private int placedSequence = -1;

    public BadPacketsH(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (shouldCancel(packet.sequence())) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    /**
     * Minecraft.startUseItem sends USE_ITEM_ON for each hand facing a block, empty or not, and
     * only placements reach onBlockPlace; every other id is checked here without a cancel.
     */
    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        if (packet.sequence() == placedSequence) {
            placedSequence = -1;
            return;
        }
        shouldCancel(packet.sequence());
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        placedSequence = place.sequence;
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
                if (abortFlags(blockBreak.sequence) && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            }
            default -> {
                // Other player-action packets are checked by BadPacketsL.
            }
        }
    }

    /**
     * An early check cancelled {@code packet}, so the handlers above never see it. The client still
     * spent its id, so count it here; the packet is already cancelled.
     */
    public void onEarlyCancel(Object packet) {
        switch (packet) {
            case ServerboundUseItem use -> outOfOrder(use.sequence());
            case ServerboundUseItemOn use -> outOfOrder(use.sequence());
            case ServerboundPlayerAction dig -> {
                if (dig.action() == PlayerAction.START_DESTROY_BLOCK || dig.action() == PlayerAction.STOP_DESTROY_BLOCK)
                    outOfOrder(dig.sequence());
                else if (dig.action() == PlayerAction.ABORT_DESTROY_BLOCK) abortFlags(dig.sequence());
            }
            default -> {}
        }
    }

    public boolean shouldCancel(int sequence) {
        return outOfOrder(sequence) && shouldModifyPackets();
    }

    /** Counts {@code sequence}; true when it is not the next id and flagged. */
    private boolean outOfOrder(int sequence) {
        int expected = lastSequence + 1;
        lastSequence = sequence;
        return sequence != expected && flagSequence(expected, sequence);
    }

    /** MultiPlayerGameMode sends ABORT_DESTROY_BLOCK without a prediction, so its id is always 0. */
    private boolean abortFlags(int sequence) {
        return sequence != 0 && flagSequence(0, sequence);
    }

    private boolean flagSequence(int expected, int sequence) {
        return flag(V.write(verbose()).sint(expected).sint(sequence));
    }

    /** A new ClientLevel, whose BlockStatePredictionHandler starts at 0. */
    public void resetLevel() {
        lastSequence = 0;
        placedSequence = -1;
    }
}
