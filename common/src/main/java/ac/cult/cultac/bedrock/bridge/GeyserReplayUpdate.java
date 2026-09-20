package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayContextEvent;
import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdatePlayerGameTypePacket;

/** Copies packet values before the outbound wrapper can be released. */
record GeyserReplayUpdate(long actorId, long tick, BedrockReplayEvent event) {
    static GeyserReplayUpdate capture(BedrockPacket packet) {
        if (packet instanceof UpdateAttributesPacket attributes) {
            return attributes(attributes.getRuntimeEntityId(), attributes.getTick(), attributes.getAttributes());
        }
        if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket spawn) {
            return attributes(spawn.getRuntimeEntityId(), 0, spawn.getAttributes());
        }
        if (packet instanceof MobEffectPacket effect && effect.getEvent() != MobEffectPacket.Event.NONE) {
            // Unmodeled effects must not replay an older movement snapshot.
            if (switch (effect.getEffectId()) {
                case 1, 2, 8, 15, 24, 27, 33 -> false;
                default -> true;
            }) return null;
            int level = effect.getEvent() == MobEffectPacket.Event.REMOVE ? 0 : effect.getAmplifier() + 1;
            return new GeyserReplayUpdate(effect.getRuntimeEntityId(), effect.getTick(),
                    new BedrockReplayContextEvent(Map.of(), effect.getEffectId(), level,
                            level == 0 ? -1 : effect.getDuration(), null, null));
        }
        if (packet instanceof UpdatePlayerGameTypePacket gameType) {
            return new GeyserReplayUpdate(gameType.getEntityId(), gameType.getTick(),
                    new BedrockReplayContextEvent(Map.of(), null, null, -1, gameType.getGameType().ordinal(), null));
        }
        return null;
    }
    private static GeyserReplayUpdate attributes(long actor, long tick,
            java.util.List<org.cloudburstmc.protocol.bedrock.data.AttributeData> attributes) {
        var values = new LinkedHashMap<String, ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState>();
        for (var value : attributes) values.put(value.getName(), GeyserMovementAttributeCodec.capture(value));
        return values.isEmpty() ? null : new GeyserReplayUpdate(actor, tick,
                new ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent(values, tick != 0));
    }
}
