package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPositionWorlds;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UnknownCollisionProvider;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
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
 * Cosmetic riders on other players cost nothing per passenger sweep, client tick, entity-push collection or entity
 * collision while the local player rides nothing: 40 ridden players, each with a two-deep stack of riders and a living
 * hat, and 40 more each carrying a closed shulker, beside 40 unridden mobs and a boat.
 */
class RiderAllocationTest {
    private static final int PLAYERS = 40;
    private static final int VEHICLE = 2000;
    private static final int RIDER = 3000;
    private static final int TOP_RIDER = 4000;
    private static final int HAT = 5000;
    private static final int MOB = 1000;
    private static final int SHULKER_MOUNT = 6000;
    private static final int SHULKER = 7000;
    private static final int BOAT = 8000;
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
            // Entity-push collection with riders minus without; reported only, as JIT noise moves it.
            WorldStageBuilder pushes = new WorldStageBuilder();
            SimpleCollisionBox riddenRange = new SimpleCollisionBox();
            SimpleCollisionBox bareRange = new SimpleCollisionBox();
            Runnable riddenPushes = () -> pushes.addPushesOnStandingPlayer(ridden, 0, 64, 0, riddenRange);
            Runnable barePushes = () -> pushes.addPushesOnStandingPlayer(bare, 0, 64, 0, bareRange);
            perOperation(bean, riddenPushes);
            perOperation(bean, barePushes);
            double riderPushes = perOperation(bean, riddenPushes) - perOperation(bean, barePushes);
            // The far hats themselves, ruled out before their seats are computed.
            PacketEntity[] hats = new PacketEntity[PLAYERS];
            for (int i = 0; i < PLAYERS; i++) hats[i] = entities.getEntity(HAT + i);
            SimpleCollisionBox near = new SimpleCollisionBox(-0.5, 63.8, -0.5, 0.5, 66.0, 0.5);
            SimpleCollisionBox rootBox = new SimpleCollisionBox();
            SimpleCollisionBox scratch = new SimpleCollisionBox();
            for (PacketEntity hat : hats) {
                assertTrue(entities.isUntrackedRider(hat));
                assertFalse(entities.untrackedRiderMayReach(hat, near, rootBox, scratch));
            }
            double farHats = perOperation(bean, () -> {
                for (PacketEntity hat : hats) entities.untrackedRiderMayReach(hat, near, rootBox, scratch);
            });
            // Entity collision and shulker peeks beside the far shulker riders, with riders minus without.
            for (int i = 0; i < PLAYERS; i++) {
                PacketEntity shulker = entities.getEntity(SHULKER + i);
                assertTrue(entities.isUntrackedRider(shulker));
                assertFalse(entities.untrackedRiderMayReach(shulker, near, rootBox, scratch));
            }
            Vec3 feet = new Vec3(0, 64, 0);
            SimpleCollisionBox region = new SimpleCollisionBox(-0.3, 63.9, -0.3, 0.3, 66.4, 0.3);
            double riderCollisions = riderShare(
                    bean,
                    ridden,
                    bare,
                    world -> EntityPositionWorlds.colliders(world, world.compensatedEntities.getSelf(), feet, region));
            double riderNoCollision = riderShare(
                    bean, ridden, bare, world -> UnknownCollisionProvider.hasEntityCollision(world, feet, region));
            double riderPeeks = riderShare(bean, ridden, bare, world -> {
                world.compensatedEntities.shulkerPeekMoves(true);
                world.compensatedEntities.shulkerPeekMoves(false);
            });
            // The whole client-tick-end entity pass, with riders minus without: the rider share of a client tick.
            Runnable riddenPass = () -> ridden.packetEntityReplication.onPlayerTickEnd(null);
            Runnable barePass = () -> bare.packetEntityReplication.onPlayerTickEnd(null);
            // A first round of both lets the JIT settle on code shared by the two worlds before either is measured.
            perOperation(bean, riddenPass);
            perOperation(bean, barePass);
            double riddenTick = perOperation(bean, riddenPass);
            double bareTick = perOperation(bean, barePass);
            double riderTick = riddenTick - bareTick;

            PacketEntity[] riders = entities.entityMap.values().stream()
                    .filter(entities::isUntrackedRider)
                    .toArray(PacketEntity[]::new);
            SimpleCollisionBox output = new SimpleCollisionBox(), readScratch = new SimpleCollisionBox();
            double lazyReads = perOperation(bean, () -> {
                for (PacketEntity rider : riders) {
                    rider.getPossibleMovementCollisionBoxes(output, readScratch);
                    rider.getPossibleCollisionBoxes(output, readScratch);
                }
            });
            System.out.println("RIDER-READS " + client + " " + lazyReads + " B for " + riders.length + " riders");
            assertTrue(lazyReads <= 8, "lazy box reads allocated " + lazyReads + " B");

            String report = String.format(
                    "%s: sweep=%.1f B, vehicleTickEnd=%.1f B, farHats=%.1f B, riderPushes=%.1f B,"
                            + " riderCollisions=%.1f B, riderNoCollision=%.1f B, riderPeeks=%.1f B,"
                            + " clientTickEnd ridden=%.1f B bare=%.1f B riderShare=%.1f B",
                    client,
                    sweep,
                    tickEnd,
                    farHats,
                    riderPushes,
                    riderCollisions,
                    riderNoCollision,
                    riderPeeks,
                    riddenTick,
                    bareTick,
                    riderTick);
            System.out.println("RIDER-ALLOC " + report);
            if (System.getProperty("rider.alloc.report") != null) return;

