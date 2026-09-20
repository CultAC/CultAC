package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent;
import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import org.geysermc.geyser.session.GeyserSession;

/** Final wire values cross the same receipt and Java-spawn barriers as entity transforms. */
final class GeyserEntityAttributes {
    private final ArrayList<Runnable> pending = new ArrayList<>();

    void capture(GeyserSession session, CultPlayer player, GeyserReplayUpdate update) {
        if (!(update.event() instanceof BedrockReplayAttributeEvent attributes)) return;
        long runtimeId = update.actorId();
        boolean self = runtimeId == session.getPlayerEntity().geyserId();
        var translated = self ? session.getPlayerEntity() : session.getEntityCache().getEntityByGeyserId(runtimeId);
        if (translated == null) return;
        int javaId = translated.getEntityId();
        var tracked = self ? null : player.compensatedEntities.getTrackedEntity(javaId);
        if (!self && tracked == null) return;
        int creationTransaction = self ? 0 : tracked.getLastTransactionHung();
        pending.add(() -> player.latencyUtils.addRealTimeTask(creationTransaction, () -> {
            var entity = self ? player.compensatedEntities.getSelf() : player.compensatedEntities.getEntity(javaId);
            if (entity == null || entity.bedrockRuntimeId != runtimeId) return;
            entity.bedrockAttributes = entity.bedrockAttributes.replace(attributes.attributes());
            player.bedrockState.movementCorrections.attributes(player, entity, update.tick(), attributes);
        }));
        if (pending.size() > 8192) throw new IllegalStateException("Attribute updates lack a latency boundary");
    }

    void boundary(CultPlayer player, CultPlayer.BedrockTransaction transaction) {
        if (pending.isEmpty() || transaction == null) return;
        var batch = java.util.List.copyOf(pending);
        pending.clear();
        player.addBedrockTransactionTask(transaction, () -> batch.forEach(Runnable::run));
    }

    void clear() { pending.clear(); }
}
