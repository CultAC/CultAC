package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.PassengerRideTick;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** A passenger's ride-tick record allocates nothing in a fluid, nor in a boat floating on one. */
class PassengerRideTickAllocationTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final int VEHICLE = 100;
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 5_000;

    enum Client {
        LEGACY(ClientVersion.V_1_21_2),
        MODERN(ClientVersion.V_26_3);

        final ClientVersion version;

        Client(ClientVersion version) {
            this.version = version;
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void aRideTickInWaterAllocatesNothing(Client client) throws Exception {
        ThreadMXBean bean = allocationBean();
        try (var f = fixture(client.version)) {
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 4; y++) {
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                    }
                }
            }
            ride(f, EntityTypeIds.ARMOR_STAND, at);
            SimulationProcessor runner = f.player.checkManager.getSimulationProcessor();
            runner.recordRideTick();
            assertTrue(rideTick(runner).inFluid(), "the seat is under water");

            double bytes = perOperation(bean, runner::recordRideTick);
            assertTrue(bytes <= 1, client + ": " + bytes + " B per ride tick in water");
        }
    }

    /** AbstractBoat#modifyPassengerFluidInteractionBox, and before 26.1 Entity#updateInWaterStateAndDoWaterCurrentPushing. */
    @ParameterizedTest
    @EnumSource(Client.class)
    void aRideTickInABoatOnWaterIsDryAndAllocatesNothing(Client client) throws Exception {
        ThreadMXBean bean = allocationBean();
        try (var f = fixture(client.version)) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.WATER.defaultState());
                }
            }
            Vec3 at = new Vec3(0.5, FLOOR_Y + 0.5, 0.5);
            ride(f, EntityTypeIds.OAK_BOAT, at);
            PacketEntity boat = f.player.compensatedEntities.getEntity(VEHICLE);
            SimpleCollisionBox boatBox = boat.getPossibleMovementCollisionBoxes();
            double waterTop = FLOOR_Y + 8.0 / 9.0;
            assertTrue(boatBox.minY < waterTop && boatBox.maxY > FLOOR_Y + 1, "the boat floats: " + boatBox);
            assertTrue(f.player.compensatedWorld.getBlockStateIdAt(0, FLOOR_Y + 1, 0) == BlockIds.AIR.defaultState());
            Vec3 seat = f.player.compensatedEntities.lastRideTickSeat();
            assertTrue(seat != null && seat.y < waterTop, "the passenger's box reaches the water: " + seat);

            SimulationProcessor runner = f.player.checkManager.getSimulationProcessor();
            runner.recordRideTick();
            assertFalse(rideTick(runner).inFluid(), "a floating boat's passenger is in no fluid");

            double bytes = perOperation(bean, runner::recordRideTick);
            assertTrue(bytes <= 1, client + ": " + bytes + " B per ride tick in a boat");
        }
    }

    private static void ride(JavaTickFixture f, int type, Vec3 at) {
        Vec3 standing = new Vec3(at.x, FLOOR_Y, at.z);
        f.place(standing, Vec3.ZERO);
        f.tick(standing, true);
        f.player.compensatedEntities.addEntity(VEHICLE, type, at, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        f.input(FORWARD);
        f.rideTick();
        f.rideTick();
    }

    private static PassengerRideTick rideTick(SimulationProcessor runner) throws ReflectiveOperationException {
        Field field = SimulationProcessor.class.getDeclaredField("passengerRideTick");
        field.setAccessible(true);
        return (PassengerRideTick) field.get(runner);
    }

    private static ThreadMXBean allocationBean() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean);
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }

    private static double perOperation(ThreadMXBean bean, Runnable operation) {
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < WARMUP; i++) operation.run();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < MEASURED; i++) operation.run();
        return (bean.getThreadAllocatedBytes(thread) - before) / (double) MEASURED;
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.input(NONE);
        return f;
    }
}
