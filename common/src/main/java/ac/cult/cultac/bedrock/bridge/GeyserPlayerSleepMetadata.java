package ac.cult.cultac.bedrock.bridge;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;

public final class GeyserPlayerSleepMetadata {
    private GeyserPlayerSleepMetadata() { }

    public static Boolean sleeping(EntityDataMap metadata) {
        Byte flags = metadata.get(EntityDataTypes.PLAYER_FLAGS);
        return flags == null ? null : (flags & 2) != 0;
    }
}
