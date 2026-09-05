package ac.cult.cultac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

@CheckData(name = "InvalidBreak", stableKey = "cult.breaking.invalid_break", description = "Sent impossible block face id")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "26.2 PlayerAction decodes the dig face via Direction.from3DDataValue (abs(data % 6)); out-of-range values wrap and can never trigger the face check.")
public class InvalidBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("face={sint}, action={digging}");

    public InvalidBreak(CultPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.faceId == 255 && blockBreak.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK && player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_7_10)) { // PE DiggingAction.CANCELLED_DIGGING
            return;
        }

        if (blockBreak.faceId < 0 || blockBreak.faceId > 5) {
            // ban
            if (flag(V.write(verbose())
                    .sint(blockBreak.faceId)
                    .uint(VerboseTags.enumId(blockBreak.action))) && shouldModifyPackets()) {
                blockBreak.cancel();
            }
        }
    }
}
