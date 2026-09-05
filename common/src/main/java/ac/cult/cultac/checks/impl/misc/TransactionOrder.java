package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.player.CultPlayer;

@CheckData(name = "TransactionOrder", stableKey = "cult.ping.invalid_transaction_order", description = "Sent transaction or ping responses in an invalid order")
@DeadCheck(reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION, detail = "Empty stub; never constructed (ping/TransactionOrder is the live check).")
public class TransactionOrder extends Check {
    public TransactionOrder(CultPlayer player) {
        super(player);
    }
}
