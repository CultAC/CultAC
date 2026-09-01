package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.player.GrimPlayer;

/**
 * Retained only so historical configuration and verbose identities remain
 * recognizable. Modern Grim 2.0 commented this check out of CheckManager, so
 * the NMS port must not manufacture packet subscriptions for it.
 */
@CheckData(name = "PacketOrderP", stableKey = "grim.packetorder.transaction_response_order", description = "Responded to chunk batch packets in an invalid transaction order", experimental = true)
@DeadCheck(reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION, detail = "Deliberately never registered (modern 2.0 commented out its registration).")
public final class PacketOrderP extends Check {
    public PacketOrderP(final GrimPlayer player) {
        super(player);
    }
}
