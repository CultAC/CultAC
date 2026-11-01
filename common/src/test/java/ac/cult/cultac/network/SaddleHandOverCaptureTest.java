package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A rider saddles, through the mount screen's hotbar-key swap, the tamed donkey it sits on while the server walks it.
 * ServerboundMoveVehiclePacket#fromEntity sends Entity#getClientPositionAndRotation, the interpolation target while the
 * server path is still being followed, and AbstractInterpolationHandler#adjustInterpolationTargetFromDeltas carries the
 * root's own travel into that target on the next tick. The donkey hand-overs replay a captured 26.3 session, shifted
 * onto the fixture floor.
 */
class SaddleHandOverCaptureTest {
    private static final int DONKEY = 100;
    private static final double OX = 128;
    private static final double OZ = 172;
    private static final float RIDER_YAW = -95.82344F;
    private static final float RIDER_PITCH = -11.1829F;
    private static final float SEATED_PITCH = -5.59145F;
    private static final double FALL = -0.0783739241897089;
    private static final int WINDOW = 4;
    private static final int HOTBAR_0 = 2 + 27;
    private static final int SWAP_TICKS = 30;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    @Test
    void saddlingTwoTicksIntoAStep() throws Exception {
        try (var f = new JavaTickFixture()) {
            Check offset = enabled(f, VehicleOffset.class);
            Check simulation = enabled(f, OffsetHandler.class);
            int tick = mount(f, "minecraft:donkey", 30, 127.56587608531851, 172.2691780113773, 297.00494F, -40F);
            tick = walk(f, tick, 33, 361, 184, null, 0F, null);
            tick = walk(f, tick, 36, 362, 184, null, 0F, null);
            tick = walk(f, tick, 39, 362, 185, null, 0F, null);
            tick = walk(
                    f, tick, 42, 280, 280, -36.5625F, -40.78125F, new double[] {0.011475309772325026, 0.013550631752426323});
            walk(f, tick, 45, 245, 321, -36.5625F, 0F, new double[] {0.010681804309345067, 0.014466214978941627});
            swapSaddleIn(f);
            move(f, 127.958984375, 172.551025390625, -36.5625F, 0F, true);
            equipSaddle(f);
            move(f, 127.96966617930934, 172.56549160560394, RIDER_YAW, 21.59605F, true);
            move(f, 127.97868286265292, 172.57770277104638, RIDER_YAW, SEATED_PITCH, true);
            stop(f, 81, 109);
            move(f, 127.97868286265292, 172.57770277104638, RIDER_YAW, SEATED_PITCH, false);
            for (int i = 0; i < 3; i++) {
                move(f, 127.97868286265292, 172.57770277104638, RIDER_YAW, SEATED_PITCH, true);
            }

            assertEquals(0, offset.violations);
            assertEquals(0, simulation.violations);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    @Test
    void saddlingOnTheTickAStepArrives() throws Exception {
        try (var f = new JavaTickFixture()) {
            Check offset = enabled(f, VehicleOffset.class);
            Check simulation = enabled(f, OffsetHandler.class);
            int tick = mount(f, "minecraft:donkey", 114, 128.1372353279964, 173.73488110529934, 6.126457F, 0F);
            tick = walk(f, tick, 117, -20, 385, null, 0F, new double[] {-9.155832265153041E-4, 0.0175181590673259});
            tick = walk(
                    f, tick, 120, -11, 402, 2.8125F, 0F, new double[] {-4.883110541414881E-4, 0.017945431239699605});
            tick = walk(f, tick, 123, -12, 405, null, 0F, null);
            tick = walk(f, tick, 126, -12, 405, null, 0F, null);
            tick = walk(f, tick, 129, -12, 405, null, 0F, null);
            tick = walk(f, tick, 132, -13, 405, null, 0F, null);
            tick = walk(f, tick, 135, -13, 406, null, 0F, null);
            tick = walk(f, tick, 138, -13, 405, 4.21875F, 0F, null);
            tick = walk(f, tick, 141, -14, 405, null, 0F, null);
            walk(f, tick, 144, -15, 405, null, 0F, null);
            swapSaddleIn(f);
            move(f, 128.104248046875, 174.71826171875, 4.21875F, 0F, true);
            equipSaddle(f);
            move(f, 128.104248046875, 174.733529028073, RIDER_YAW, SEATED_PITCH, true);
            move(f, 128.104248046875, 174.74641641017507, RIDER_YAW, SEATED_PITCH, true);
            stop(f, 0, 115);
            move(f, 128.104248046875, 174.74641641017507, RIDER_YAW, SEATED_PITCH, false);
            for (int i = 0; i < 3; i++) {
                move(f, 128.104248046875, 174.74641641017507, RIDER_YAW, SEATED_PITCH, true);
            }

            assertEquals(0, offset.violations);
            assertEquals(0, simulation.violations);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    /**
     * A tamed horse with movement_speed 0.225 strolls three blocks a second under the server and is saddled on the
     * tick a step arrives. The reports are the target T, then T + v, then T + v(1 + f + f^2) with f the ground friction
     * 0.6F * 0.91F, as LivingEntity#travel slows v while AbstractInterpolationHandler#adjustInterpolationTargetFromDeltas
     * carries each tick's travel into the target until the path ends.
     */
    @Test
    void saddlingAStrollingHorse() throws Exception {
        saddleWhileServerMoves(700, 860, 0.057, 0.07);
    }

    /** A leash is ticked on the server only (Leashable#tickLeash takes a ServerLevel): the client sees large steps. */
    @Test
    void saddlingALedHorse() throws Exception {
        saddleWhileServerMoves(2560, 3456, 0.208, 0.281);
    }

    /**
     * A rider holding forward as the horse is saddled. LocalPlayer#sendChanges sends the keys its aiStep read after the
     * root travelled that tick, so the root travels on them from the next tick: on the ground Entity#moveRelative adds
     * movement_speed times the forward impulse 0.98F, turned to the rider's yaw by AbstractHorse#tickRidden.
     */
    @Test
    void aRiderHoldingForwardIntoTheSaddleTick() throws Exception {
        saddleWhileRiderHoldsForward(true);
    }

    @Test
    void aRiderPressingForwardOnTheSaddleTick() throws Exception {
        saddleWhileRiderHoldsForward(false);
    }

    /**
     * The server moves the horse a led step every three ticks while the rider swaps the saddle out and back in before
     * each of its ticks, so each tick hands the root over again with the latest step's target to follow. A report
     * that leaves the path sideways is set back on its first tick.
     */
    @Test
    void aSaddleHandOverOnAMovingHorseFollowsItsPath() throws Exception {
        assertEquals(-1, saddleSwappedEveryTickOnALivePath(0.0D));
        assertEquals(0, saddleSwappedEveryTickOnALivePath(0.01D));
    }

    private static void saddleWhileServerMoves(int dx, int dz, double vx, double vz) throws Exception {
        try (var f = new JavaTickFixture()) {
            Check offset = enabled(f, VehicleOffset.class);
            Check simulation = enabled(f, OffsetHandler.class);
            double[] target = walkedHorse(f, dx, dz, vx, vz, null);
            double tx = target[0];
            double tz = target[1];
            double friction = 0.6F * 0.91F;
            double carried = 1 + friction + friction * friction;

            swapSaddleIn(f);
            move(f, tx, tz, 0F, 0F, true);
            equipSaddle(f);
            move(f, tx + vx, tz + vz, RIDER_YAW, SEATED_PITCH, true);
            move(f, tx + vx * carried, tz + vz * carried, RIDER_YAW, SEATED_PITCH, true);
            stop(f, dx, dz);
            move(f, tx + vx * carried, tz + vz * carried, RIDER_YAW, SEATED_PITCH, false);
            for (int i = 0; i < 3; i++) {
                move(f, tx + vx * carried, tz + vz * carried, RIDER_YAW, SEATED_PITCH, true);
            }

            assertEquals(0, offset.violations);
            assertEquals(0, simulation.violations);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    private static void saddleWhileRiderHoldsForward(boolean beforeSaddleTick) throws Exception {
        try (var f = new JavaTickFixture()) {
            Check offset = enabled(f, VehicleOffset.class);
            Check simulation = enabled(f, OffsetHandler.class);
            double vx = 0.057;
            double vz = 0.07;
            double[] target = walkedHorse(f, 700, 860, vx, vz, beforeSaddleTick ? FORWARD : null);
            if (!beforeSaddleTick) f.input(FORWARD);
            double[] input = forwardInput(0.225F, RIDER_YAW);
            double friction = 0.6F * 0.91F;
            double mx = vx + (beforeSaddleTick ? input[0] : 0.0D);
            double mz = vz + (beforeSaddleTick ? input[1] : 0.0D);

            swapSaddleIn(f);
            move(f, target[0], target[1], 0F, 0F, true);
            equipSaddle(f);
            double x = target[0] + mx;
            double z = target[1] + mz;
            move(f, x, z, RIDER_YAW, SEATED_PITCH, true);
            for (int i = 0; i < 2; i++) {
                mx = mx * friction + input[0];
                mz = mz * friction + input[1];
                x += mx;
                z += mz;
            }
            move(f, x, z, RIDER_YAW, SEATED_PITCH, true);
            for (int i = 0; i < 3; i++) {
                mx = mx * friction + input[0];
                mz = mz * friction + input[1];
                x += mx;
                z += mz;
                move(f, x, z, RIDER_YAW, SEATED_PITCH, true);
            }

            assertEquals(0, offset.violations);
            assertEquals(0, simulation.violations);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());
        }
    }

    /** Entity#getInputVector of the forward impulse 0.98F at {@code speed}, through Mth's sine table. */
    private static double[] forwardInput(float speed, float yaw) {
        double forward = 0.98F * (double) speed;
        double radians = yaw * (float) (Math.PI / 180.0);
        double sin = (float) Math.sin((int) ((long) (radians * 10430.378350470453) & 65535L) / 10430.378350470453);
        double cos =
                (float) Math.sin((int) ((long) (radians * 10430.378350470453 + 16384.0) & 65535L) / 10430.378350470453);
        return new double[] {-forward * sin, forward * cos};
    }

    /**
     * Swaps the saddle out and back in before each rider tick while the server steps the horse, reporting the latest
     * step's position moved {@code sideways} further across the path every tick; the first tick that flags or sets
     * back, or -1.
     */
    private static int saddleSwappedEveryTickOnALivePath(double sideways) throws Exception {
        try (var f = new JavaTickFixture()) {
            Check offset = enabled(f, VehicleOffset.class);
            Check simulation = enabled(f, OffsetHandler.class);
            int dx = 2560;
            int dz = 3456;
            double x0 = OX - 5;
            double z0 = OZ - 5;
            int tick = mount(f, "minecraft:horse", 114, x0, z0, 0F, 0F);
            for (int step = 1; step <= 3; step++) {
                tick = walk(f, tick, 114 + 3 * step, dx, dz, null, 0F, null);
            }
            long serverX = Math.round(x0 * 4096) + 3L * dx;
            long serverZ = Math.round(z0 * 4096) + 3L * dz;
            double across = Math.hypot(dx, dz);
            double drift = 0.0D;
            for (int i = 0; i < SWAP_TICKS; i++) {
                clickSaddle(f, SimItemStack.EMPTY, saddle());
                if (i % 3 == 0) {
                    moveEntity(f, dx, dz, 0F, 0F, false);
                    serverX += dx;
                    serverZ += dz;
                }
                clickSaddle(f, saddle(), SimItemStack.EMPTY);
                drift += sideways;
                move(
                        f,
                        serverX / 4096.0 - dz / across * drift,
                        serverZ / 4096.0 + dx / across * drift,
                        0F,
                        0F,
                        true);
                if (offset.violations + simulation.violations > 0
                        || f.player.getSetbackTeleportUtil().isPendingSetback()) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Mounts a horse with movement_speed 0.225 and lets the server walk it six steps of ({@code dx}, {@code dz}) at
     * velocity ({@code vx}, {@code vz}), the rider sending {@code input} on the tick before the last step; the last
     * step's position.
     */
    private static double[] walkedHorse(
            JavaTickFixture f, int dx, int dz, double vx, double vz, ServerboundPlayerInput input) {
        double x0 = OX - 5;
        double z0 = OZ - 5;
        int steps = 6;
        int tick = mount(f, "minecraft:horse", 114, x0, z0, 0F, 0F);
        var speed = new ClientboundUpdateAttributes(
                DONKEY, List.of(new AttributeSnapshot("minecraft:movement_speed", 0.225, List.of())));
        var speedEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, speed, false);
        f.player.packetEntityReplication.onUpdateAttributes(speedEvent, f.player, speed);
        speedEvent.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int step = 1; step <= steps; step++) {
            int at = 114 + 3 * step;
            if (step == steps && input != null) {
                for (; tick < at - 1; tick++) f.rideTick();
                f.input(input);
            }
            tick = walk(f, tick, at, dx, dz, null, 0F, new double[] {vx, vz});
        }
        return new double[] {(x0 * 4096 + steps * dx) / 4096, (z0 * 4096 + steps * dz) / 4096};
    }

    /** Seats the rider on the horse at an entity_position_sync and rides until {@code syncTick + 1}. */
    private static int mount(
            JavaTickFixture f, String type, int syncTick, double x, double z, float yaw, float pitch) {
        Vec3d spawn = new Vec3d(x - OX, FLOOR_Y, z - OZ);
        f.place(new Vec3(spawn.x(), spawn.y() + 1, spawn.z()), Vec3.ZERO);
        send(f, ClientboundPackets.ADD_ENTITY, new ClientboundAddEntity(DONKEY, type, spawn, yaw, pitch, 0));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.compensatedEntities.updateEntityMetadata(
                DONKEY, List.of(new EntityMetadata.Entry(18, (byte) 0x02, ByteBuffer.allocate(0))));
        f.setPassengers(DONKEY, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        openMountScreen(f);
        f.player.xRot = RIDER_YAW;
        f.player.yRot = RIDER_PITCH;
        for (int tick = 0; tick < 20; tick++) f.rideTick();
        return syncTick + 1;
    }

    /** Rides uncontrolled until {@code at}, then delivers that tick's set_entity_motion and stepped move_entity. */
    private static int walk(
            JavaTickFixture f, int tick, int at, int dx, int dz, Float yaw, float pitch, double[] motion) {
        for (; tick < at; tick++) f.rideTick();
        if (motion != null) {
            send(
                    f,
                    ClientboundPackets.ENTITY_MOTION,
                    new ClientboundEntityMotion(DONKEY, new Vec3d(motion[0], FALL, motion[1])));
        }
        moveEntity(f, dx, dz, yaw == null ? 0F : yaw, pitch, yaw != null);
        return tick;
    }

    private static void stop(JavaTickFixture f, int dx, int dz) {
        send(f, ClientboundPackets.ENTITY_MOTION, new ClientboundEntityMotion(DONKEY, new Vec3d(0, 0, 0)));
        moveEntity(f, dx, dz, -97.03125F, -5.625F, true);
    }

    private static void moveEntity(JavaTickFixture f, int dx, int dz, float yaw, float pitch, boolean hasRotation) {
        send(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        DONKEY,
                        new EntityDelta.Stepped(List.of(new EntityDelta.Step((short) dx, (short) 0, (short) dz, 3))),
                        yaw,
                        pitch,
                        true,
                        true,
                        hasRotation));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** One LocalPlayer#tick of the rider: its Rot packet, then the root's ServerboundMoveVehiclePacket. */
    private static void move(JavaTickFixture f, double x, double z, float yaw, float pitch, boolean onGround) {
        var rot = new ServerboundMovePlayer(0, 0, 0, RIDER_YAW, RIDER_PITCH, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var vehicle = new ServerboundMoveVehicle(new Vec3d(x - OX, FLOOR_Y, z - OZ), yaw, pitch, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, vehicle), f.player, vehicle);
        f.tickEnd();
    }

    private static void openMountScreen(JavaTickFixture f) {
        var open = new ClientboundMountScreenOpen(WINDOW, 0, DONKEY);
        var openEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOUNT_SCREEN_OPEN, open, false);
        new PacketPlayerWindow().onMountScreenOpen(openEvent, f.player, open);
        f.player.getInventory().onMountScreenOpen(openEvent, f.player, open);
        openEvent.tasksAfter().forEach(Runnable::run);
        var items = new ArrayList<SimItemStack>();
        for (int slot = 0; slot < 2 + 36; slot++) items.add(SimItemStack.EMPTY);
        items.set(HOTBAR_0, saddle());
        var content = new InventoryPackets.Content(WINDOW, 1, items, SimItemStack.EMPTY);
        var contentEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, type(InventoryPackets.Content.class), content, false);
        f.player.getInventory().onContainerSetContent(contentEvent, f.player, content);
        contentEvent.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void swapSaddleIn(JavaTickFixture f) {
        clickSaddle(f, saddle(), SimItemStack.EMPTY);
    }

    /** A hotbar-key swap between the mount screen's saddle slot and the first hotbar slot, leaving these stacks. */
    private static void clickSaddle(JavaTickFixture f, SimItemStack saddleSlot, SimItemStack hotbar) {
        var click = new InventoryClick(
                WINDOW,
                1,
                0,
                0,
                WindowClickType.SWAP,
                Map.of(0, saddleSlot, HOTBAR_0, hotbar),
                SimItemStack.EMPTY);
        var event = new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, type(InventoryClick.class), click);
        f.player.getInventory().onContainerClick(event, f.player, click);
        f.player.getInventory().onClickForwarded(click);
    }

    private static void equipSaddle(JavaTickFixture f) {
        var packet = new InventoryPackets.Equipment(
                DONKEY, List.of(new InventoryPackets.EquipmentEntry(EquipmentSlot.SADDLE, saddle())));
        send(f, type(InventoryPackets.Equipment.class), packet);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static SimItemStack saddle() {
        return ItemUtil.modelItems().stack("minecraft:saddle", 1);
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
        } else if (raw instanceof ClientboundEntityMotion motion) {
            replication.onSetEntityMotion(
                    (PacketSendEvent<ClientboundEntityMotion>) (PacketSendEvent<?>) event, f.player, motion);
        } else {
            replication.onSetEquipment(
                    (PacketSendEvent<InventoryPackets.Equipment>) (PacketSendEvent<?>) event,
                    f.player,
                    (InventoryPackets.Equipment) raw);
        }
        event.tasksAfter().forEach(Runnable::run);
    }
}
