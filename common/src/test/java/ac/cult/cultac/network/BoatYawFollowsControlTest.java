package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VehicleOffset;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AbstractBoat#controlBoat turns the boat by deltaRotation and thrusts along the turned yaw, which
 * ServerboundMoveVehiclePacket#fromEntity then reports. The thrust follows the simulated yaw, and the server receives it.
 */
class BoatYawFollowsControlTest {
    private static final int BOAT = 100;
    // Past the client ticks after a mount in which a control switch may still be settling.
    private static final int WARMUP_TICKS = 6;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 80, 0.5);
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 MOVED = new Vec3(0.5, FLOOR_Y, -3.5);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    private static JavaTickFixture mount(Vec3 at, ServerboundPlayerInput input) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(at, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, at, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(input);
        f.tickEnd();
        return f;
    }

    private static Vec3 floatBoatInAir(Vec3 v) {
        return new Vec3(v.x * 0.9F, v.y - 0.04F, v.z * 0.9F);
    }

    private static Vec3 thrust(float yaw) {
        double rad = Math.toRadians(yaw);
        return new Vec3(-Math.sin(rad) * 0.04F, 0, Math.cos(rad) * 0.04F);
    }

    private static double offset(PredictionResult r) {
        return r.getTarget().subtract(r.getAcceptedClosestToTarget()).length();
    }

    private static void assertClose(PredictionResult r, String at) {
        assertTrue(
                r.getFlags().isEmpty() && !r.isExempt() && offset(r) < 1.0E-4,
                at + " " + JavaTickFixture.describe(r) + " offset " + offset(r));
    }

    /** A client tick whose rider Rot keeps the rider's yaw and whose MoveVehicle reports {@code boatYaw}. */
    private static PacketReceiveEvent<ServerboundMoveVehicle> drive(
            JavaTickFixture f, Vec3 to, float boatYaw, boolean onGround) {
        var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        PacketReceiveEvent<ServerboundMoveVehicle> move = f.moveVehicle(to, boatYaw, 0, onGround);
        f.tickEnd();
        return move;
    }

    private static PredictionResult last(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    @Test
    void thrustAlongAYawTheBoatDidNotTurnToIsRejected() throws Exception {
        try (JavaTickFixture f = mount(SEAT, FORWARD)) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < WARMUP_TICKS; tick++) {
                velocity = floatBoatInAir(velocity).add(thrust(0));
                position = position.add(velocity);
                assertClose(f.rideTick(position, false), "tick " + tick);
            }
            velocity = floatBoatInAir(velocity).add(thrust(90));
            position = position.add(velocity);
            drive(f, position, 90, false);
            JavaTickFixture.assertRejected(last(f), "VehicleOffset");
        }
    }

    @Test
    void aReportedYawTheBoatDidNotTurnToReachesTheServerAsTheTurnedYaw() throws Exception {
        try (JavaTickFixture f = mount(SEAT, FORWARD)) {
            f.player.checkManager.getCheck(VehicleOffset.class).setEnabled(true);
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < WARMUP_TICKS; tick++) {
                velocity = floatBoatInAir(velocity).add(thrust(0));
                position = position.add(velocity);
                assertClose(f.rideTick(position, false), "tick " + tick);
            }
            velocity = floatBoatInAir(velocity).add(thrust(0));
            position = position.add(velocity);
            PacketReceiveEvent<ServerboundMoveVehicle> move = drive(f, position, 90, false);
            assertClose(last(f), "reported yaw 90");
            assertFalse(move.isCancelled());
            assertEquals(0.0F, move.getPacket().yaw());
            assertEquals(0.0F, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalYaw);
            velocity = floatBoatInAir(velocity).add(thrust(0));
            position = position.add(velocity);
            assertClose(f.rideTick(position, false), "after the reported yaw");
        }
    }

    /** VehicleOffset left unable to modify packets forwards the claimed yaw; the engine keeps the turned one. */
    @Test
    void aReportedYawTheBoatDidNotTurnToIsForwardedWhenPacketsAreNotModified() throws Exception {
        try (JavaTickFixture f = mount(SEAT, FORWARD)) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick <= WARMUP_TICKS; tick++) {
                velocity = floatBoatInAir(velocity).add(thrust(0));
                position = position.add(velocity);
                if (tick < WARMUP_TICKS) assertClose(f.rideTick(position, false), "tick " + tick);
            }
            assertFalse(f.player.checkManager.getCheck(VehicleOffset.class).shouldModifyPackets());
            PacketReceiveEvent<ServerboundMoveVehicle> move = drive(f, position, 90, false);
            assertClose(last(f), "reported yaw 90");
            assertEquals(90.0F, move.getPacket().yaw());
            assertEquals(0.0F, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalYaw);
        }
    }

    @Test
    void aBoatTurnedWithTheKeysReportsItsYawUnchanged() throws Exception {
        try (JavaTickFixture f = mount(SEAT, FORWARD_LEFT)) {
            Vec3 position = SEAT;
            Vec3 velocity = Vec3.ZERO;
            float deltaRotation = 0.0F;
            float yaw = 0.0F;
            for (int tick = 0; tick < 15; tick++) {
                deltaRotation *= 0.9F;
                deltaRotation--;
                yaw += deltaRotation;
                velocity = floatBoatInAir(velocity).add(thrust(yaw));
                position = position.add(velocity);
                f.player.xRot = yaw;
                PacketReceiveEvent<ServerboundMoveVehicle> move = drive(f, position, yaw, false);
                assertClose(last(f), "tick " + tick + " yaw " + yaw);
                assertEquals(yaw, move.getPacket().yaw(), "tick " + tick);
                assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), "setback at " + tick);
            }
        }
    }

    @Test
    void aBoatDrivesAlongTheYawAServerVehicleMoveGaveIt() throws Exception {
        try (JavaTickFixture f = mount(GROUND, RELEASED)) {
            f.fixture.registrar.register(routes -> routes.send(
                    ClientboundPackets.MOVE_VEHICLE,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new PacketServerTeleport().onMoveVehicle(event, player, packet)));
            for (int i = 0; i < 3; i++) JavaTickFixture.assertVehicleExact(f.rideTick(GROUND, true), i);
            f.serverMoveVehicle(MOVED, 45, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketReceiveEvent<ServerboundMoveVehicle> answer = f.moveVehicle(MOVED, 45, 0, true);
            assertFalse(answer.isCancelled());
            assertEquals(45.0F, answer.getPacket().yaw());
            f.tickEnd();
            // AbstractBoat#controlBoat reads the input LocalPlayer#rideTick set in the previous tick.
            f.input(FORWARD);
            JavaTickFixture.assertVehicleExact(f.rideTick(MOVED, true), 0);
            Vec3 position = MOVED;
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < 4; tick++) {
                velocity = new Vec3(velocity.x * 0.6F, 0, velocity.z * 0.6F).add(thrust(45));
                position = position.add(velocity);
                PacketReceiveEvent<ServerboundMoveVehicle> move = drive(f, position, 45, true);
                assertClose(last(f), "tick " + tick);
                assertEquals(45.0F, move.getPacket().yaw(), "tick " + tick);
            }
        }
    }

    private static final int WATER_Y = FLOOR_Y - 2;
    private static final ServerboundPlayerInput LEFT = keys(false, true, false);
    private static final ServerboundPlayerInput RIGHT = keys(false, false, true);
    private static final ServerboundPlayerInput FORWARD_RIGHT = keys(true, false, true);
    private static final ServerboundPlayerInput[] PADDLES = paddles();

    private static ServerboundPlayerInput keys(boolean forward, boolean left, boolean right) {
        return new ServerboundPlayerInput(forward, false, left, right, false, false, false);
    }

    /** The keys of each tick: the paddles alternating, both with and without forward, then a coast. */
    private static ServerboundPlayerInput[] paddles() {
        List<ServerboundPlayerInput> keys = new ArrayList<>();
        for (ServerboundPlayerInput[] run : new ServerboundPlayerInput[][] {
            {LEFT, LEFT, LEFT, LEFT, LEFT, LEFT},
            {RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT},
            {LEFT, RIGHT, LEFT, RIGHT, LEFT, RIGHT},
            {FORWARD_LEFT, FORWARD_LEFT, FORWARD_RIGHT, FORWARD_RIGHT},
            {LEFT, LEFT, LEFT},
        }) {
            keys.addAll(List.of(run));
        }
        for (int tick = 0; tick < 14; tick++) keys.add(RELEASED);
        return keys.toArray(new ServerboundPlayerInput[0]);
    }

    /**
     * AbstractBoat#tick of a boat its player drives over a flat floor or still source water, transcribed from 1.21.11
     * and 26.3, which agree: floatBoat, controlBoat, then Entity#move.
     */
    private static final class VanillaBoat {
        enum Status {
            ON_LAND,
            IN_AIR,
            IN_WATER
        }

        private static final float[] SIN = new float[65536];

        static {
            for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i / 10430.378350470453);
        }

        Vec3 position;
        Vec3 velocity = Vec3.ZERO;
        float yaw;
        float deltaRotation;
        double lastYd;
        Status status;
        // The friction of the floor block, which AbstractBoat#getGroundFriction averages over the blocks under the boat.
        final float floorFriction;
        // The block the still water fills, or none.
        final int waterY;

        VanillaBoat(Vec3 position, float yaw, float floorFriction, int waterY) {
            this.position = position;
            this.yaw = yaw;
            this.floorFriction = floorFriction;
            this.waterY = waterY;
            this.status = status();
        }

        static float sin(double radians) {
            return SIN[(int) ((long) (radians * 10430.378350470453) & 65535L)];
        }

        static float cos(double radians) {
            return SIN[(int) ((long) (radians * 10430.378350470453 + 16384.0) & 65535L)];
        }

        // Fluid#getHeight of a source with air above, added to its block's y in float.
        private float surface() {
            return waterY + 8.0F / 9.0F;
        }

        private Status status() {
            if (waterY != Integer.MIN_VALUE && position.y < surface()) return Status.IN_WATER;
            return position.y == FLOOR_Y && waterY == Integer.MIN_VALUE ? Status.ON_LAND : Status.IN_AIR;
        }

        private float groundFriction() {
            int blocks = 0;
            for (int x = (int) Math.floor(position.x - 0.6875); x < Math.ceil(position.x + 0.6875); x++) {
                for (int z = (int) Math.floor(position.z - 0.6875); z < Math.ceil(position.z + 0.6875); z++) {
                    blocks++;
                }
            }
            float friction = 0.0F;
            for (int block = 0; block < blocks; block++) friction += floorFriction;
            return friction / blocks;
        }

        // AbstractBoat#getWaterLevelAbove for a boat over the single layer of water at waterY.
        private float waterLevelAbove() {
            double maxY = position.y + 0.5625F;
            int from = (int) Math.floor(maxY);
            int to = (int) Math.ceil(maxY - lastYd);
            for (int y = from; y < to; y++) {
                return y == waterY ? surface() : (float) y;
            }
            return to + 1;
        }

        void tick(ServerboundPlayerInput keys) {
            Status oldStatus = status;
            status = status();
            if (oldStatus == Status.IN_AIR && status == Status.IN_WATER) {
                double targetY = waterLevelAbove() - 0.5625F + 0.101;
                position = new Vec3(position.x, targetY, position.z);
                velocity = new Vec3(velocity.x, 0.0, velocity.z);
                lastYd = 0.0;
            } else {
                float invFriction = status == Status.ON_LAND ? groundFriction() : 0.9F;
                velocity = new Vec3(velocity.x * invFriction, velocity.y - 0.04, velocity.z * invFriction);
                deltaRotation *= invFriction;
                if (status == Status.IN_WATER) {
                    double buoyancy = (surface() - position.y) / 0.5625F;
                    if (buoyancy > 0.0) {
                        velocity = new Vec3(velocity.x, (velocity.y + buoyancy * (0.04 / 0.65)) * 0.75, velocity.z);
                    }
                }
            }
            float acceleration = 0.0F;
            if (keys.left()) deltaRotation--;
            if (keys.right()) deltaRotation++;
            if (keys.right() != keys.left() && !keys.forward() && !keys.backward()) acceleration += 0.005F;
            yaw += deltaRotation;
            if (keys.forward()) acceleration += 0.04F;
            float radians = yaw * (float) (Math.PI / 180.0);
            velocity = velocity.add(sin(-radians) * acceleration, 0.0, cos(radians) * acceleration);
            Vec3 delta = velocity;
            Vec3 movement = delta;
            if (status == Status.ON_LAND) {
                // Entity#move stops the fall on the floor, and Block#updateEntityMovementAfterFallOn zeroes it.
                movement = new Vec3(delta.x, 0.0, delta.z);
                velocity = movement;
            }
            // Entity#move leaves the boat where it is when the floor took all but a tiny remainder of the move.
            double moved = movement.lengthSqr();
            if (moved > 1.0E-7 || delta.lengthSqr() - moved < 1.0E-7) position = position.add(movement);
            lastYd = velocity.y;
        }
    }

    private static JavaTickFixture driver(ClientVersion version, Vec3 at, float yaw) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.checkManager.getCheck(VehicleOffset.class).setEnabled(true);
        f.place(at, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, at, yaw, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = yaw;
        f.player.yRot = 0;
        return f;
    }

    private static void floor(JavaTickFixture f, int block) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), block);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /**
     * Drives {@code boat} through {@code keys}, LocalPlayer#tick sending each tick's changed keys before its Rot and
     * MoveVehicle, which AbstractBoat#controlBoat reads the tick after. Every tick the engine predicts the move exactly,
     * forwards the boat's yaw unchanged and holds it as the boat's yaw.
     */
    private static void driveHonestly(JavaTickFixture f, VanillaBoat boat, ServerboundPlayerInput[] keys, String at)
            throws Exception {
        for (int tick = 0; tick < keys.length; tick++) {
            if (tick % 50 == 0) f.resetTimerBalances();
            boat.tick(keys[tick]);
            if (tick + 1 < keys.length && !keys[tick + 1].equals(keys[tick])) f.input(keys[tick + 1]);
            f.player.xRot = boat.yaw;
            PacketReceiveEvent<ServerboundMoveVehicle> move =
                    drive(f, boat.position, boat.yaw, boat.status == VanillaBoat.Status.ON_LAND);
            String where = at + " tick " + tick + " yaw " + boat.yaw + " turn " + boat.deltaRotation;
            assertFalse(move.isCancelled(), where);
            assertClose(last(f), where);
            assertEquals(boat.yaw, move.getPacket().yaw(), where);
            assertEquals(boat.yaw, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalYaw, where);
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), where);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "V_1_21_11, minecraft:stone, 0.6",
        "V_1_21_11, minecraft:ice, 0.98",
        "V_1_21_11, minecraft:blue_ice, 0.989",
        "V_26_3, minecraft:stone, 0.6",
        "V_26_3, minecraft:ice, 0.98",
        "V_26_3, minecraft:blue_ice, 0.989"
    })
    void aBoatPaddledOnLandReportsItsYawUnchanged(ClientVersion version, String block, float friction)
            throws Exception {
        try (JavaTickFixture f = driver(version, GROUND, 0)) {
            floor(f, DataTables.defaults().registry().block(block).defaultState());
            f.input(PADDLES[0]);
            f.tickEnd();
            VanillaBoat boat = new VanillaBoat(GROUND, 0, friction, Integer.MIN_VALUE);
            driveHonestly(f, boat, PADDLES, block);
        }
    }

    /**
     * A ledge under a resting boat turns to water: the boat falls, AbstractBoat#floatBoat lands it on the surface
     * without slowing its deltaRotation, and it floats, all while the paddles alternate.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aBoatPaddledIntoAndOnWaterReportsItsYawUnchanged(ClientVersion version) throws Exception {
        Vec3 rest = new Vec3(0.5, WATER_Y + 1, 0.5);
        try (JavaTickFixture f = driver(version, rest, 0)) {
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, WATER_Y, z), BlockIds.STONE.defaultState());
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, WATER_Y - 1, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.input(RELEASED);
            f.tickEnd();
            for (int tick = 0; tick < 3; tick++) {
                if (tick == 2) f.input(PADDLES[0]);
                JavaTickFixture.assertVehicleExact(f.rideTick(rest, true), tick);
            }
            f.water(new BlockPos(-4, WATER_Y, -4), new BlockPos(4, WATER_Y, 4));
            VanillaBoat still = new VanillaBoat(rest, 0, 0, WATER_Y);
            for (double height : new double[] {62.96, 62.88, 62.5385, 62.53724989232772, 62.53516477683845}) {
                still.tick(RELEASED);
                assertEquals(height - 62 + WATER_Y, still.position.y, 1.0E-9);
            }
            VanillaBoat boat = new VanillaBoat(rest, 0, 0, WATER_Y);
            boat.status = VanillaBoat.Status.IN_AIR;
            driveHonestly(f, boat, PADDLES, "water");
        }
    }

    /**
     * ClientPacketListener#handleMoveVehicle snaps a turning boat to the packet's yaw and answers at once, and
     * AbstractBoat#controlBoat turns the snapped yaw by the boat's unchanged deltaRotation. The client ticks
     * {@code before} times before it handles the move and {@code between} times between its answer and the pong.
     */
    @ParameterizedTest
    @CsvSource({"0, 0", "2, 0", "0, 2", "2, 2"})
    void aBoatTurningThroughAServerVehicleMoveReportsItsYawUnchanged(int before, int between) throws Exception {
        try (JavaTickFixture f = driver(ClientVersion.V_26_3, GROUND, 0)) {
            f.input(LEFT);
            f.tickEnd();
            VanillaBoat boat = new VanillaBoat(GROUND, 0, 0.6F, Integer.MIN_VALUE);
            ServerboundPlayerInput[] keys = {LEFT, LEFT, LEFT, LEFT, RELEASED, RELEASED, RELEASED};
            driveHonestly(f, boat, keys, "before the move");
            f.serverMoveVehicle(MOVED, 405, 0);
            int ping = f.player.lastTransactionSent.get();
            ServerboundPlayerInput[] coast = new ServerboundPlayerInput[before];
            Arrays.fill(coast, RELEASED);
            driveHonestly(f, boat, coast, "move in flight");
            boat.position = MOVED;
            boat.yaw = 405 % 360.0F;
            PacketReceiveEvent<ServerboundMoveVehicle> answer = f.moveVehicle(MOVED, boat.yaw, 0, true);
            assertFalse(answer.isCancelled());
            assertEquals(45.0F, answer.getPacket().yaw());
            coast = new ServerboundPlayerInput[between];
            Arrays.fill(coast, RELEASED);
            driveHonestly(f, boat, coast, "pong in flight");
            f.pongThrough(ping);
            coast = new ServerboundPlayerInput[6];
            Arrays.fill(coast, RELEASED);
            driveHonestly(f, boat, coast, "after the pong");
        }
    }

    /**
     * A ClientboundMoveVehicle to where the boat is: ClientPacketListener#handleMoveVehicle snaps the boat to the packet's
     * yaw only when {@code before} ticks in flight moved it away first, and the answer carries the yaw the boat then has.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void aServerVehicleMoveToWhereTheBoatIsIsAnsweredWithTheBoatsYaw(int before) throws Exception {
        try (JavaTickFixture f = driver(ClientVersion.V_26_3, GROUND, 0)) {
            f.input(LEFT);
            f.tickEnd();
            VanillaBoat boat = new VanillaBoat(GROUND, 0, 0.6F, Integer.MIN_VALUE);
            driveHonestly(f, boat, new ServerboundPlayerInput[] {LEFT, LEFT, LEFT, LEFT, LEFT}, "before the move");
            Vec3 sent = boat.position;
            f.serverMoveVehicle(sent, 405, 0);
            int ping = f.player.lastTransactionSent.get();
            ServerboundPlayerInput[] keys = new ServerboundPlayerInput[before];
            Arrays.fill(keys, LEFT);
            driveHonestly(f, boat, keys, "move in flight");
            if (before > 0) {
                boat.position = sent;
                boat.yaw = 405 % 360.0F;
            }
            PacketReceiveEvent<ServerboundMoveVehicle> answer = f.moveVehicle(boat.position, boat.yaw, 0, true);
            assertFalse(answer.isCancelled());
            assertEquals(boat.yaw, answer.getPacket().yaw());
            f.pongThrough(ping);
            keys = new ServerboundPlayerInput[6];
            Arrays.fill(keys, LEFT);
            driveHonestly(f, boat, keys, "after the answer");
        }
    }

    /** An answer to a ClientboundMoveVehicle carries the yaw the snap gave the boat, whatever the client reports. */
    @Test
    void aServerVehicleMoveAnswerReachesTheServerWithTheSnappedYaw() throws Exception {
        try (JavaTickFixture f = driver(ClientVersion.V_26_3, GROUND, 0)) {
            f.input(RELEASED);
            f.tickEnd();
            for (int i = 0; i < 3; i++) JavaTickFixture.assertVehicleExact(f.rideTick(GROUND, true), i);
            f.serverMoveVehicle(MOVED, 45, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketReceiveEvent<ServerboundMoveVehicle> answer = f.moveVehicle(MOVED, 170, 0, true);
            assertFalse(answer.isCancelled());
            assertEquals(45.0F, answer.getPacket().yaw());
            assertEquals(45.0F, f.player.compensatedEntities.getEntity(BOAT).clientPhysicalYaw);
        }
    }
}
