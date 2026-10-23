package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.latency.LatencyUtils;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A remote player's relative move on a legacy client without the reach bundle delimiter: the transaction-bound movement queues one task for both of its
 * transactions, and the FairReach move applies once, at whichever of its transaction or its deadline comes first.
 */
class EntityMoveSchedulingTest {
    private static final int TARGET = 3000;

    @Test
    void aTransactionBoundMoveQueuesOneTaskForBothTransactions() {
        CultPlayer player = trackingPlayer();
        try {
            world(player);
            RecordingLatencyUtils latency = new RecordingLatencyUtils(player);
            player.latencyUtils = latency;
            moveAndAcknowledge(player, move(4096));
            latency.queued.clear();

            int first = foreignPing(player);
            send(player, move(-4096));
            Runnable movement = latency.queuedAt(first);
            acknowledge(player, first);

            assertSame(movement, latency.queuedAt(first + 1), "the second half reuses the first half's task");
        } finally {
            close(player);
        }
    }

    @Test
    void aFairReachMoveAppliesOnceAtItsTransactionOrItsDeadline() {
        CultPlayer player = trackingPlayer();
        EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
        try {
            FairReach fairReach = world(player);
            moveAndAcknowledge(player, move(4096));
            FairReach.FairReachEntity target = fairReach.targetPlayers.get(TARGET);

            int first = foreignPing(player);
            send(player, move(-4096));
            acknowledge(player, first);
            target.lerpTicks = 0;
            acknowledge(player, first + 1);
            assertEquals(3, target.lerpTicks, "the acknowledged transaction applies the move");
            target.lerpTicks = 0;
            channel.runScheduledPendingTasks();
            assertEquals(0, target.lerpTicks, "the deadline does not apply an applied move again");

            int next = foreignPing(player);
            send(player, move(4096));
            channel.runScheduledPendingTasks();
            assertEquals(3, target.lerpTicks, "the deadline applies an unacknowledged move");
            target.lerpTicks = 0;
            acknowledge(player, next + 1);
            assertEquals(0, target.lerpTicks, "the transaction does not apply an applied move again");
        } finally {
            close(player);
        }
    }

    private static FairReach world(CultPlayer player) {
        player.entityID = 7;
        player.x = 0;
        player.y = 64;
        player.z = 0;
        FairReach fairReach = player.checkManager.getListener(FairReach.class);
        fairReach.max_ping_compensation = 0;
        fairReach.onAddEntity(
                null, player, new ClientboundAddEntity(TARGET, "minecraft:player", new Vec3d(0, 64, 0), 0, 0, 0));
        player.packetEntityReplication.addEntity(TARGET, EntityTypeIds.PLAYER, new Vec3(0, 64, 0), 0, 0, 0, -1);
        // reach-use-bundle-delimiter: false proves each move with the transactions around it.
        try {
            Field delimiter = PacketEntityReplication.class.getDeclaredField("useReachBundleDelimiter");
            delimiter.setAccessible(true);
            delimiter.setBoolean(player.packetEntityReplication, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return fairReach;
    }

    private static void moveAndAcknowledge(CultPlayer player, ClientboundMoveEntity packet) {
        send(player, packet);
        acknowledge(player, foreignPing(player));
    }

    /** Another ping after everything sent so far, which the client has not acknowledged yet. */
    private static int foreignPing(CultPlayer player) {
        int transaction = Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()) + 1;
        player.lastTransactionSent.set(transaction);
        return transaction;
    }

    private static void send(CultPlayer player, ClientboundMoveEntity packet) {
        player.packetEntityReplication.onMoveEntity(
                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false),
                player,
                packet);
    }

    private static void acknowledge(CultPlayer player, int transaction) {
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static ClientboundMoveEntity move(int dx) {
        return new ClientboundMoveEntity(
                TARGET, new EntityDelta.Linear((short) dx, (short) 0, (short) 0), 0.0F, 0.0F, true, true, false);
    }

    private static final class RecordingLatencyUtils extends LatencyUtils {
        private final List<Object[]> queued = new ArrayList<>();

        private RecordingLatencyUtils(CultPlayer player) {
            super(player);
        }

        @Override
        public void addRealTimeTask(int transaction, Runnable task) {
            queued.add(new Object[] {transaction, task});
            super.addRealTimeTask(transaction, task);
        }

        private Runnable queuedAt(int transaction) {
            Runnable found = null;
            for (Object[] entry : queued) {
                if ((int) entry[0] != transaction
                        || entry[1].getClass().getName().contains("FairReach")) continue;
                assertNull(found, "one movement task per transaction");
                found = (Runnable) entry[1];
            }
            assertNotNull(found, "a movement task at transaction " + transaction);
            return found;
        }
    }

    private static CultPlayer trackingPlayer() {
        OfflineCultTestBootstrap.installConfig();
        PlatformConnection platform = new PlatformConnection() {
            @Override
            public User.Profile authenticatedProfile() {
                return null;
            }

            @Override
            public PlayerBinding playerBinding() {
                return null;
            }

            @Override
            public ProtocolVersion getObservedProtocol() {
                return ProtocolVersion.V1_21_11;
            }

            @Override
            public ProtocolVersion getClientProtocol() {
                return ProtocolVersion.V1_21_11;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
        User user =
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Move_Tasks"), new EmbeddedChannel(), platform);
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        return player;
    }

    private static void close(CultPlayer player) {
        Object channel = player.user.getChannel();
        player.onRemove();
        if (channel instanceof EmbeddedChannel embedded) {
            embedded.runPendingTasks();
            embedded.finishAndReleaseAll();
        }
    }
}