            assertTrue(sweep <= 8, report);
            assertTrue(tickEnd <= 8, report);
            assertTrue(farHats <= 8, report);
            assertTrue(riderCollisions <= 8, report);
            assertTrue(riderNoCollision <= 8, report);
            assertTrue(riderPeeks <= 8, report);
            assertTrue(riderTick <= 64, report);
        } finally {
            close(ridden);
            close(bare);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void movingLoadedRidersDoNotAddPerTickAllocations(Client client) {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        CultPlayer ridden = world(client.version, true), bare = world(client.version, false);
        try {
            Runnable withRiders = movingTicks(ridden), withoutRiders = movingTicks(bare);
            perOperation(bean, withRiders);
            perOperation(bean, withoutRiders);
            double extra = perOperation(bean, withRiders) - perOperation(bean, withoutRiders);
            System.out.println("RIDER-MOVING " + client + " riderShare=" + extra + " B/tick");
            assertTrue(extra <= 64, "moving riders added " + extra + " B/tick");
        } finally {
            close(ridden);
            close(bare);
        }
    }

    private static Runnable movingTicks(CultPlayer player) {
        PacketEntity[] roots = new PacketEntity[PLAYERS * 2];
        for (int i = 0; i < PLAYERS; i++) {
            roots[i] = player.compensatedEntities.getEntity(VEHICLE + i);
            roots[PLAYERS + i] = player.compensatedEntities.getEntity(SHULKER_MOUNT + i);
        }
        SimpleCollisionBox box = new SimpleCollisionBox();
        return () -> {
            int tick = ++player.packetStateData.acceptedClientTick;
            double shift = (tick & 1) == 0 ? 0.0 : 0.25;
            for (int i = 0; i < roots.length; i++) {
                double x = i % PLAYERS + shift;
                double z = i < PLAYERS ? -3.0 : -9.0;
                ac.cult.cultac.utils.nmsutil.GetBoundingBox.getBoundingBoxFromPosAndSize(box, x, 64.0, z, .6F, 1.8F);
                roots[i].setPositionRaw(box);
            }
            player.packetEntityReplication.onPlayerTickEnd(null);
        };
    }

    private static double perOperation(ThreadMXBean bean, Runnable operation) {
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < WARMUP; i++) operation.run();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < MEASURED; i++) operation.run();
        return (bean.getThreadAllocatedBytes(thread) - before) / (double) MEASURED;
    }

    // An operation's allocation in the ridden world minus the bare one, after a first round of both settles the JIT.
    private static double riderShare(
            ThreadMXBean bean, CultPlayer ridden, CultPlayer bare, Consumer<CultPlayer> operation) {
        Runnable riddenOperation = () -> operation.accept(ridden);
        Runnable bareOperation = () -> operation.accept(bare);
        perOperation(bean, riddenOperation);
        perOperation(bean, bareOperation);
        return perOperation(bean, riddenOperation) - perOperation(bean, bareOperation);
    }

    private static CultPlayer world(ClientVersion version, boolean riders) {
        CultPlayer player = trackingPlayer(version);
        player.entityID = 7;
        player.x = 0;
        player.y = 64;
        player.z = 0;
        var column = new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[24], 0);
        for (int x = 0; x <= 2; x++) {
            player.compensatedWorld.addToCache(column, x, -1);
            player.compensatedWorld.addToCache(column, x, 0);
            player.compensatedWorld.addToCache(column, x, 1);
        }
        var replication = player.packetEntityReplication;
        // A collision candidate in both worlds, so collision readers past their empty-world return are compared.
        replication.addEntity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(0, 64, 20), 0, 0, 0, -1);
        for (int i = 0; i < PLAYERS; i++) {
            replication.addEntity(SHULKER_MOUNT + i, EntityTypeIds.PLAYER, new Vec3(i, 64, -9), 0, 0, 0, -1);
            if (riders) {
                replication.addEntity(SHULKER + i, EntityTypeIds.SHULKER, new Vec3(i, 64, -9), 0, 0, 0, -1);
                player.compensatedEntities.vehicles.applyVehiclePassengers(SHULKER_MOUNT + i, new int[] {SHULKER + i});
            }
        }
        for (int i = 0; i < PLAYERS; i++) {
            replication.addEntity(MOB + i, EntityTypeIds.ZOMBIE, new Vec3(i, 64, 3), 0, 0, 0, -1);
            replication.addEntity(VEHICLE + i, EntityTypeIds.PLAYER, new Vec3(i, 64, -3), 0, 0, 0, -1);
            if (!riders) continue;
            replication.addEntity(RIDER + i, EntityTypeIds.ARMOR_STAND, new Vec3(i, 64, -3), 0, 0, 0, -1);
            replication.addEntity(TOP_RIDER + i, EntityTypeIds.ARMOR_STAND, new Vec3(i, 64, -3), 0, 0, 0, -1);
            if (i % 2 == 0) player.compensatedEntities.getEntity(TOP_RIDER + i).scale = 0.5F;
            replication.addEntity(HAT + i, EntityTypeIds.SLIME, new Vec3(i, 64, -3), 0, 0, 0, -1);
            player.compensatedEntities.vehicles.applyVehiclePassengers(VEHICLE + i, new int[] {RIDER + i, HAT + i});
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
