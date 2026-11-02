package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Received PlayerInfo identities and immutable modes, including references cached by remote players. */
public final class ClientPlayerModes {
    public record Entry(Object identity, GameMode mode, String name) {}

    private final Map<UUID, Entry> profiles = new HashMap<>();

    public Entry get(UUID id) {
        return profiles.get(id);
    }

    public void remove(List<UUID> ids) {
        ids.forEach(profiles::remove);
    }

    public void clear() {
        profiles.clear();
    }

    public void update(ClientboundPlayerInfoUpdate packet, Set<UUID> visibleModes, Iterable<PacketEntity> entities) {
        update(packet, visibleModes, entities, (id, mode) -> {});
    }

    public void update(ClientboundPlayerInfoUpdate packet, Set<UUID> visibleModes, Iterable<PacketEntity> entities,
            java.util.function.BiConsumer<UUID, GameMode> onModeChanged) {
        var actions = packet.actions();
        if (actions.contains(ClientboundPlayerInfoUpdate.Action.ADD_PLAYER))
            for (var entry : packet.entries())
                profiles.putIfAbsent(entry.profileId(), new Entry(new Object(), GameMode.SURVIVAL, name(entry)));
        if (!actions.contains(ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) return;
        for (var entry : packet.entries()) {
            var previous = profiles.get(entry.profileId());
            if (previous == null || !visibleModes.contains(entry.profileId())) continue;
            // ClientPacketListener#applyPlayerInfoUpdate invokes the local callback BEFORE storing the mode.
            // ADD_PLAYER creates a default SURVIVAL entry first, even when both actions share one packet.
            if (previous.mode() != entry.gameMode()) onModeChanged.accept(entry.profileId(), entry.gameMode());
            var next = new Entry(previous.identity(), entry.gameMode(), previous.name());
            profiles.put(entry.profileId(), next);
            for (var entity : entities)
                if (entity instanceof PacketEntityPlayer remote
                        && remote.cachedInfo != null
                        && remote.cachedInfo.identity() == previous.identity()) remote.cachedInfo = next;
        }
    }

    private static String name(ClientboundPlayerInfoUpdate.Entry entry) {
        var encoded = entry.encodedActions().get(ClientboundPlayerInfoUpdate.Action.ADD_PLAYER);
        if (encoded == null) return null;
        var buffer = io.netty.buffer.Unpooled.wrappedBuffer(encoded.bytes());
        try {
            return ac.cult.cultac.protocol.wire.Wire.readString(buffer, 16);
        } finally {
            buffer.release();
        }
    }
}
