package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A rider takes control of a living mount that LinearInterpolationHandler still moves toward a server position.
 * Entity#commonTick runs the handler's interpolate before LivingEntity#aiStep reaches travelRidden, so the mount
 * finishes its three linear steps under the rider: a third, a half, then the rest of the way to the target.
 */
class LinearLivingMountTest {
    private static final int DONKEY = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final short STEP = 600;
    private static final double PATH = STEP / 4096.0;
    private static final double SIDEWAYS = 0.25;

    @Test
    void aSaddledLinearMountFinishesTheServerPath() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            move(f, STEP);
            f.rideTick();
            saddle(f);

            for (int step = 2; step <= 3; step++) {
                assertTrue(controls(f), "step " + step);
                PredictionResult result = f.rideTick(START.add(PATH * step / 3, 0, 0), true);
                assertTrue(exact(result), "step " + step + ": " + describe(result));
            }
            PredictionResult settled = f.rideTick(START.add(PATH, 0, 0), true);
            assertTrue(exact(settled), describe(settled));
            assertEquals(0, badPackets.violations);
        }
    }

    /**
     * ServerboundMoveVehiclePacket#fromEntity sends Entity#getClientPositionAndRotation, the handler's target while the
     * path is followed: each controlled tick reports the path end while the mount itself still steps toward it.
     */
    @Test
    void aSaddledLinearMountReportsTheTargetUntilThePathEnds() throws Exception {
        try (var f = ridden()) {
            Check offset = enabled(f, VehicleOffset.class);
            Check simulation = enabled(f, OffsetHandler.class);
            move(f, STEP);
            f.rideTick();
            saddle(f);

            for (int tick = 0; tick < 4; tick++) {
                assertTrue(controls(f), "tick " + tick);
                f.rideTick(START.add(PATH, 0, 0), true);
            }
            assertEquals(0, offset.violations);
            assertEquals(0, simulation.violations);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    /** The rider's forward key walks the mount along z while the linear steps carry it along x. */
    @Test
    void aRiderWalksALinearMountForwardWhileItFinishesTheServerPath() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            move(f, STEP);
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            f.rideTick();
            saddle(f);

            // AbstractHorse#getRiddenInput: the 0.98F forward input at the donkey's movement_speed of 0.175.
            double input = 0.98F;
            float speed = 0.175F;
            float blockFriction = 0.6F;
            double onGround = input * (speed * (0.21600002F / (blockFriction * blockFriction * blockFriction)));
            double inAir = input * (speed * 0.1F);
            float groundFriction = blockFriction * 0.91F;
            float airFriction = 0.91F;

            double velocity = onGround;
            double z = START.z + velocity;
            assertExactTick(f, new Vec3(START.x + PATH * 2 / 3, START.y, z), false, "tick 1");
            velocity = velocity * groundFriction + inAir;
            z += velocity;
            assertExactTick(f, new Vec3(START.x + PATH, START.y, z), true, "tick 2");
            velocity = velocity * airFriction + onGround;
            z += velocity;
            assertExactTick(f, new Vec3(START.x + PATH, START.y, z), true, "tick 3");
            assertEquals(0, badPackets.violations);
        }
    }

    /**
     * The rider's forward key walks the mount along z while ServerboundMoveVehiclePacket#fromEntity reports the
     * handler's target: the first controlled tick reports the path end, the mount's own travel reaching the target only
     * on the next tick, which lands the mount on it and ends the path.
     */
    @Test
    void aRiderHoldingForwardReportsTheTargetUntilThePathEnds() throws Exception {
        try (var f = ridden()) {
            Check offset = enabled(f, VehicleOffset.class);
            Check simulation = enabled(f, OffsetHandler.class);
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            move(f, STEP);
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            f.rideTick();
            saddle(f);

            // AbstractHorse#getRiddenInput: the 0.98F forward input at the donkey's movement_speed of 0.175.
            double input = 0.98F;
            float speed = 0.175F;
            float blockFriction = 0.6F;
            double onGround = input * (speed * (0.21600002F / (blockFriction * blockFriction * blockFriction)));
            double inAir = input * (speed * 0.1F);
            float groundFriction = blockFriction * 0.91F;
            float airFriction = 0.91F;
            double target = START.x + PATH;

            double velocity = onGround;
            double z = START.z + velocity;
            assertTrue(controls(f), "tick 1");
            PredictionResult reported = f.rideTick(new Vec3(target, START.y, START.z), false);
            assertTrue(reported.getFlags().isEmpty(), "tick 1: " + describe(reported));
            velocity = velocity * groundFriction + inAir;
            z += velocity;
            assertExactTick(f, new Vec3(target, START.y, z), true, "tick 2");
            velocity = velocity * airFriction + onGround;
            z += velocity;
            assertExactTick(f, new Vec3(target, START.y, z), true, "tick 3");
            assertEquals(0, offset.violations);
            assertEquals(0, simulation.violations);
            assertEquals(0, badPackets.violations);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    /** A controlled report that leaves the path sideways is set back onto the path. */
    @Test
    void aReportSidewaysOffTheServerPathIsSetBackOntoIt() throws Exception {
        try (var f = ridden()) {
            enabled(f, VehicleOffset.class);
            move(f, STEP);
            f.rideTick();
            saddle(f);

            assertTrue(controls(f));
            PredictionResult sideways = f.rideTick(START.add(PATH * 2 / 3, 0, SIDEWAYS), true);
            assertTrue(sideways.hasFlag(VehicleOffset.class), describe(sideways));
            assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), describe(sideways));
            Vec3 setback = f.player
                    .getSetbackTeleportUtil()
                    .getRequiredSetBack()
                    .getTeleportData()
                    .getLocation();
            assertTrue(setback.x > START.x && setback.x <= START.x + PATH, "setback " + setback);
            assertEquals(START.y, setback.y, 1.0E-9);
            assertEquals(START.z, setback.z, 1.0E-9);
        }
    }

    private static void assertExactTick(JavaTickFixture f, Vec3 to, boolean onGround, String tick) {
        assertTrue(controls(f), tick);
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(to, f.player.xRot, f.player.yRot, onGround);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotSame(before, result, tick + " committed no prediction");
        assertTrue(exact(result), tick + ": " + describe(result));
    }

    private static boolean controls(JavaTickFixture f) {
        PacketEntity donkey = f.player.compensatedEntities.getEntity(DONKEY);
        return f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(donkey);
    }

    /** A tamed, unsaddled donkey created after the opt-in, under the rider after a second of passive riding. */
    private static JavaTickFixture ridden() throws Exception {
        var f = new JavaTickFixture();
        f.player.interpolationOptIn.loginHandled(true);
        ClientInterpolationOptInTest.channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3");
        f.place(START.add(0, 1, 0), Vec3.ZERO);
        send(
                f,
                ClientboundPackets.ADD_ENTITY,
                new ClientboundAddEntity(DONKEY, "minecraft:donkey", new Vec3d(START.x, START.y, START.z), 0, 0, 0));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getEntity(DONKEY).usesLinearInterpolationHandler());
        // AbstractHorse's DATA_ID_FLAGS with FLAG_TAME.
        f.player.compensatedEntities.updateEntityMetadata(
                DONKEY, List.of(new EntityMetadata.Entry(18, (byte) 0x02, ByteBuffer.allocate(0))));
        f.setPassengers(DONKEY, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        for (int tick = 0; tick < 20; tick++) f.rideTick();
        assertFalse(controls(f));
        return f;
    }

    private static void move(JavaTickFixture f, short x) {
        send(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        DONKEY, new EntityDelta.Linear(x, (short) 0, (short) 0), 0, 0, true, true, false));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void saddle(JavaTickFixture f) {
        var item = DataTables.defaults().items().stream()
                .filter(definition -> definition.key().equals("minecraft:saddle"))
                .findFirst()
                .orElseThrow();
        var packet = new InventoryPackets.Equipment(
                DONKEY,
                List.of(new InventoryPackets.EquipmentEntry(
                        EquipmentSlot.SADDLE, new SimItemStack(item, 1, Components.EMPTY))));
        send(f, type(InventoryPackets.Equipment.class), packet);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> PacketType<T> type(Class<T> record) {
        return (PacketType<T>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == record)
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Check> T enabled(JavaTickFixture f, Class<T> type) {
        f.player.setExperimentalChecks(true);
        T check = f.player.checkManager.getCheck(type);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    @SuppressWarnings("unchecked")
    private static <T> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        var replication = f.player.packetEntityReplication;
        Object raw = packet;
        if (raw instanceof ClientboundAddEntity add) {
            replication.onAddEntity((PacketSendEvent<ClientboundAddEntity>) (PacketSendEvent<?>) event, f.player, add);
        } else if (raw instanceof ClientboundMoveEntity move) {
            replication.onMoveEntity(
                    (PacketSendEvent<ClientboundMoveEntity>) (PacketSendEvent<?>) event, f.player, move);
        } else {
            replication.onSetEquipment(
                    (PacketSendEvent<InventoryPackets.Equipment>) (PacketSendEvent<?>) event,
                    f.player,
                    (InventoryPackets.Equipment) raw);
        }
        event.tasksAfter().forEach(Runnable::run);
    }
}
