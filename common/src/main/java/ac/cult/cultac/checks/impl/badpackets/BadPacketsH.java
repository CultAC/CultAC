package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.api.storage.verbose.Verbose;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

@CheckData(
        name = "BadPacketsH",
        stableKey = "cult.badpackets.unexpected_sequence",
        description = "Sent unexpected sequence id",
        experimental = true)
public class BadPacketsH extends BlockPlaceCheck implements BlockBreakListener {
    private static final Verbose V = Verbose.of("expected={sint}, id={sint}");

    /** The client predicting in one ClientLevel, whose BlockStatePredictionHandler last sent this id. */
    private record History(int level, int last) {}

    // ClientLevels the client may still be in, oldest first. A newer level starts unused, at id 0.
    private final ArrayDeque<Integer> levels = new ArrayDeque<>(List.of(0));
    // Every history the ids so far fit; one id can continue several, and none is chosen.
    private List<History> histories = List.of(new History(0, 0));
    private int nextLevel = 1;
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
        var next = new ArrayList<History>(histories.size() + 1);
        for (History history : histories) {
            if (sequence == history.last() + 1) add(next, new History(history.level(), sequence));
            if (sequence != 1) continue;
            for (int level : levels) {
                if (level > history.level()) add(next, new History(level, 1));
            }
        }
        if (!next.isEmpty()) {
            histories = next;
            return false;
        }
        History newest = newest();
        boolean certain = histories.size() == 1 && newest.level() == levels.peekLast();
        histories = List.of(new History(levels.peekLast(), sequence));
        return flagSequence(newest.last() + 1, sequence) && certain && shouldModifyPackets();
    }

    private static void add(List<History> histories, History history) {
        if (!histories.contains(history)) histories.add(history);
    }

    private History newest() {
        History newest = histories.getFirst();
        for (History history : histories) {
            if (history.level() > newest.level() || history.level() == newest.level() && history.last() > newest.last())
                newest = history;
        }
        return newest;
    }

    private boolean flagSequence(int expected, int sequence) {
        return flag(V.write(verbose()).sint(expected).sint(sequence));
    }

    /** Starts a ClientLevel, created when the client handles the next packet sent; returns its id. */
    public int beginLevel() {
        int id = nextLevel++;
        levels.addLast(id);
        return id;
    }

    /**
     * The client acknowledged a ping sent after the level began, so it predicts in that level or a
     * newer one; a history still in an older level has entered it without sending an id.
     */
    public void levelProven(int id) {
        if (!levels.contains(id)) return;
        var next = new ArrayList<History>(histories.size());
        for (History history : histories) add(next, history.level() < id ? new History(id, 0) : history);
        histories = next;
        while (levels.peekFirst() < id) levels.removeFirst();
        placedSequence = -1;
    }

    /** A ClientLevel whose predecessor's packets have all arrived. */
    public void resetLevel() {
        int id = nextLevel++;
        levels.clear();
        levels.add(id);
        histories = List.of(new History(id, 0));
        placedSequence = -1;
    }
}
