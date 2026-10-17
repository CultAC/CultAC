package ac.cult.cultac.companion;

import ac.cult.cultac.companion.bridge.CompanionBridge;
import ac.cult.cultac.companion.bridge.CompanionMessageHandler;
import ac.cult.cultac.companion.bridge.CompanionSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Serves the sessions opened through it and records handler and listener registrations. */
public final class FakeBridge implements CompanionBridge {
    public final List<Integer> listenerBits = new ArrayList<>();
    public final List<CompanionMessageHandler> handlers = new ArrayList<>();
    private final Map<UUID, FakeSession> sessions = new HashMap<>();
    private final List<FakeSession> order = new ArrayList<>();

    public FakeSession open(UUID player) {
        FakeSession session = new FakeSession(player);
        sessions.put(player, session);
        order.add(session);
        return session;
    }

    @Override
    public @Nullable CompanionSession session(UUID player) {
        return sessions.get(player);
    }

    @Override
    public @Nullable CompanionSession anySession() {
        return order.isEmpty() ? null : order.getFirst();
    }

    @Override
    public void addHandler(CompanionMessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void setListenerBits(int listenerKindBits) {
        listenerBits.add(listenerKindBits);
    }

    @Override
    public void addCapabilities(long capabilityMask) {}
}
