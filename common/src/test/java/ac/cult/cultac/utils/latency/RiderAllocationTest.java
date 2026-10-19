package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * Cosmetic riders on other players cost nothing per passenger sweep or client tick while the local player rides
 * nothing: 40 ridden players, each with a two-deep stack of riders, beside 40 unridden mobs.
 */
class RiderAllocationTest {
    private static final int PLAYERS = 40;
    private static final int VEHICLE = 2000;
    private static final int RIDER = 3000;
    private static final int TOP_RIDER = 4000;
    private static final int MOB = 1000;
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 5_000;

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
    void cosmeticRidersAllocateNothingPerSweepOrTick(Client client) {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);

        CultPlayer ridden = world(client.version, true);
        CultPlayer bare = world(client.version, false);
        try {
            CompensatedEntities entities = ridden.compensatedEntities;

            // Every entity movement task runs this sweep.
            double sweep = perOperation(bean, entities::updatePassengerPositions);
            double tickEnd = perOperation(bean, entities.vehicles::onClientTickEnd);
            // The whole client-tick-end entity pass, with riders minus without: the rider share of a client tick.
            Runnable riddenPass = () -> ridden.packetEntityReplication.onPlayerTickEnd(null);
            Runnable barePass = () -> bare.packetEntityReplication.onPlayerTickEnd(null);
            // A first round of both lets the JIT settle on code shared by the two worlds before either is measured.
            perOperation(bean, riddenPass);
            perOperation(bean, barePass);
            double riddenTick = perOperation(bean, riddenPass);
            double bareTick = perOperation(bean, barePass);
            double riderTick = riddenTick - bareTick;

            String report = String.format(
                    "%s: sweep=%.1f B, vehicleTickEnd=%.1f B, clientTickEnd ridden=%.1f B bare=%.1f B riderShare=%.1f B",
                    client, sweep, tickEnd, riddenTick, bareTick, riderTick);
            System.out.println("RIDER-ALLOC " + report);
            if (System.getProperty("rider.alloc.report") != null) return;

            assertTrue(sweep <= 8, report);
            assertTrue(tickEnd <= 8, report);
            assertTrue(riderTick <= 64, report);
        } finally {
            close(ridden);
            close(bare);
        }
    }

    private static double perOperation(ThreadMXBean bean, Runnable operation) {
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < WARMUP; i++) operation.run();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < MEASURED; i++) operation.run();
        return (bean.getThreadAllocatedBytes(thread) - before) / (double) MEASURED;
    }

    private static CultPlayer world(ClientVersion version, boolean riders) {
        CultPlayer player = trackingPlayer(version);
        player.entityID = 7;
        player.x = 0;
        player.y = 64;
        player.z = 0;
        var replication = player.packetEntityReplication;
        for (int i = 0; i < PLAYERS; i++) {
            replication.addEntity(MOB + i, EntityTypeIds.ZOMBIE, new Vec3(i, 64, 3), 0, 0, 0, -1);
            replication.addEntity(VEHICLE + i, EntityTypeIds.PLAYER, new Vec3(i, 64, -3), 0, 0, 0, -1);
            if (!riders) continue;
            replication.addEntity(RIDER + i, EntityTypeIds.ARMOR_STAND, new Vec3(i, 64, -3), 0, 0, 0, -1);
            replication.addEntity(TOP_RIDER + i, EntityTypeIds.ARMOR_STAND, new Vec3(i, 64, -3), 0, 0, 0, -1);
            if (i % 2 == 0) player.compensatedEntities.getEntity(TOP_RIDER + i).scale = 0.5F;
            player.compensatedEntities.vehicles.applyVehiclePassengers(VEHICLE + i, new int[] {RIDER + i});
            player.compensatedEntities.vehicles.applyVehiclePassengers(RIDER + i, new int[] {TOP_RIDER + i});
        }
        return player;
    }

    private static CultPlayer trackingPlayer(ClientVersion version) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Rider_Alloc"), new EmbeddedChannel());
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
