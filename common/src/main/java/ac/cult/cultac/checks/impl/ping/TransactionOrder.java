package ac.cult.cultac.checks.impl.ping;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

/** Flags transaction responses that skip pending transactions. */
@CheckData(name = "TransactionOrder", stableKey = "cult.ping.invalid_transaction_order", description = "Sent transaction or ping responses in an invalid order")
public class TransactionOrder extends Check implements CheckListener {
    public TransactionOrder(CultPlayer player) {
        super(player);
    }

    public void skipped(int skipped) {
        if (skipped > 0) {
            flag("skipped=" + skipped);
        }
    }
}
