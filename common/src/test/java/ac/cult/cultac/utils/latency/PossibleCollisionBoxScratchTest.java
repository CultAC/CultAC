package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * An entity's possible collision box written into one pair of boxes reused across every entity, in entity map order,
 * reads as the box of its own the allocating overload returns: bounds and full-block flag, through spawns, moves an
 * acknowledgement has not yet settled, riders seated on 26.3 and a full-block seat.
 */
class PossibleCollisionBoxScratchTest {
    private static final int SELF = 7;

    enum Client {
        LEGACY(ClientVersion.V_1_21_11),
        STEPPED(ClientVersion.V_26_3);

        final ClientVersion version;

        Client(ClientVersion version) {
            this.version = version;
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void aReusedPairReadsAsAFreshBox(Client client) {
        CultPlayer player = player(client.version);
        try {
            int[] types = {
                EntityTypeIds.ZOMBIE,
                EntityTypeIds.PLAYER,
                EntityTypeIds.OAK_BOAT,
                EntityTypeIds.SLIME,
                EntityTypeIds.ARMOR_STAND,
                EntityTypeIds.ZOMBIE,
                EntityTypeIds.PIG
            };
            for (int i = 0; i < types.length; i++) add(player, 3000 + i, types[i], -6 + 2.25 * i, 1.5 * i);
            player.compensatedEntities.vehicles.applyVehiclePassengers(3002, new int[] {3003});
            player.compensatedEntities.vehicles.applyVehiclePassengers(3006, new int[] {3005});
            SimpleCollisionBox scratch = new SimpleCollisionBox();
            SimpleCollisionBox out = new SimpleCollisionBox();
            int compared = check(player, out, scratch);
            assertTrue(compared >= types.length, "entities " + compared);

            player.compensatedEntities
                    .getEntity(3003)
                    .setPassengerPosition(player, new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true));
            player.compensatedEntities
                    .getEntity(3005)
                    .setPassengerPosition(player, new SimpleCollisionBox(-1, 64, 2, -0.4, 65.8, 2.6, false));
            check(player, out, scratch);
            checkUnsettled(player, out, scratch);
            for (int tick = 0; tick < 6; tick++) {
                for (int i = 0; i < types.length; i++) {
                    if ((i + tick) % 3 == 0) continue;
                    move(player, 3000 + i, 300 * (tick + 1) - 200 * i, (short) (97 * i - 40 * tick), 150 - 60 * tick);
                }
                check(player, out, scratch);
                checkUnsettled(player, out, scratch);
                acknowledgeThrough(player, player.lastTransactionSent.get() - 1);
                check(player, out, scratch);
                ((EmbeddedChannel) player.user.getChannel()).runPendingTasks();
                player.onEndOfTickEvent(tick);
                acknowledgeThrough(player, player.lastTransactionSent.get());
                player.packetEntityReplication.onPlayerTickEnd(null);
                check(player, out, scratch);
            }
        } finally {
            close(player);
        }
    }

    /** Compares every entity's two boxes; how many were compared. */
    private static int check(CultPlayer player, SimpleCollisionBox out, SimpleCollisionBox scratch) {
        List<String> expected = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            expected.add(entity.getEntityId() + " " + entity.getPossibleCollisionBoxes());
            actual.add(entity.getEntityId() + " " + entity.getPossibleCollisionBoxes(out, scratch));
        }
        assertEquals(expected, actual);
        return expected.size();
    }

    /**
     * Compares them while each entity's older location is another's current one, as a move holds until its proof
     * settles it.
     */
    private static void checkUnsettled(CultPlayer player, SimpleCollisionBox out, SimpleCollisionBox scratch) {
        List<PacketEntity> entities = new ArrayList<>(player.compensatedEntities.entityMap.values());
        List<ReachInterpolationData> old = new ArrayList<>();
        for (PacketEntity entity : entities) old.add(entity.oldPacketLocation);
        try {
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).oldPacketLocation = entities.get((i + 1) % entities.size()).newPacketLocation;
            }
            check(player, out, scratch);
        } finally {
            for (int i = 0; i < entities.size(); i++) entities.get(i).oldPacketLocation = old.get(i);
        }
    }

    private static void add(CultPlayer player, int id, int type, double x, double z) {
        player.packetEntityReplication.addEntity(id, type, new Vec3(x, 64, z), 0, 0, 0, -1);
    }

    private static void move(CultPlayer player, int id, int dx, short dy, int dz) {
        var move = new ClientboundMoveEntity(
                id, new EntityDelta.Linear((short) dx, dy, (short) dz), 0, 0, true, true, false);
        var event =
                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        player.packetEntityReplication.onMoveEntity(event, player, move);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static void acknowledgeThrough(CultPlayer player, int transaction) {
        for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
    }

    private static CultPlayer player(ClientVersion version) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(
                new User.Profile(UUID.randomUUID(), ".Box_Scratch"),
                new EmbeddedChannel(),
                OfflineCultTestBootstrap.platformConnection());
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        try {
            Field resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            resolved.set(player, version);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        player.entityID = SELF;
        player.x = 0;
        player.y = 64;
        player.z = 0;
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
