package ac.cult.cultac.platform.velocity.bridge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.bridge.protocol.BridgeMessage.BackendName;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.player.CultPlayer;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BackendNamesTest {
    private static final UUID PLAYER = UUID.randomUUID();

    private final CultPlayer player = mock(CultPlayer.class);
    private final Map<UUID, BridgeSession> current = new HashMap<>();
    private final BackendNames names = new BackendNames(new BridgeSessions() {
        @Override
        public @Nullable BridgeSession current(UUID id) {
            return current.get(id);
        }

        @Override
        public void addListener(BridgeMessageListener listener) {}

        @Override
        public void removeListener(BridgeMessageListener listener) {}
    }, id -> id.equals(PLAYER) ? player : null);

    @Test
    void theCurrentSessionsNameIsTakenUntilItCloses() {
        FakeBridgeSession lobby = new FakeBridgeSession(PLAYER, "lobby");
        current.put(PLAYER, lobby);

        names.onMessage(lobby, new BackendName("Vk4817"));
        verify(player).backendName("Vk4817");
        names.onMessage(lobby, new BackendName("not a name"));
        verify(player).backendName(null);

        names.onClosed(lobby);
        verify(player, times(2)).backendName(null);
    }

    @Test
    void aSessionThatIsNotThePlayersCurrentOneIsIgnored() {
        FakeBridgeSession old = new FakeBridgeSession(PLAYER, "lobby");
        current.put(PLAYER, new FakeBridgeSession(PLAYER, "game"));

        names.onMessage(old, new BackendName("Vk4817"));
        names.onClosed(old);
        verify(player, never()).backendName(any());
    }

    @Test
    void aCompanionWithoutTheCapabilityIsIgnored() {
        FakeBridgeSession older = new FakeBridgeSession(
                PLAYER, "lobby", EnumSet.complementOf(EnumSet.of(ServerCapability.BACKEND_NAME)), 0);
        current.put(PLAYER, older);

        names.onMessage(older, new BackendName("Vk4817"));
        verify(player, never()).backendName(any());
    }
}
