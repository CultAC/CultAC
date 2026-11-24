package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * FairReach judges an attack against the position the client has been shown at most its compensation after the server
 * sent it, whether the target rides a vehicle or the attacker does.
 */
class FairReachRiderTest {
    private static final int TARGET = 3000;
    private static final int TARGET_HORSE = 3001;
    private static final int OWN_HORSE = 3002;
    private static final int BLOCK = 4096;

    @Test
    void aRiderIsJudgedWhereItsVehicleCarriedIt() {
        try (Fixture f = new Fixture()) {
            f.addTarget(2);
            f.addHorse(TARGET_HORSE, 2);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.acknowledge();
            assertFalse(f.attackCancelled(), "a rider beside the attacker is in reach");

            f.send(move(TARGET_HORSE, 4));
            assertFalse(f.attackCancelled(), "the client may not have the vehicle's move yet");

            f.passDeadlines();
            f.tick(3);
            assertTrue(f.attackCancelled(), "the vehicle's move is due, so its rider is out of reach");
        }
    }

    @Test
    void aMountedAttackerIsJudgedFromItsSeat() {
        try (Fixture f = new Fixture()) {
            f.addHorse(OWN_HORSE, 0);
            f.send(new ClientboundSetPassengers(OWN_HORSE, List.of(f.player.entityID)));
            f.acknowledge();
            assertTrue(f.player.compensatedEntities.getSelf().inVehicle(), "the attacker rides its horse");
            f.addTarget(2);
            assertFalse(f.attackCancelled(), "a target beside the mounted attacker is in reach");

            f.send(move(TARGET, 4));
            assertFalse(f.attackCancelled(), "the client may not have the target's move yet");

            f.passDeadlines();
            f.tick(3);
            assertTrue(f.attackCancelled(), "the target's move is due, so it is out of reach");
        }
    }

    @Test
    void aRiderMovesWithItsVehicleUntilTheClientRemovesIt() {
        try (Fixture f = new Fixture()) {
            f.addTarget(6);
            f.addHorse(TARGET_HORSE, 6);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.acknowledge();
            f.tick(3);
            assertTrue(f.attackCancelled(), "the rider is out of reach");

            f.fairReach.max_ping_compensation = 300;
            f.send(move(TARGET_HORSE, -4));
            int moved = f.ping();
            f.remove(TARGET_HORSE);
            f.acknowledge(moved);
            f.tick(3);
            assertFalse(f.attackCancelled(), "the client shows the rider where its vehicle carried it");

            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "Entity#setRemoved leaves the rider on its last seat");
        }
    }

    @Test
    void aRidersOwnRotationLeavesItOnItsSeat() {
        try (Fixture f = new Fixture()) {
            f.addTarget(6);
            f.addHorse(TARGET_HORSE, 6);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.send(move(TARGET_HORSE, -4));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "the vehicle carried its rider into reach");

            f.send(new ClientboundMoveEntity(
                    TARGET, new EntityDelta.Linear((short) 0, (short) 0, (short) 0), 90.0F, 0.0F, true, false, true));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "Entity#positionRider keeps the rider on its seat");
        }
    }

    @Test
    void aDismountedRiderStaysOnItsLastSeat() {
        try (Fixture f = new Fixture()) {
            f.addTarget(6);
            f.addHorse(TARGET_HORSE, 6);
            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of(TARGET)));
            f.send(move(TARGET_HORSE, -4));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "the vehicle carried its rider into reach");

            f.send(new ClientboundSetPassengers(TARGET_HORSE, List.of()));
            f.acknowledge();
            f.tick(3);
            assertFalse(f.attackCancelled(), "Entity#stopRiding leaves the rider on its last seat");
        }
    }

    private static ClientboundMoveEntity move(int entityId, int blocks) {
        return new ClientboundMoveEntity(
                entityId,
                new EntityDelta.Linear((short) (blocks * BLOCK), (short) 0, (short) 0),
                0.0F,
                0.0F,
                true,
                true,
                false);
    }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final CultPlayer player;
        final FairReach fairReach;

        Fixture() {
            OfflineCultTestBootstrap.installConfig();
            channel.freezeTime();
            User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Fair_Rider"), channel, platform());
            player = new CultPlayer(user);
            user.bind(Mockito.mock(PlatformPlayer.class));
            player.entityID = 7;
            player.x = 0;
            player.y = 64;
            player.z = 0;
            fairReach = player.checkManager.getListener(FairReach.class);
            fairReach.max_ping_compensation = 0;
        }

        void addTarget(double x) {
            fairReach.onAddEntity(
                    null, player, new ClientboundAddEntity(TARGET, "minecraft:player", new Vec3d(x, 64, 0), 0, 0, 0));
            player.packetEntityReplication.addEntity(TARGET, EntityTypeIds.PLAYER, new Vec3(x, 64, 0), 0, 0, 0, -1);
            acknowledge();
        }

        void addHorse(int entityId, double x) {
            player.packetEntityReplication.addEntity(entityId, EntityTypeIds.HORSE, new Vec3(x, 64, 0), 0, 0, 0, -1);
            acknowledge();
        }

        void remove(int entityId) {
            var packet = new ClientboundRemoveEntities(List.of(entityId));
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
            fairReach.onRemoveEntities(event, player, packet);
            player.packetEntityReplication.onRemoveEntities(event, player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }

        /** A ping the client has not answered yet; later packets reach it after this one. */
        int ping() {
            return player.lastTransactionSent.incrementAndGet();
        }

        void acknowledge(int transaction) {
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        void send(ClientboundMoveEntity packet) {
            player.packetEntityReplication.onMoveEntity(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false),
                    player,
                    packet);
        }

        void send(ClientboundSetPassengers packet) {
            player.packetEntityReplication.onSetPassengers(
                    new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false),
                    player,
                    packet);
        }

        /** The client answers every ping sent so far. */
        void acknowledge() {
            int transaction = player.lastTransactionSent.get() + 1;
            player.lastTransactionSent.set(transaction);
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        void passDeadlines() {
            channel.runScheduledPendingTasks();
        }

        void tick(int ticks) {
            for (int i = 0; i < ticks; i++) {
                channel.advanceTimeBy(50, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                fairReach.onClientTickEnd(null, player, null);
            }
        }

        boolean attackCancelled() {
            var attack =
                    new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack);
            fairReach.onInteract(event, player, attack);
            return event.isCancelled();
        }

        @Override
        public void close() {
            player.onRemove();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
    }

    private static PlatformConnection platform() {
        return new PlatformConnection() {
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
    }
}
