package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * Allocation bounds for tracked entities interpolating toward fresh server positions: 40 mobs and 10 boats moving every
 * cycle, beside a vehicle the local player rides with a rider carrying another. A client tick's entity pass allocates
 * at most the new position of each moving entity, and a passenger sweep at most the seat offset and position of each
 * passenger it places.
 */
class EntityInterpolationAllocationTest {
    private static final int MOBS = 40;
    private static final int BOATS = 10;
    private static final int MOB = 1000;
    private static final int BOAT = 2000;
    private static final int VEHICLE = 3000;
    private static final int RIDER = 3001;
    private static final int TOP_RIDER = 3002;
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 5_000;
    private static final int MOVING = MOBS + BOATS + 1;
    private static final int PASSENGERS = 3;
    private static final double POSITION_BYTES = 48;
    private static final double SEAT_BYTES = 160;

    enum Client {
        OLD(ClientVersion.V_1_8),
        LEGACY(ClientVersion.V_1_21_4),
        STEPPED(ClientVersion.V_26_3);

        final ClientVersion version;

        Client(ClientVersion version) {
            this.version = version;
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void interpolatingEntitiesStayWithinTheirPositionAllocations(Client client) {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);

        CultPlayer player = world(client.version);
        try {
            CompensatedEntities entities = player.compensatedEntities;
            assertSame(entities.getEntity(VEHICLE), entities.selfRidingRoot());
            Consumer<PacketEntity> onMovement = entity -> entity.onMovement(player, true);
            boolean stepped = client.version.isNewerThanOrEquals(ClientVersion.V_26_3);
            Runnable entityPass = stepped
                    ? () -> entities.tickClientEntities(onMovement)
                    : () -> entities.forEachTrackedEntity(onMovement);
            Runnable tickEnd = () -> player.packetEntityReplication.onPlayerTickEnd(null);
            long[] totals = new long[3];
            for (int i = 0; i < WARMUP; i++) cycle(player, i, entityPass, tickEnd, bean, totals);
            totals[0] = totals[1] = totals[2] = 0;
            for (int i = 0; i < MEASURED; i++) cycle(player, WARMUP + i, entityPass, tickEnd, bean, totals);
            double sweep = totals[0] / (double) MEASURED;
            double pass = totals[1] / (double) MEASURED;
            double clientTickEnd = totals[2] / (double) MEASURED;

            String report = String.format(
                    "%s: sweep=%.1f B, entityPass=%.1f B, clientTickEnd=%.1f B",
                    client, sweep, pass, clientTickEnd);
            System.out.println("INTERP-ALLOC " + report);
            if (System.getProperty("interp.alloc.report") != null) return;

            assertTrue(sweep <= MOVING * PASSENGERS * SEAT_BYTES, report);
            assertTrue(pass <= (stepped ? 8 : MOVING * POSITION_BYTES), report);
        } finally {
            close(player);
        }
    }

    /**
     * Every mob, boat and the ridden vehicle moves, each movement followed by the passenger sweep its task runs; then
     * one client tick's entity pass, and one whole client tick end. Adds the bytes the sweeps, the pass and the tick
     * end allocate to {@code totals}.
     */
    private static void cycle(
            CultPlayer player, int n, Runnable entityPass, Runnable tickEnd, ThreadMXBean bean, long[] totals) {
        long thread = Thread.currentThread().getId();
        CompensatedEntities entities = player.compensatedEntities;
        double d = (n & 1) == 0 ? 0.25 : -0.25;
        float yaw = (n & 2) == 0 ? 10.0F : -10.0F;
        boolean stepped = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
        for (int id : ids()) {
            PacketEntity entity = entities.getEntity(id);
            Vec3 base = entity.desyncClientPos;
            if (stepped && entity.isLivingEntity()) {
                entity.onPositionPath(
                        EntityPositionPath.linear(new Vec3(base.x + d, base.y, base.z + d)),
                        true,
                        yaw,
                        0.0F,
                        player,
                        true);
            } else {
                entity.onBundledPositionSyncTransaction(base.x + d, base.y, base.z + d, yaw, 0.0F, player);
            }
            long before = bean.getThreadAllocatedBytes(thread);
            entities.updatePassengerPositions();
            totals[0] += bean.getThreadAllocatedBytes(thread) - before;
        }
        long before = bean.getThreadAllocatedBytes(thread);
        entityPass.run();
        long afterPass = bean.getThreadAllocatedBytes(thread);
        tickEnd.run();
        totals[1] += afterPass - before;
        totals[2] += bean.getThreadAllocatedBytes(thread) - afterPass;
    }

    private static final int[] IDS = buildIds();

    private static int[] ids() {
        return IDS;
    }

    private static int[] buildIds() {
        int[] ids = new int[MOVING];
        for (int i = 0; i < MOBS; i++) ids[i] = MOB + i;
        for (int i = 0; i < BOATS; i++) ids[MOBS + i] = BOAT + i;
        ids[MOBS + BOATS] = VEHICLE;
        return ids;
    }

    private static CultPlayer world(ClientVersion version) {
        CultPlayer player = trackingPlayer(version);
        player.entityID = 7;
        player.x = 0;
        player.y = 64;
        player.z = 0;
        var replication = player.packetEntityReplication;
        for (int i = 0; i < MOBS; i++) {
            replication.addEntity(MOB + i, EntityTypeIds.ZOMBIE, new Vec3(i, 64, 3), 0, 0, 0, -1);
        }
        for (int i = 0; i < BOATS; i++) {
            replication.addEntity(BOAT + i, EntityTypeIds.OAK_BOAT, new Vec3(i, 64, -6), 0, 0, 0, -1);
        }
        replication.addEntity(VEHICLE, EntityTypeIds.PIG, new Vec3(0, 64, 0), 0, 0, 0, -1);
        replication.addEntity(RIDER, EntityTypeIds.ARMOR_STAND, new Vec3(0, 64, 0), 0, 0, 0, -1);
        replication.addEntity(TOP_RIDER, EntityTypeIds.SLIME, new Vec3(0, 64, 0), 0, 0, 0, -1);
        var vehicles = player.compensatedEntities.vehicles;
        vehicles.applyVehiclePassengers(VEHICLE, new int[] {player.entityID, RIDER});
        vehicles.applyVehiclePassengers(RIDER, new int[] {TOP_RIDER});
        return player;
    }

    private static CultPlayer trackingPlayer(ClientVersion version) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Interp_Alloc"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        try {
            Field resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            resolved.set(player, version);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
