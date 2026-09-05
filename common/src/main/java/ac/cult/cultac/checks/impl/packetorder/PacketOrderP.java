package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.player.CultPlayer;

/**
 * Retained only so historical configuration and verbose identities remain
 * recognizable. Modern Cult 2.0 commented this check out of CheckManager, so
 * the NMS port must not manufacture packet subscriptions for it.
 */
@CheckData(name = "PacketOrderP", stableKey = "cult.packetorder.transaction_response_order", description = "Responded to chunk batch packets in an invalid transaction order", experimental = true)
@DeadCheck(reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION, detail = "Deliberately never registered (modern 2.0 commented out its registration).")
public final class PacketOrderP extends Check {
    public PacketOrderP(final CultPlayer player) {
        super(player);
    }
}
