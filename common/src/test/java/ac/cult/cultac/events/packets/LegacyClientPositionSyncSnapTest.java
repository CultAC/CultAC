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
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleEntityPositionSync on a pre-26.3 client from 1.21.2 on snaps a ticking entity to a
 * position more than 64 blocks from where the client shows it, and interpolates a nearer one. A 1.21.1 client gets
 * the sync as a teleport, which ClientPacketListener#handleTeleportEntity always interpolates.
 */
class LegacyClientPositionSyncSnapTest {
    private static final int ID = 3000;

    @Test
    void farSyncSnaps() throws Exception {
        CultPlayer player = player();
        try {
            PacketEntity entity = syncedFrom(player, new Vec3d(20, 100, 6));
            entity.onMovement(player, true);
            assertEquals(20.0D, entity.clientPhysicalPosition.x, 0.0D);
            assertEquals(100.0D, entity.clientPhysicalPosition.y, 0.0D);
            SimpleCollisionBox possible = entity.getPossibleCollisionBoxes();
            assertTrue(possible.minY > 99.0D, possible.toString());
        } finally {
            close(player);
        }
    }

    /**
     * Entity#snapTo leaves the InterpolationHandler running: its next interpolate shifts the target by the jump from
     * the position its last step left, then lerps on from the synced position.
     */
    @Test
    void farSyncKeepsTheInterpolationItInterrupts() throws Exception {
        CultPlayer player = player();
        try {
            PacketEntity entity = added(player);
            sync(player, new Vec3d(0, 37, 0.9));
            entity.onMovement(player, true);
            assertEquals(0.3D, entity.clientPhysicalPosition.z, 1.0E-9D);

            sync(player, new Vec3d(20, 100, 6));
            entity.onMovement(player, true);
            assertEquals(20.0D, entity.clientPhysicalPosition.x, 1.0E-9D);
            assertEquals(100.0D, entity.clientPhysicalPosition.y, 1.0E-9D);
            assertEquals(6.3D, entity.clientPhysicalPosition.z, 1.0E-9D);
            entity.onMovement(player, true);
            assertEquals(6.6D, entity.clientPhysicalPosition.z, 1.0E-9D);
            entity.onMovement(player, true);
            assertEquals(6.6D, entity.clientPhysicalPosition.z, 1.0E-9D);
        } finally {
            close(player);
        }
    }

    /**
     * Before 1.21.5, Entity#moveTo leaves LivingEntity's lerp target where it was, and LivingEntity#aiStep lerps from
     * the synced position toward it.
     */
    @Test
    void farSyncBefore1215LerpsTowardTheUnshiftedTarget() throws Exception {
        CultPlayer player = player(ProtocolVersion.V1_21_4);
        try {
            PacketEntity entity = added(player);
            sync(player, new Vec3d(0, 37, 0.9));
            entity.onMovement(player, true);
            assertEquals(0.3D, entity.clientPhysicalPosition.z, 1.0E-9D);

            sync(player, new Vec3d(20, 100, 6));
            entity.onMovement(player, true);
            assertEquals(10.0D, entity.clientPhysicalPosition.x, 1.0E-9D);
            assertEquals(68.5D, entity.clientPhysicalPosition.y, 1.0E-9D);
            assertEquals(3.45D, entity.clientPhysicalPosition.z, 1.0E-9D);
            entity.onMovement(player, true);
            assertEquals(0.0D, entity.clientPhysicalPosition.x, 1.0E-9D);
            assertEquals(37.0D, entity.clientPhysicalPosition.y, 1.0E-9D);
            assertEquals(0.9D, entity.clientPhysicalPosition.z, 1.0E-9D);
        } finally {
            close(player);
        }
    }

    @Test
    void nearSyncInterpolates() throws Exception {
        CultPlayer player = player();
        try {
            PacketEntity entity = syncedFrom(player, new Vec3d(20, 60, 6));
            entity.onMovement(player, true);
            assertTrue(entity.clientPhysicalPosition.y < 59.0D, entity.clientPhysicalPosition.toString());
        } finally {
            close(player);
        }
    }

    @Test
    void farSyncInterpolatesBefore1_21_2() throws Exception {
        CultPlayer player = player();
        try {
            Field version = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            version.set(player, ClientVersion.V_1_21);
            PacketEntity entity = syncedFrom(player, new Vec3d(20, 100, 6));
            entity.onMovement(player, true);
            assertTrue(entity.clientPhysicalPosition.y < 99.0D, entity.clientPhysicalPosition.toString());
        } finally {
            close(player);
        }
    }

    private static PacketEntity syncedFrom(CultPlayer player, Vec3d target) {
        PacketEntity entity = added(player);
        sync(player, target);
        return entity;
    }

    private static PacketEntity added(CultPlayer player) {
        player.packetEntityReplication.addEntity(ID, EntityTypeIds.PLAYER, new Vec3(0, 37, 0), 0, 0, 0, -1);
        PacketEntity entity = player.compensatedEntities.getEntity(ID);
        assertNotNull(entity);
        for (int tick = 0; tick < 5; tick++) entity.onMovement(player, true);
        return entity;
    }

    private static void sync(CultPlayer player, Vec3d target) {
        var sync = new ClientboundEntityPositionSync(ID, new PositionPath.Linear(target), 0.0F, 0.0F, false);
        var event = new PacketSendEvent<>(
                player.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, sync, false);
        player.packetEntityReplication.onEntityPositionSync(event, player, sync);
        event.tasksAfter().forEach(Runnable::run);
        for (var write : event.writesAfter()) {
            if (write.packet() instanceof ClientboundPing ping) assertTrue(player.addTransactionResponse(ping.id()));
        }
    }

    private static CultPlayer player() {
        return player(ProtocolVersion.V26_2);
    }

    private static CultPlayer player(ProtocolVersion version) {
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
                return version;
            }

            @Override
            public ProtocolVersion getClientProtocol() {
                return version;
            }

            @Override
            public void disconnect(Component reason) {}

            @Override
            public void sendMessage(Component message) {}
        };
        User user =
                TestUsers.create(new User.Profile(UUID.randomUUID(), "Legacy_Sync"), new EmbeddedChannel(), platform);
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
