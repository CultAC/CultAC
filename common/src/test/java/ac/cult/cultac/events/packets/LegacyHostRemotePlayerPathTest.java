package ac.cult.cultac.events.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client behind ViaVersion on a 1.21.11 host: the host sends legacy relative moves and linear position syncs,
 * and Via's {@code EntityPacketRewriter26_3} delivers each positioned move and sync as one step of 3 ticks, which the
 * client's {@code SteppedInterpolationHandler} interpolates with its own registry's player update interval (2).
 */
class LegacyHostRemotePlayerPathTest {
    private static final int ID = 3000;

    @Test
    void legacyRelativeMoveAndPositionSyncInterpolateAsTheViaSteppedPath() throws Exception {
        CultPlayer player = player();
        try {
            player.packetEntityReplication.addEntity(ID, EntityTypeIds.PLAYER, new Vec3(0, 64, 0), 0, 0, 0, -1);
            PacketEntity entity = player.compensatedEntities.getEntity(ID);
            assertNotNull(entity);
            for (int tick = 0; tick < 5; tick++) entity.onMovement(player, true);

            var move = new ClientboundMoveEntity(
                    ID, new EntityDelta.Linear((short) 4096, (short) 0, (short) 0), 0.0F, 0.0F, true, true, false);
            var moveEvent = event(player, ClientboundPackets.MOVE_ENTITY, move);
            player.packetEntityReplication.onMoveEntity(moveEvent, player, move);
            acknowledge(player, moveEvent);
            // InterpolationData#getNewPositionAndRotation over the step [x 1, 3 ticks] with interpolationSteps 2:
            // 1/3, then 3/4 after InterpolationData#advance moves 1.25 ticks, then the step's end.
            assertTicks(player, entity, 0.0D, 1.0D, "move");

            var sync = new ClientboundEntityPositionSync(
                    ID, new PositionPath.Linear(new Vec3d(2, 64, 0)), 0.0F, 0.0F, true);
            var syncEvent = event(player, ClientboundPackets.ENTITY_POSITION_SYNC, sync);
            player.packetEntityReplication.onEntityPositionSync(syncEvent, player, sync);
            acknowledge(player, syncEvent);
            assertTicks(player, entity, 1.0D, 2.0D, "sync");
        } finally {
            close(player);
        }
    }

    private static void assertTicks(CultPlayer player, PacketEntity entity, double from, double to, String what) {
        float[] samples = {1.0F / 3.0F, 2.25F / 3.0F, 1.0F};
        for (int tick = 0; tick < samples.length; tick++) {
            entity.onMovement(player, true);
            assertEquals(
                    from + (to - from) * samples[tick],
                    entity.clientPhysicalPosition.x,
                    1e-9,
                    what + " tick " + (tick + 1));
        }
    }

    private static void acknowledge(CultPlayer player, PacketSendEvent<?> event) {
        event.tasksAfter().forEach(Runnable::run);
        for (var write : event.writesAfter()) {
            if (write.packet() instanceof ClientboundPing ping) assertTrue(player.addTransactionResponse(ping.id()));
        }
    }

    private static <R extends ClientboundPacket> PacketSendEvent<R> event(
            CultPlayer player, PacketType<R> type, R packet) {
        return new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, type, packet, false);
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
                return ProtocolVersion.V26_3;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
        User user =
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Legacy_Host"), new EmbeddedChannel(), platform);
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
