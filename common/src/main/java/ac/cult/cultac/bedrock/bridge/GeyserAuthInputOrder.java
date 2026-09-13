package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Field;
import net.kyori.adventure.text.Component;
import org.geysermc.geyser.session.DownstreamSession;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;

/** Sends each captured frame after Geyser's actions, before movement or tick-end. */
final class GeyserAuthInputOrder extends DownstreamSession {
    private final GeyserSession connection;
    private final DownstreamSession delegate;
    private final Field downstreamField;
    private Packet pending;
    private long lastAuthInputTick = -1;
    private volatile boolean closed;

    GeyserAuthInputOrder(GeyserSession connection) {
        super(connection.getDownstream().getSession());
        this.connection = connection;
        this.delegate = connection.getDownstream();
        try {
            downstreamField = GeyserSession.class.getDeclaredField("downstream");
            downstreamField.setAccessible(true);
            downstreamField.set(connection, this);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to install Geyser auth-input ordering", failure);
        }
    }

    void submit(Packet payload, long tick) {
        runInEventLoop(() -> {
            if (closed) return;
            flush();
            pending = payload;
            lastAuthInputTick = tick;
        });
    }

    @Override
    public void sendPacket(Packet packet) {
        if (!closed && isMovementBoundary(packet)) flush();
        delegate.sendPacket(packet);
    }

    @Override public void disconnect(Component reason) { delegate.disconnect(reason); }
    @Override public void disconnect(Component reason, Throwable cause) { delegate.disconnect(reason, cause); }
    @Override public boolean isClosed() { return delegate.isClosed(); }

    void finishTranslation(long tick) {
        // Loading can omit both movement and tick-end; finish after the translator's writes.
        runInEventLoop(() -> {
            if (!closed && lastAuthInputTick == tick) flush();
        });
    }

    long lastAuthInputTick() {
        return lastAuthInputTick;
    }

    void close() {
        closed = true;
        runInEventLoop(() -> {
            pending = null;
            if (connection.getDownstream() == this) {
                try {
                    downstreamField.set(connection, delegate);
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException("Unable to restore Geyser downstream", failure);
                }
            }
        });
    }

    private void flush() {
        Packet payload = pending;
        pending = null;
        if (payload != null) delegate.sendPacket(payload);
    }

    private void runInEventLoop(Runnable task) {
        var loop = getSession().getChannel().eventLoop();
        if (loop.inEventLoop()) task.run();
        else loop.execute(task);
    }

    private static boolean isMovementBoundary(Packet packet) {
        return packet instanceof ServerboundMovePlayerPosPacket
                || packet instanceof ServerboundMovePlayerPosRotPacket
                || packet instanceof ServerboundMovePlayerRotPacket
                || packet instanceof ServerboundMovePlayerStatusOnlyPacket
                || packet instanceof ServerboundMoveVehiclePacket
                || packet instanceof ServerboundClientTickEndPacket;
    }
}
