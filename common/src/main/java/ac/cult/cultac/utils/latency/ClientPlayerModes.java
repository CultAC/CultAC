package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Received PlayerInfo identities and immutable modes, including references cached by remote players. */
public final class ClientPlayerModes {
    public record Entry(Object identity, GameMode mode) {}

    private final Map<UUID, Entry> profiles = new HashMap<>();

    public Entry get(UUID id) {
        return profiles.get(id);
    }

    public void remove(java.util.List<UUID> ids) {
        ids.forEach(profiles::remove);
    }

    public void clear() {
        profiles.clear();
    }

    public void update(ClientboundPlayerInfoUpdate packet, Set<UUID> visibleModes, Iterable<PacketEntity> entities) {
        var actions = packet.actions();
        if (actions.contains(ClientboundPlayerInfoUpdate.Action.ADD_PLAYER))
            for (var entry : packet.entries())
                profiles.putIfAbsent(entry.profileId(), new Entry(new Object(), GameMode.SURVIVAL));
        if (!actions.contains(ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) return;
        for (var entry : packet.entries()) {
            var previous = profiles.get(entry.profileId());
            if (previous == null || !visibleModes.contains(entry.profileId())) continue;
            var next = new Entry(previous.identity(), entry.gameMode());
            profiles.put(entry.profileId(), next);
            for (var entity : entities)
                if (entity instanceof PacketEntityPlayer remote
                        && remote.cachedInfo != null
                        && remote.cachedInfo.identity() == previous.identity()) remote.cachedInfo = next;
        }
    }
}
