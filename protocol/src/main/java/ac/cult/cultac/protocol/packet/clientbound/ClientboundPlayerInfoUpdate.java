package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.ByteArray;
import ac.cult.cultac.protocol.value.GameMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ClientboundPlayerInfoUpdate(Set<Action> actions, List<Entry> entries) implements ClientboundPacket {
    public ClientboundPlayerInfoUpdate {
        var copy = java.util.EnumSet.noneOf(Action.class);
        copy.addAll(actions);
        actions = Collections.unmodifiableSet(copy);
        entries = List.copyOf(entries);
    }

    public enum Action {
        ADD_PLAYER,
        INITIALIZE_CHAT,
        UPDATE_GAME_MODE,
        UPDATE_LISTED,
        UPDATE_LATENCY,
        UPDATE_DISPLAY_NAME,
        UPDATE_LIST_ORDER,
        UPDATE_HAT
    }

    /**
     * Only the UUID and game mode are consumed. Other action bodies
     * stay encoded so spectator filtering can retain them without interpreting them.
     */
    public record Entry(UUID profileId, GameMode gameMode, Map<Action, ByteArray> encodedActions) {
        public Entry {
            Objects.requireNonNull(profileId);
            Objects.requireNonNull(gameMode);
            var copy = new EnumMap<Action, ByteArray>(Action.class);
            encodedActions.forEach((action, bytes) -> copy.put(action, Objects.requireNonNull(bytes)));
            if (copy.containsKey(Action.UPDATE_GAME_MODE))
                throw new IllegalArgumentException("Game mode is not opaque");
            encodedActions = Collections.unmodifiableMap(copy);
        }
    }
}
