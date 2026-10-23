package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ItemUseDesyncHistory;
import ac.grim.grimac.api.config.ConfigManager;

/** Movement policy for legal but persistently desynchronized use. Never calls Check.flag. */
public final class ItemUseDesync extends CultProcessor implements PostPredictionListener, ClientTickEndListener {
    private final ItemUseDesyncHistory history = new ItemUseDesyncHistory();
    private int pendingTick = -1;
    private Boolean pending;

    public ItemUseDesync(CultPlayer player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        history.configure(
                config.getIntElse("cult.prediction.item-use-desync.history-ticks", 100),
                config.getDoubleElse("cult.prediction.item-use-desync.max-percent", 20));
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (!player.usesModernJavaItemUse() || complete.isTeleport() || complete.isExempt()) return;
        var evidence = complete.getPredictionResult().getItemUseResult();
        double threshold = player.checkManager.getListener(NoSlow.class).threshold();
        if (evidence == null || !evidence.state().required() || evidence.deterministic(threshold)) return;
        boolean alreadyDesynced = pendingTick == evidence.state().tick() && Boolean.TRUE.equals(pending);
        pendingTick = evidence.state().tick();
        pending = alreadyDesynced || evidence.desynced(threshold);
        // Decide before SetbackTeleportUtil can promote this movement to its rollback
        // anchor. The accounting closes at tick-end and survives movement setbacks.
        // The rejected movement is a client tick, so the correction advances it from the anchor.
        if (pending && !alreadyDesynced && history.wouldExceed(true) && !player.noSetbackPermission)
            player.getSetbackTeleportUtil().executeViolationSetback();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (!player.usesModernJavaItemUse()) return;
        if (pending != null && pendingTick == player.packetStateData.acceptedClientTick) history.add(pending);
        pending = null;
    }

    public void reset() {
        pending = null;
        pendingTick = -1;
        history.clear();
    }

    public ItemUseDesyncHistory history() {
        return history;
    }
}
