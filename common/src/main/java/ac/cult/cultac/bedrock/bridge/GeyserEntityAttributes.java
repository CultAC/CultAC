package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent;
import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import org.geysermc.geyser.session.GeyserSession;

/** Final wire values cross the same receipt and Java-spawn barriers as entity transforms. */
final class GeyserEntityAttributes {
    // Updates written since the last latency marker; the next marker proves them.
    private ArrayList<Runnable> pending = new ArrayList<>();

    void capture(GeyserSession session, CultPlayer player, GeyserReplayUpdate update) {
        if (!(update.event() instanceof BedrockReplayAttributeEvent captured)) return;
        long runtimeId = update.actorId();
        boolean self = runtimeId == session.getPlayerEntity().geyserId();
        // The jump strength instance launches a ridden horse. It is also written for the
        // player, whose jump does not follow it, so it never enters the player's attributes.
        var attributes = self ? withoutHorseJumpStrength(captured) : captured;
        if (attributes.attributes().isEmpty()) return;
        var translated =
                self ? session.getPlayerEntity() : session.getEntityCache().getEntityByGeyserId(runtimeId);
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
    }

    private static BedrockReplayAttributeEvent withoutHorseJumpStrength(BedrockReplayAttributeEvent event) {
        if (!event.attributes().containsKey("minecraft:horse.jump_strength")) return event;
        var kept = new java.util.LinkedHashMap<>(event.attributes());
        kept.remove("minecraft:horse.jump_strength");
        return new BedrockReplayAttributeEvent(kept, event.historical());
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    void boundary(CultPlayer player, CultPlayer.BedrockTransaction transaction) {
        if (pending.isEmpty() || transaction == null) return;
        var batch = pending;
        pending = new ArrayList<>();
        player.addBedrockTransactionTask(transaction, () -> batch.forEach(Runnable::run));
    }

    void clear() {
        pending.clear();
    }
}
