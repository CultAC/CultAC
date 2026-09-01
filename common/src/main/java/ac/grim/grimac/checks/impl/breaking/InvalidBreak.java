package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

@CheckData(name = "InvalidBreak", stableKey = "grim.breaking.invalid_break", description = "Sent impossible block face id")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "26.2 PlayerAction decodes the dig face via Direction.from3DDataValue (abs(data % 6)); out-of-range values wrap and can never trigger the face check.")
public class InvalidBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("face={sint}, action={digging}");

    public InvalidBreak(GrimPlayer player) {
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
