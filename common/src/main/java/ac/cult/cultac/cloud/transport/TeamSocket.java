package ac.cult.cultac.cloud.transport;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One open intake socket whose connect offered team sync, for as long as it stays open. */
public interface TeamSocket {
    /** The team sync version connect offers. */
    int VERSION = 1;

    /** Queues a {@code TeamRoles} record; false when the socket is gone or too much is queued, and it was dropped. */
    boolean send(byte[] record);

    String serverId();

    /** What the team sockets hear. Every method only hands off: none may block or check permissions. */
    interface Listener {
        Listener NONE = new Listener() {
            @Override
            public void opened(TeamSocket socket, @Nullable Boolean enabled) {}

            @Override
            public void state(TeamSocket socket, boolean enabled) {}

            @Override
            public void sweep(TeamSocket socket, List<UUID> players) {}

            @Override
            public void closed(TeamSocket socket) {}
        };

        /** The socket opened; {@code enabled} is connect's {@code team} answer, null when the service took none. */
        void opened(TeamSocket socket, @Nullable Boolean enabled);

        /** A {@code team_state} reply. */
        void state(TeamSocket socket, boolean enabled);

        /** A {@code team_sweep} reply. */
        void sweep(TeamSocket socket, List<UUID> players);

        /** A socket {@link #opened} with an answer closed. */
        void closed(TeamSocket socket);
    }
}
