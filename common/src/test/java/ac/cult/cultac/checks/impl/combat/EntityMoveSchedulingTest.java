package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A remote player's FairReach move applies once, at whichever of its transaction or its deadline comes first. */
class EntityMoveSchedulingTest {
    private static final int TARGET = 3000;
    private static final int STEPPED = 3001;

    @Test
    void aFairReachMoveAppliesOnceAtItsTransactionOrItsDeadline() {
        CultPlayer player = trackingPlayer(ProtocolVersion.V1_21_11);
        EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
        try {
            FairReach fairReach = world(player);
            moveAndAcknowledge(player, move(4096));
            FairReach.FairReachEntity target = fairReach.targetPlayers.get(TARGET);

            foreignPing(player);
            send(player, move(-4096));
            // FairReach applies a move at the first ping sent after it.
            int applies = player.lastTransactionSent.get() + 1;
            acknowledge(player, applies - 1);
            target.lerpTicks = 0;
            acknowledge(player, applies);
            assertEquals(3, target.lerpTicks, "the acknowledged transaction applies the move");
            target.lerpTicks = 0;
            channel.runScheduledPendingTasks();
            assertEquals(0, target.lerpTicks, "the deadline does not apply an applied move again");

            foreignPing(player);
            send(player, move(4096));
            int next = player.lastTransactionSent.get() + 1;
            channel.runScheduledPendingTasks();
            assertEquals(3, target.lerpTicks, "the deadline applies an unacknowledged move");
            target.lerpTicks = 0;
            acknowledge(player, next);
            assertEquals(0, target.lerpTicks, "the transaction does not apply an applied move again");
        } finally {
            close(player);
        }
    }

    @Test
    void aRotationRestartsTheLerpOfALinear263EntityOnly() {
        CultPlayer player = trackingPlayer(ProtocolVersion.V26_3);
        try {
            player.interpolationOptIn.loginSent(true);
            player.interpolationOptIn.loginHandled(true);
            player.interpolationOptIn.declare();
            FairReach fairReach = world(player);
            player.interpolationOptIn.undeclare();
            fairReach.onAddEntity(
                    null, player, new ClientboundAddEntity(STEPPED, "minecraft:player", new Vec3d(0, 64, 0), 0, 0, 0));
            player.packetEntityReplication.addEntity(STEPPED, EntityTypeIds.PLAYER, new Vec3(0, 64, 0), 0, 0, 0, -1);
            assertTrue(player.compensatedEntities.getEntity(TARGET).usesLinearInterpolationHandler());
            assertFalse(player.compensatedEntities.getEntity(STEPPED).usesLinearInterpolationHandler());

            send(player, turn(TARGET));
            send(player, turn(STEPPED));
            acknowledge(player, player.lastTransactionSent.get() + 1);
            assertEquals(3, fairReach.targetPlayers.get(TARGET).lerpTicks);
            assertEquals(0, fairReach.targetPlayers.get(STEPPED).lerpTicks);
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

    private static ClientboundMoveEntity turn(int entityId) {
        return new ClientboundMoveEntity(entityId, EntityDelta.ZERO, 90.0F, 0.0F, true, false, true);
    }

    private static CultPlayer trackingPlayer(ProtocolVersion protocol) {
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
