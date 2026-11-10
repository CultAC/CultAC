package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Packet-order decoding and the observable order of real movement tasks and their proof continuations. */
class EntityMovementProofOrderTest {
    private static final int FIRST = 3000;
    private static final int SECOND = 3001;

    @Test
    void movementsSharingABundleProofApplyAndSettleInPacketOrder() throws Exception {
        try (var f = new Fixture(ProtocolVersion.V1_21_11)) {
            var target = f.add(FIRST, "a");
            int proof = f.nextProof();
            f.move(FIRST, 4096, proof);
            f.player.latencyUtils.addRealTimeTask(proof, () -> f.log.add("metadata"));
            f.move(FIRST, 8192, proof);
            assertEquals(List.of(), f.log);
            f.ack(proof);
            assertEquals(List.of("a bundled 1.0", "a settle", "metadata", "a bundled 3.0", "a settle"), f.log);
            assertEquals(3.0, target.desyncClientPos.x);
            assertNull(target.oldPacketLocation);
            f.log.clear();
            f.ack(proof + 1);
            assertEquals(List.of(), f.log, "bundled movement has no next-transaction continuation");
        }
    }

    @Test
    void bedrockJavaMovementUpdatesAuthorityWithoutSchedulingJavaInterpolation() throws Exception {
        try (var f = new Fixture(ProtocolVersion.V1_21_11)) {
            var target = f.add(FIRST, "a");
            f.player.movementPlatform = MovementPlatform.BEDROCK;
            int first = f.nextProof();
            f.move(FIRST, 4096);
            assertEquals(
                    1.0, f.player.compensatedEntities.getTrackedEntity(FIRST).getX());
            f.ack(first + 1);
            assertEquals(List.of(), f.log, "the outgoing Bedrock stream owns the visible position");
            assertEquals(0.0, target.desyncClientPos.x);
            assertNull(target.oldPacketLocation);
        }
    }

    private static final class RecordingEntity extends PacketEntity {
        private final String name;
        private final List<String> log;

        private RecordingEntity(CultPlayer player, int id, String name, List<String> log) {
            super(player, id, EntityTypeIds.ZOMBIE, 0, 64, 0);
            this.name = name;
            this.log = log;
        }

        @Override
        public void onBundleFirstTransaction(
                boolean relative,
                boolean hasPosition,
                double x,
                double y,
                double z,
                boolean hasRotation,
                float yaw,
                float pitch,
                CultPlayer player) {
            log.add(name + " bundled " + x);
            super.onBundleFirstTransaction(relative, hasPosition, x, y, z, hasRotation, yaw, pitch, player);
        }

        @Override
        public void onPositionPath(
                EntityPositionPath path,
                boolean hasPosition,
                boolean hasRotation,
                float yaw,
                float pitch,
                CultPlayer player) {
            log.add(name + " path " + path.endPosition().x);
            super.onPositionPath(path, hasPosition, hasRotation, yaw, pitch, player);
        }

        @Override
        public void onSecondTransaction() {
            log.add(name + " settle");
            super.onSecondTransaction();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CultPlayer player;
        private final List<String> log = new ArrayList<>();

        private Fixture(ProtocolVersion protocol) throws Exception {
            OfflineCultTestBootstrap.installConfig();
            var platform = new PlatformConnection() {
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
                    return protocol;
                }

                @Override
                public ProtocolVersion getClientProtocol() {
                    return protocol;
                }

                @Override
                public void disconnect(Component reason) {}

                @Override
                public void sendMessage(Component message) {}
            };
            User user = TestUsers.create(
                    new User.Profile(UUID.randomUUID(), "Movement_Order"), new EmbeddedChannel(), platform);
            player = new CultPlayer(user);
            user.bind(Mockito.mock(PlatformPlayer.class));
            player.entityID = 7;
        }

        private RecordingEntity add(int id, String name) {
            player.packetEntityReplication.addEntity(id, EntityTypeIds.ZOMBIE, new Vec3(0, 64, 0), 0, 0, 0, -1);
            return replace(id, name);
        }

        private RecordingEntity replace(int id, String name) {
            var entity = new RecordingEntity(player, id, name, log);
            player.compensatedEntities.entityMap.put(id, entity);
            return entity;
        }

        private int nextProof() {
            int next = Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()) + 1;
            player.lastTransactionSent.set(next);
            return next;
        }

        private void move(int id, int dx) {
            move(id, dx, 0);
        }

        private void move(int id, int dx, int sharedProof) {
            var packet = new ClientboundMoveEntity(
                    id, new EntityDelta.Linear((short) dx, (short) 0, (short) 0), 0, 0, true, true, false);
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, sharedProof != 0);
            if (sharedProof != 0) event.sharedPing(sharedProof);
            player.packetEntityReplication.onMoveEntity(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        private void ack(int transaction) {
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        @Override
        public void close() {
            player.onRemove();
            var channel = (EmbeddedChannel) player.user.getChannel();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
    }
}
