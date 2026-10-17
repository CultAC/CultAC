package ac.cult.cultac.checks.impl.breaking;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(
        name = "PositionBreakB",
        stableKey = "cult.breaking.position_break_b",
        description = "Cancelled block breaking with an invalid block face")
public class PositionBreakB extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("lastFace={face}, action={digging}");

    private Direction lastFace;

    public PositionBreakB(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == PlayerAction.START_DESTROY_BLOCK) { // PE DiggingAction.START_DIGGING
            if (blockBreak.face == lastFace) {
                lastFace = null;
            }
        }

        if (lastFace != null) {
            flag(V.write(verbose()).uint(VerboseTags.enumId(lastFace)).uint(VerboseCodecs.digging(blockBreak.action)));
        }

        if (blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK) { // PE DiggingAction.CANCELLED_DIGGING
            lastFace = blockBreak.faceId == 0 ? null : blockBreak.face;
        }
    }
}
