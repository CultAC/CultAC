package ac.cult.cultac.manager.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.manager.player.setback.SetbackReason;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** A setback asked for while the client has no level (configuration, or play before its login) is dropped. */
class SetbackOutsidePlayTest {
    private static final Vec3 SAFE = new Vec3(4, 64, 2);

    @Test
    void aLatencySetbackInPlayIsSent() {
        withPlayer(player -> {
            assertTrue(player.getSetbackTeleportUtil().executeTooHighLatencySetback("kb 1200ms"));
            assertNotNull(player.getSetbackTeleportUtil().getRequiredSetBack());
        });
    }

    @Test
    void aLatencySetbackDuringConfigurationIsDropped() {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            connection.placedClientbound(ClientboundPackets.START_CONFIGURATION);
            new PacketPlayerRespawn().onStartConfiguration(null, player, null);
            assertFalse(connection.clientHasLevel());

            player.getSetbackTeleportUtil().executeTooHighLatencySetback("kb 1200ms");
            player.getSetbackTeleportUtil().executeViolationSetback();
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();

            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
        });
    }

    @Test
    void aSetbackRequestedWithoutALevelIsNeverRequired() {
        withPlayer(player -> {
            player.user.getCultConnection().placedClientbound(ClientboundPackets.START_CONFIGURATION);

            player.getSetbackTeleportUtil().executeTooHighLatencySetback("kb 1200ms");

            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
        });
    }

    @Test
    void aResyncRequestedWithoutALevelDoesNotBlockMovement() {
        withPlayer(player -> {
            player.user.getCultConnection().placedClientbound(ClientboundPackets.START_CONFIGURATION);

            player.getSetbackTeleportUtil().executeForceResync("phase");

            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertFalse(player.getSetbackTeleportUtil().isBlockingOffsets(), "no teleport is tracked to release it");
        });
    }

    @Test
    void leavingTheLevelReleasesAResyncBlock() {
        withPlayer(player -> {
            player.getSetbackTeleportUtil().executeForceResync("phase");
            assertTrue(player.getSetbackTeleportUtil().isBlockingOffsets());

            player.user.getCultConnection().placedClientbound(ClientboundPackets.START_CONFIGURATION);
            new PacketPlayerRespawn().onStartConfiguration(null, player, null);

            assertFalse(player.getSetbackTeleportUtil().isBlockingOffsets());
        });
    }

    @Test
    void aSetbackQueuedInPlayIsNotSentOnceTheClientHasLeftItsLevel() {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            Hold hold = new Hold();
            channel.pipeline().addLast("hold", hold);
            drain(channel);

            player.getSetbackTeleportUtil().executeTooHighLatencySetback("kb 1200ms");
            assertNotNull(player.getSetbackTeleportUtil().getRequiredSetBack(), "queued at its outbound position");
            assertFalse(hold.held.isEmpty());

            connection.placedClientbound(ClientboundPackets.START_CONFIGURATION);
            new PacketPlayerRespawn().onStartConfiguration(null, player, null);
            hold.release();
            channel.runPendingTasks();

            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertEquals(0, drain(channel), "the queued setback wrote nothing");

            connection.placedClientbound(ClientboundPackets.LOGIN);
            channel.runPendingTasks();
            assertEquals(0, drain(channel), "nor after the next login");
            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
        });
    }

    @Test
    void aSetbackQueuedInPlayDoesNotBlockTheNextPlaySessionOnceWritesLeavePlay() {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            Hold hold = new Hold();
            channel.pipeline().addLast("hold", hold);
            drain(channel);

            player.getSetbackTeleportUtil().executeTooHighLatencySetback("kb 1200ms");
            assertNotNull(player.getSetbackTeleportUtil().getRequiredSetBack(), "queued at its outbound position");

            connection.placedClientbound(ClientboundPackets.START_CONFIGURATION);
            connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
            new PacketPlayerRespawn().onStartConfiguration(null, player, null);
            hold.release();
            channel.runPendingTasks();

            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
            assertEquals(0, drain(channel), "the queued setback wrote nothing");
        });
    }

    @Test
    void aQueuedSetbackIsReportedWhenItsTeleportIsWritten() {
        withPlayer(player -> {
            Setbacks reports = new Setbacks();
            player.observer = reports;
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            Hold hold = new Hold();
            channel.pipeline().addLast("hold", hold);
            drain(channel);

            player.getSetbackTeleportUtil().executeTooHighLatencySetback("kb 1200ms");
            hold.release();
            channel.runPendingTasks();

            assertEquals(List.of("LATENCY kb 1200ms"), reports.sent);
        });
    }

    @Test
    void aSetbackThatIsNeverWrittenIsNotReported() {
        withPlayer(player -> {
            Setbacks reports = new Setbacks();
            player.observer = reports;
            CultConnection connection = player.user.getCultConnection();
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            Hold hold = new Hold();
            channel.pipeline().addLast("hold", hold);
            drain(channel);

            player.getSetbackTeleportUtil().executeTooHighLatencySetback("kb 1200ms");
            connection.placedClientbound(ClientboundPackets.START_CONFIGURATION);
            new PacketPlayerRespawn().onStartConfiguration(null, player, null);
            hold.release();
            channel.runPendingTasks();
            player.getSetbackTeleportUtil().executeForceResync("phase");
            player.getSetbackTeleportUtil().executeViolationSetback();
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            channel.runPendingTasks();

            assertEquals(0, drain(channel), "a setback was written");
            assertEquals(List.of(), reports.sent);
        });
    }

    private static final class Setbacks implements PlayerObserver {
        final List<String> sent = new ArrayList<>();

        @Override
        public void setbackSent(SetbackReason reason, String cause, Check check) {
            sent.add(reason + " " + cause);
        }
    }

    /** Holds writes until released, so a queued write runs after what the test does in between. */
    private static final class Hold extends ChannelOutboundHandlerAdapter {
        final List<Object> held = new ArrayList<>();
        final List<ChannelPromise> promises = new ArrayList<>();
        ChannelHandlerContext context;

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            context = ctx;
            held.add(message);
            promises.add(promise);
        }

        void release() {
            for (int i = 0; i < held.size(); i++) context.write(held.get(i), promises.get(i));
            held.clear();
            promises.clear();
            context.flush();
            context.pipeline().remove(this);
        }
    }

    private static int drain(EmbeddedChannel channel) {
        int frames = 0;
        Object written;
        while ((written = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(written);
            frames++;
        }
        return frames;
    }

    private static void withPlayer(Consumer<CultPlayer> body) {
        OfflinePlayers.withSafePlayer(SAFE, player -> {
            assertTrue(player.user.getCultConnection().clientHasLevel());
            body.accept(player);
        });
    }
}
