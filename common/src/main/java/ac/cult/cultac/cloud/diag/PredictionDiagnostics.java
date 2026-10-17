package ac.cult.cultac.cloud.diag;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.player.CultPlayer;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Diagnostics on a player's Java predictions: a watch hangs on their {@link SuperDebug}, which copies each prediction
 * into the watch's ring, and a cut is answered from that ring. A Bedrock player has no Java prediction to keep. Every
 * method runs on the player's event loop; the only per-player state is {@link SuperDebug#diag}.
 */
public final class PredictionDiagnostics {
    public static final PredictionDiagnostics INSTANCE = new PredictionDiagnostics();

    private PredictionDiagnostics() {}

    /**
     * The service watches {@code player} for {@code leaseSeconds} from the call, or stops at 0, on the socket of
     * {@code generation}; {@code frameSeq} reads the seq of the frame a prediction belongs to.
     */
    public void watch(
            CultPlayer player, Diagnostics diagnostics, long generation, int leaseSeconds, LongSupplier frameSeq) {
        if (player.isBedrockMovement()) {
            diagnostics.refuse(player.getUniqueId(), "bedrock");
            return;
        }
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        if (debug == null) {
            diagnostics.refuse(player.getUniqueId(), "busy");
            return;
        }
        DiagWatch watch = diagnostics.watch(
                player.getUniqueId(), generation, leaseSeconds, frameSeq, debug::history, debug::unwatch);
        if (watch == null || watch == debug.watch()) return;
        debug.watch(watch);
        if (watch.ended()) debug.unwatch(watch);
    }

    /** The service asks for a range of {@code player}'s prediction history; a Bedrock player's is refused. */
    public void cut(CultPlayer player, Diagnostics diagnostics, Cut cut) {
        if (player.isBedrockMovement()) diagnostics.refuse(player.getUniqueId(), "bedrock");
        String header;
        try {
            header = header(player);
        } catch (RuntimeException unreadable) {
            header = "";
        }
        diagnostics.cut(player.getUniqueId(), cut, header);
    }

    /** The checks that flagged the frame of seq {@code frameSeq}, in flag order. */
    public void tickEnd(CultPlayer player, long frameSeq, List<String> checks) {
        if (checks.isEmpty()) return;
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        if (debug == null || !(debug.watch() instanceof DiagWatch watch)) return;
        try {
            watch.tickEnd(frameSeq, checks);
        } catch (RuntimeException failure) {
            watch.failed(failure);
        }
    }

    /** {@code player}'s capture ended. */
    public void ended(CultPlayer player) {
        SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        if (debug != null && debug.watch() instanceof DiagWatch watch) watch.owner.left(watch.player());
    }

    private static String header(CultPlayer player) {
        return "agent " + CultAPI.INSTANCE.getExternalAPI().getGrimVersion() + ", client "
                + player.getClientVersion().getReleaseName() + ", brand " + player.getBrand() + ", ping "
                + player.getTransactionPing() + " ms";
    }
}
