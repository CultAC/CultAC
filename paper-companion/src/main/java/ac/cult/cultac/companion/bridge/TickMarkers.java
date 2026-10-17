package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Marker;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Answers MARKER frames at the end of the tick that reached them. The server corrects a click or held-slot change it
 * refuses while it handles the packet, and sends the menu's other changes at the player's tick
 * ({@code ServerPlayer#tick}); the answer goes out on the same connection after both.
 */
final class TickMarkers implements CompanionMessageHandler, Listener {
    /** Each session's latest marker the server has reached this tick. Main thread only. */
    private final Map<CompanionSession, Integer> reached = new LinkedHashMap<>();

    @Override
    public void onMessage(CompanionSession session, BridgeMessage message) {
        if (message instanceof Marker marker) session.runInPacketOrder(() -> reached.put(session, marker.marker()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        if (reached.isEmpty()) return;
        reached.forEach((session, marker) -> session.send(new Marker(marker)));
        reached.clear();
    }
}
