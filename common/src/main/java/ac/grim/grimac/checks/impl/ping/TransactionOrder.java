package ac.grim.grimac.checks.impl.ping;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.player.GrimPlayer;

/** Flags transaction responses that skip pending transactions. */
@CheckData(name = "TransactionOrder", stableKey = "grim.ping.invalid_transaction_order", description = "Sent transaction or ping responses in an invalid order")
public class TransactionOrder extends Check implements CheckListener {
    public TransactionOrder(GrimPlayer player) {
        super(player);
    }

    public void skipped(int skipped) {
        if (skipped > 0) {
            flag("skipped=" + skipped);
        }
    }
}
