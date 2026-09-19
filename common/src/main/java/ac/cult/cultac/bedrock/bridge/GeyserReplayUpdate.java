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
            Map<String, Float> values = new LinkedHashMap<>();
            for (var value : attributes.getAttributes()) {
                if (switch (value.getName()) {
                    case "minecraft:movement", "minecraft:underwater_movement", "minecraft:lava_movement",
                         "minecraft:horse.jump_strength" -> true;
                    default -> false;
                }) values.put(value.getName(), Math.max(value.getMinimum(), Math.min(value.getMaximum(), value.getValue())));
            }
            return values.isEmpty() ? null : new GeyserReplayUpdate(attributes.getRuntimeEntityId(), attributes.getTick(),
                    new BedrockReplayContextEvent(values, null, null, -1, null, null));
        }
        if (packet instanceof MobEffectPacket effect && effect.getEvent() != MobEffectPacket.Event.NONE) {
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
}
