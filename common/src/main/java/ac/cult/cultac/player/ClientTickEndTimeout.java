package ac.cult.cultac.player;

import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import java.util.concurrent.TimeUnit;

/** Closes one synthetic client tick after a minute without a tick end. Owned by the packet executor. */
public final class ClientTickEndTimeout {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final CheckManagerListener LISTENER = new CheckManagerListener();

    private final CultPlayer player;
    private boolean active = true;
    private long lastTickEndNanos = System.nanoTime();

    public ClientTickEndTimeout(CultPlayer player) {
        this.player = player;
    }

    private boolean enabled() {
        return player.user.getCultConnection().channel().isActive()
                && !player.isBedrockMovement()
                && player.user.getBedrockBridgeConnection() == null
                && player.user.getConnectionState() == ConnectionPhase.PLAY
                && player.user.getEncoderState() == ConnectionPhase.PLAY
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }

    /** Whether the watchdog needs an owner-thread task, including when play starts or stops. */
    public boolean needsPoll(long now) {
        boolean enabled = enabled();
        return enabled != active || (enabled && now - lastTickEndNanos >= TIMEOUT_NANOS);
    }

    public void onTickEnd(long now) {
        active = enabled();
        lastTickEndNanos = now;
    }

    public void poll(long now) {
        if (!enabled()) {
            active = false;
            return;
        }
        if (!active) {
            active = true;
            lastTickEndNanos = now;
            return;
        }
        if (now - lastTickEndNanos < TIMEOUT_NANOS) return;

        // This is an explicit resource-recovery boundary, not evidence of an actual client tick.
        // Run once per timeout, without replaying the wall-clock gap as client ticks or writing a packet.
        lastTickEndNanos = now;
        try {
            LISTENER.processClientTickEndReceive(
                    new PacketReceiveEvent<>(
                            player.user,
                            ConnectionPhase.PLAY,
                            ServerboundPackets.CLIENT_TICK_END,
                            ServerboundPackets.CLIENT_TICK_END.opaqueValue(),
                            true),
                    player);
        } finally {
            // The receive handler records its own clock; retain the watchdog's timestamp for this boundary.
            lastTickEndNanos = now;
            player.user.getCultConnection().clientTickEnded();
        }
    }
}
