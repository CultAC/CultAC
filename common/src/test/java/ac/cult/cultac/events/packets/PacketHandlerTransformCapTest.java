package ac.cult.cultac.events.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** Packet-handler transforms for distinct entity ids stay bounded however long the client goes without a tick end. */
class PacketHandlerTransformCapTest {
    private static final int CAP = 1024;

    @Test
    void stalledClientKeepsAtMostTheHardCap() throws Exception {
        CultPlayer player = player();
        try {
            for (int entityId = 1; entityId <= PacketEntityReplication.MAX_TRANSFORMS * 3; entityId++)
                record(player, entityId);

            assertTrue(
                    transforms(player).size() <= PacketEntityReplication.MAX_TRANSFORMS,
                    "size " + transforms(player).size());
        } finally {
            close(player);
        }
    }

    @Test
    void moreThanTheReuseCapOfEntitiesMovingInOneTickAreAllKept() throws Exception {
        CultPlayer player = player();
        try {
            int moved = CAP * 4;
            for (int entityId = 1; entityId <= moved; entityId++) record(player, entityId);

            assertEquals(moved, transforms(player).size());
            for (int entityId = 1; entityId <= moved; entityId++)
                assertTrue(transforms(player).containsKey(entityId));
        } finally {
            close(player);
        }
    }

    @Test
    void insertionAtTheCapDropsOnlyEarlierTicks() throws Exception {
        CultPlayer player = player();
        try {
            for (int entityId = 1; entityId < CAP; entityId++) record(player, entityId);
            Field tick = PacketEntityReplication.class.getDeclaredField("transformTick");
            tick.setAccessible(true);
            tick.setLong(player.packetEntityReplication, tick.getLong(player.packetEntityReplication) + 1);

            record(player, 5000);
            record(player, 5001);

            assertEquals(2, transforms(player).size());
            assertTrue(transforms(player).containsKey(5000));
            assertTrue(transforms(player).containsKey(5001));
        } finally {
            close(player);
        }
    }

    private static void record(CultPlayer player, int entityId) throws Exception {
        Method record = PacketEntityReplication.class.getDeclaredMethod(
                "recordPacketHandlerTransform",
                int.class,
                boolean.class,
                double.class,
                double.class,
                double.class,
                boolean.class,
                float.class,
                float.class);
        record.setAccessible(true);
        record.invoke(player.packetEntityReplication, entityId, true, 1.0D, 64.0D, 1.0D, true, 0.0F, 0.0F);
    }

    private static Int2ObjectOpenHashMap<?> transforms(CultPlayer player) throws Exception {
        Field field = PacketEntityReplication.class.getDeclaredField("packetHandlerEntityTransforms");
        field.setAccessible(true);
        return (Int2ObjectOpenHashMap<?>) field.get(player.packetEntityReplication);
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
                return ProtocolVersion.V26_3;
            }

            @Override
            public ProtocolVersion getClientProtocol() {
                return ProtocolVersion.V26_3;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
        User user =
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Transform_Cap"), new EmbeddedChannel(), platform);
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        return player;
    }

    private static void close(CultPlayer player) {
        Object channel = player.user.getChannel();
        player.onRemove();
        if (channel instanceof EmbeddedChannel embedded) {
            embedded.runPendingTasks();
            embedded.runScheduledPendingTasks();
            embedded.finishAndReleaseAll();
        }
    }
}
