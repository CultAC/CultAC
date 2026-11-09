package ac.cult.cultac.events.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Entity movements are reused only once their proof has run them. */
class ProofTaskReuseTest {
    private static final int ENTITY = 4000;
    private static final int UNKNOWN = 5000;

    @Test
    void aQueuedMovementIsReusableOnlyAfterItsProofRuns() throws Exception {
        CultPlayer player = player();
        try {
            ping(player);
            move(player, ENTITY);
            assertEquals(0, free(player), "the transaction queue holds the movement");
            acknowledge(player);
            assertEquals(1, free(player));
            Object released = firstFree(player);

            ping(player);
            move(player, ENTITY);
            assertEquals(0, free(player), "the next movement takes the released one");
            acknowledge(player);
            assertSame(released, firstFree(player));
        } finally {
            close(player);
        }
    }

    @Test
    void tasksThatNeverQueueAreReusableAtOnce() throws Exception {
        CultPlayer player = player();
        try {
            player.lastTransactionReceived.set(player.lastTransactionSent.get() + 2);
            move(player, ENTITY);
            assertEquals(1, free(player), "an answered proof runs the movement in place");
            ping(player);
            move(player, UNKNOWN);
            assertEquals(1, free(player), "a move of an unknown entity is discarded");
        } finally {
            close(player);
        }
    }

    @Test
    void reuseIsBounded() throws Exception {
        CultPlayer player = player();
        try {
            for (int i = 0; i < 100; i++) {
                ping(player);
                move(player, ENTITY);
            }
            assertEquals(0, free(player));
            assertEquals(64, (int) field(player, "filledMovementSlots"));
            acknowledge(player);
            assertEquals(64, free(player));
        } finally {
            close(player);
        }
    }

    private static void ping(CultPlayer player) {
        player.lastTransactionSent.set(
                Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()) + 1);
    }

    private static void acknowledge(CultPlayer player) {
        int transaction = Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get());
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static void move(CultPlayer player, int id) {
        var packet = new ClientboundMoveEntity(
                id, new EntityDelta.Linear((short) 1024, (short) 0, (short) 0), 0.0F, 0.0F, true, true, false);
        player.packetEntityReplication.onMoveEntity(
                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false),
                player,
                packet);
    }

    private static int free(CultPlayer player) throws ReflectiveOperationException {
        return Long.bitCount((long) field(player, "freeMovementSlots"));
    }

    private static Object firstFree(CultPlayer player) throws ReflectiveOperationException {
        long free = (long) field(player, "freeMovementSlots");
        return ((Object[]) field(player, "movementSlots"))[Long.numberOfTrailingZeros(free)];
    }

    private static Object field(CultPlayer player, String name) throws ReflectiveOperationException {
        Field field = PacketEntityReplication.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(player.packetEntityReplication);
    }

    private static CultPlayer player() {
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
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Proof_Reuse"), new EmbeddedChannel(), platform);
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        player.entityID = 7;
        player.packetEntityReplication.addEntity(ENTITY, EntityTypeIds.ZOMBIE, new Vec3(0.5, 64, 0.5), 0, 0, 0, -1);
        acknowledge(player);
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
