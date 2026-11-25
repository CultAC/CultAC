package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.BackendName;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.player.CultPlayer;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Takes the profile name a bridged backend gives a player as their shown name while they are on that backend. */
public final class BackendNames implements BridgeMessageListener {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}|\\.[A-Za-z0-9_]{1,15}");

    private final BridgeSessions sessions;
    private final Function<UUID, @Nullable CultPlayer> players;

    public BackendNames(BridgeSessions sessions, Function<UUID, @Nullable CultPlayer> players) {
        this.sessions = sessions;
        this.players = players;
    }

    @Override
    public void onMessage(BridgeSession session, BridgeMessage message) {
        if (!(message instanceof BackendName reported) || !session.has(ServerCapability.BACKEND_NAME)) return;
        if (sessions.current(session.playerId()) != session) return;
        CultPlayer player = players.apply(session.playerId());
        if (player == null) return;
        String name = reported.name();
        player.backendName(NAME.matcher(name).matches() ? name : null);
    }

    @Override
    public void onClosed(BridgeSession session) {
        BridgeSession current = sessions.current(session.playerId());
        if (current != null && current != session) return;
        CultPlayer player = players.apply(session.playerId());
        if (player != null) player.backendName(null);
    }
}
