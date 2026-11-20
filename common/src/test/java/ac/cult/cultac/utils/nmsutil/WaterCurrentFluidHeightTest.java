package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * A shallow current scales by the fluid height above the entity: 1.21.11 measures it from the deflated fluid box,
 * 26.1 and later from the bounding box.
 */
class WaterCurrentFluidHeightTest {
    private static final Vec3 FEET = new Vec3(0.5D, 64.0D, 0.5D);

    @Test
    void legacyClientsMeasureTheFluidHeightFromTheDeflatedBox() {
        Vec3 legacy = current(ProtocolVersion.V1_21_11);
        Vec3 modern = current(ProtocolVersion.V26_3);

        // Water at level 7 stands 1/9 above its block; the deflated box starts 0.001 higher.
        double height = 1.0D / 9.0D;
        assertEquals((height - 0.001D) / height, legacy.x / modern.x, 1.0E-6D);
        assertEquals(legacy.z / legacy.x, modern.z / modern.x, 1.0E-12D);
    }

    private static Vec3 current(ProtocolVersion client) {
        CultPlayer player = player(client);
        try {
            BlockRegistry registry = DataTables.defaults().registry();
            int water = registry.block("minecraft:water").defaultState();
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            player.compensatedWorld.updateBlock(1, 64, 0, water);
            player.compensatedWorld.updateBlock(0, 64, 0, registry.with(water, "level", "7"));

            SimulationContext context = mock(SimulationContext.class);
            when(context.getVehicle()).thenReturn(null);
            when(context.getPose()).thenReturn(Pose.STANDING);
            when(context.getScale()).thenReturn(1.0F);
            Vec3 current = WaterCurrent.calculateWaterCurrent(player, context, FEET, new Vec3(1.0D, 0.0D, 1.0D));
            assertNotNull(current, client.toString());
            return current;
        } finally {
            close(player);
        }
    }

    private static CultPlayer player(ProtocolVersion client) {
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
                return client;
            }

            @Override
            public ProtocolVersion getClientProtocol() {
                return client;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
        User user =
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Water_Current"), new EmbeddedChannel(), platform);
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
