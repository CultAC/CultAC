package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A boat the player drives while it rides a minecart. ClientLevel#tickPassenger runs the boat's Entity#rideTick each
 * client tick: rideTick zeroes its delta, and AbstractBoat#tick still floats, steers and moves it, as the local player
 * is its controlling passenger; the minecart's positionRider then reseats it. LocalPlayer#sendChanges sends
 * MoveVehicle only for Entity#getRootVehicle, the uncontrolled minecart, so the client reports none of those ticks, and
 * the server's ServerGamePacketListenerImpl#handleMoveVehicle ignores any it receives. Once a set_passengers ejects the
 * boat, its first tick as a root floats from the delta the last ridden tick left.
 */
class JavaBoatRiddenInMinecartTest {
    private static final int BOAT = 100;
    private static final int MINECART = 101;
    private static final Vec3 AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    /** AbstractBoat#controlBoat's forward acceleration at yaw 0: Mth.cos(0) * 0.04F. */
    private static final double THRUST = 0.04F;

    @ParameterizedTest(name = "{0}: an ejected boat falls on from the gravity its last ridden tick left")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anEjectedBoatKeepsTheGravityOfItsLastRiddenTick(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 seat = boardRiddenBoat(f, NONE);
            eject(f);
            // floatBoat in the air: the carried -0.04 plus this tick's -0.04; nothing below within reach.
            assertExact(f.rideTick(seat.add(0, -0.08, 0), false));
        }
    }

    @ParameterizedTest(name = "{0}: an ejected boat glides on from the thrust its last ridden tick left")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anEjectedBoatKeepsTheThrustOfItsLastRiddenTick(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 seat = boardRiddenBoat(f, FORWARD);
            eject(f);
            // The ridden tick's thrust, scaled by floatBoat's air friction, then this tick's own thrust.
            double z = THRUST * (double) 0.9F + THRUST;
            assertExact(f.rideTick(seat.add(0, -0.08, z), false));
        }
    }

    @Test
    void aRiddenBoatsMoveVehicleIsRejectedAndNeverForwarded() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            Vec3 seat = boardRiddenBoat(f, NONE);
            PredictionResult before =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            double violations = f.player.checkManager.getCheck(BadPacketsVehicle.class).violations;
            var event = rideTick(f, seat.add(0, 5, 0), false);
            assertTrue(event.isCancelled(), "a MoveVehicle for a boat riding a minecart reached the server");
            assertSame(before, f.player.checkManager.getSimulationProcessor().getLastPrediction());
            assertTrue(f.player.checkManager.getCheck(BadPacketsVehicle.class).violations > violations);
        }
    }

    @Test
    void aCheckedMoveIsWithheldOnceTheServerSentTheBoatAboard() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            f.place(AT, Vec3.ZERO);
            f.player.xRot = 0;
            f.player.yRot = 0;
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, AT, 0, 0, 0);
            f.player.compensatedEntities.addEntity(MINECART, minecart(), AT.add(3, 0, 0), 0, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            assertExact(f.rideTick(AT, true));
            // The client has not answered the stacking, so it still drives a root boat; the server already ignores it.
            f.setPassengers(MINECART, List.of(BOAT));
            PredictionResult before =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            var event = rideTick(f, AT, true);
            PredictionResult checked =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(checked != before, "the client's move was not checked");
            assertExact(checked);
            assertTrue(event.isCancelled(), "a move the server applies to no vehicle the client moved was forwarded");
        }
    }

    @Test
    void aPassengerOfARiddenBoatOwesNoMoveVehicle() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            boardRiddenBoat(f, NONE);
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            // BadPacketsR counts the passenger's Rot as its position update.
            assertFalse(f.player.compensatedEntities.vehicles.mustEchoMoveVehicle(boat));
            eject(f);
            assertTrue(f.player.compensatedEntities.vehicles.mustEchoMoveVehicle(boat));
            assertTrue(f.player.compensatedEntities.vehicles.serverMovesVehicleFor(boat));
            assertFalse(f.player.compensatedEntities.vehicles.serverMovesVehicleFor(null));
        }
    }

    @ParameterizedTest(name = "{0}: startRiding preserves the first tick's ground contact")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theFirstRiddenTickStartsBeforeTheMinecartSeatsTheBoat(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            board(f, AT, AT.add(0, 4, 0));
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            assertPosition(AT, boat);
            f.rideTick();
            Vec3 seat = AT.add(0, 4 + .1875, 0);
            assertPosition(seat, boat);
            assertEquals(0, boat.deltaMovement.y, 1e-13, "its first travel landed before it was seated");
            eject(f);
            assertExact(f.rideTick(seat.add(0, -.04, 0), false));
        }
    }

    @Test
    void aNestedBoatUsesItsOwnShiftFlagForItsFallCallback() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            board(f, AT, AT.add(0, 4, 0));
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            // Entity#isSuppressingBounce reads this entity's metadata, independently of the player's keys.
            boat.actionSharedFlags |= 2;
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.SLIME_BLOCK.defaultState());
            f.rideTick();
            assertEquals(0, boat.deltaMovement.y, 1e-13);
            Vec3 seat = AT.add(0, 4 + .1875, 0);
            eject(f);
            assertExact(f.rideTick(seat.add(0, -.04, 0), false));
        }
    }

    @Test
    void ejectionBeforeAnyRiddenTickDoesNotInventASeat() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            board(f, AT, AT.add(0, 4, 0));
            eject(f);
            assertPosition(AT, f.player.compensatedEntities.getEntity(BOAT));
            assertExact(f.rideTick(AT, true));
        }
    }

    @ParameterizedTest(name = "{0}: a piston displaces the boat independently of the minecart")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonMoveSurvivesPassengerUpdatesAndEjection(String release, ClientVersion version) throws Exception {
        pistonEjection(version, false);
    }

    @ParameterizedTest(name = "{0}: slime writes the nested boat's carried velocity")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aSlimePistonMoveCarriesItsVelocityThroughEjection(String release, ClientVersion version) throws Exception {
        pistonEjection(version, true);
    }

    private static void pistonEjection(ClientVersion version, boolean slime) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 seat = boardRiddenBoat(f, NONE);
            var registry = DataTables.defaults().registry();
            BlockPos base = new BlockPos(0, FLOOR_Y, 2);
            int piston = registry.with(BlockIds.PISTON.defaultState(), "facing", "north");
            f.player.compensatedWorld.updateBlock(base, piston);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(0, FLOOR_Y, 1),
                    slime ? BlockIds.SLIME_BLOCK.defaultState() : BlockIds.STONE.defaultState());
            if (slime)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 1), BlockIds.AIR.defaultState());
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            blockEvent(f, base, 0, 2, piston);
            f.rideTick();
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            // Entity#limitPistonMovement caps all movers together at .51 per axis in this game tick.
            Vec3 shoved = seat.add(0, 0, -.51);
            assertPosition(shoved, boat);
            assertEquals(slime ? -1 : 0, boat.deltaMovement.z, 1e-13);
            f.player.compensatedEntities.updatePassengerPositions();
            assertPosition(shoved, boat);
            // Remove moving blocks, then remove the minecart: Entity#setRemoved ejects without a passenger packet.
            f.player.compensatedWorld.updateBlock(base, BlockIds.AIR.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 1), BlockIds.AIR.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.AIR.defaultState());
            f.player.compensatedEntities.removeEntity(MINECART);
            assertPosition(shoved, boat);
            assertExact(f.rideTick(shoved.add(0, -.08, slime ? -(double) .9F : 0), false));
        }
    }

    @ParameterizedTest(name = "{0}: a shulker box leaves the boat off its seat")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aShulkerBoxMoveSurvivesEjection(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 seat = boardRiddenBoat(f, NONE);
            var registry = DataTables.defaults().registry();
            BlockPos pos = new BlockPos(0, FLOOR_Y, 1);
            int box = registry.with(registry.block("minecraft:shulker_box").defaultState(), "facing", "north");
            f.player.compensatedWorld.updateBlock(pos, box);
            blockEvent(f, pos, 1, 1, box);
            f.rideTick();
            // moveCollidedEntities uses the full progress delta (.1F), then adds .01;
            // getBoundingBox alone halves progress for the physical lid shape.
            double push = (double) .1F + .01;
            Vec3 shoved = seat.add(0, 0, -push);
            PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
            assertPosition(shoved, boat);
            f.player.compensatedWorld.updateBlock(pos, BlockIds.AIR.defaultState());
            eject(f);
            assertExact(f.rideTick(shoved.add(0, -.08, 0), false));
        }
    }

    @ParameterizedTest(name = "{0}: a later shulker entity moves the seated boat")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aLaterShulkerPeekMoveSurvivesEjection(String release, ClientVersion version) throws Exception {
        shulkerEjection(version, false);
    }

    @ParameterizedTest(name = "{0}: an earlier shulker moves before rideTick and positionRider")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anEarlierShulkerPeekIsReplacedOnlyByPositionRider(String release, ClientVersion version) throws Exception {
        shulkerEjection(version, true);
    }

    private static void shulkerEjection(ClientVersion version, boolean early) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            board(f, AT, AT.add(0, .3, 0));
            for (int tick = 0; tick < 6; tick++) f.rideTick();
            Vec3 seat = f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition;
            // Shulkers stand on integer Y. Here y=65 meets the boat's top (65.05), above its minecart (65).
            f.player.compensatedEntities.addEntity(
                    102, EntityTypeIds.SHULKER, new Vec3(seat.x, FLOOR_Y + 1, seat.z + 1.19), 0, 0, 0);
            var shulker = (PacketEntityShulker) f.player.compensatedEntities.getEntity(102);
            shulker.onShapeData(Direction.SOUTH, (byte) 100);
            if (early) shulker.setClientTickOrder(-100);
            double push = shulker.peekMove().movement().z;
            assertTrue(push < 0);
            f.rideTick();
            Vec3 shoved = early ? seat : seat.add(0, 0, push);
            assertPosition(shoved, f.player.compensatedEntities.getEntity(BOAT));
            f.player.compensatedEntities.removeEntity(102);
            eject(f);
            assertExact(f.rideTick(shoved.add(0, -.08, 0), false));
        }
    }

    @ParameterizedTest(name = "{0}: a pre-tick shulker shove changes the first travel's ground contact")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void anEarlierPeekIsAppliedBeforeTheBoatSamplesItsStatus(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(.5, FLOOR_Y, -1.375F / 2 + .0025);
            board(f, start, AT.add(0, 4, 0));
            // Only the .0025 strip of the boat over z=0 is supported. The shulker's first .006155... move
            // takes it off that edge before getStatus and move, so the first ridden tick keeps its gravity.
            for (int x = -1; x <= 1; x++)
                for (int z = -3; z < 0; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
            f.player.compensatedEntities.addEntity(102, EntityTypeIds.SHULKER, start.add(0, 0, 1.19), 0, 0, 0);
            var shulker = (PacketEntityShulker) f.player.compensatedEntities.getEntity(102);
            shulker.onShapeData(Direction.SOUTH, (byte) 100);
            shulker.setClientTickOrder(-100);
            f.rideTick();
            var boat = f.player.compensatedEntities.getEntity(BOAT);
            assertEquals(-.04, boat.deltaMovement.y, 1e-13);
            f.player.compensatedEntities.removeEntity(102);
            Vec3 seat = AT.add(0, 4 + .1875, 0);
            eject(f);
            assertExact(f.rideTick(seat.add(0, -.08, 0), false));
        }
    }

    @Test
    void aLivingEntityCannotPushABoatCarryingAPlayer() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observeOnly()) {
            Vec3 seat = boardRiddenBoat(f, NONE);
            f.player.compensatedEntities.addEntity(102, EntityTypeIds.ZOMBIE, seat.add(0, -.1, .5), 0, 0, 0);
            f.rideTick();
            assertEquals(0, f.player.compensatedEntities.getEntity(BOAT).deltaMovement.z, 1e-13);
            f.player.compensatedEntities.removeEntity(102);
            eject(f);
            assertExact(f.rideTick(seat.add(0, -.08, 0), false));
        }
    }

    private static void assertPosition(Vec3 expected, PacketEntity entity) {
        assertEquals(expected.x, entity.clientPhysicalPosition.x, 1e-10);
        assertEquals(expected.y, entity.clientPhysicalPosition.y, 1e-10);
        assertEquals(expected.z, entity.clientPhysicalPosition.z, 1e-10);
        var box = entity.getPossibleMovementCollisionBoxes();
        assertEquals(expected.x, (box.minX + box.maxX) / 2, 1e-10);
        assertEquals(expected.y, box.minY, 1e-10);
        assertEquals(expected.z, (box.minZ + box.maxZ) / 2, 1e-10);
    }

    private static void board(JavaTickFixture f, Vec3 boatAt, Vec3 cartAt) {
        f.place(boatAt, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(MINECART, minecart(), cartAt, 0, 0, 0);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, boatAt, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.setPassengers(MINECART, List.of(BOAT));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NONE);
    }

    private static void blockEvent(JavaTickFixture f, BlockPos pos, int action, int parameter, int state) {
        var packet = new ClientboundBlockEvent(
                pos, action, parameter, DataTables.defaults().registry().blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * Puts the player on a new boat and that boat on a new minecart standing on the floor, then runs six ridden ticks
     * holding {@code keys}, past the vehicle-switch window; returns the seat the boat ends on.
     */
    private static Vec3 boardRiddenBoat(JavaTickFixture f, ServerboundPlayerInput keys) throws Exception {
        f.place(AT, Vec3.ZERO);
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.player.compensatedEntities.addEntity(MINECART, minecart(), AT, 0, 0, 0);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, AT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.setPassengers(MINECART, List.of(BOAT));
        f.pongThrough(f.player.lastTransactionSent.get());
        PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
        assertSame(f.player.compensatedEntities.getEntity(MINECART), boat.getRiding());
        f.input(keys);
        for (int tick = 0; tick < 6; tick++) f.rideTick();
        Vec3 seat = boat.clientPhysicalPosition;
        // Minecart's passengerAttachments(0.1875F): the free tick's 0.08 fall stays clear of the floor.
        assertEquals(FLOOR_Y + (double) 0.1875F, seat.y, 1e-9, "the boat's seat on the minecart");
        assertEquals(AT.x, seat.x, 1e-9);
        assertEquals(AT.z, seat.z, 1e-9);
        return seat;
    }

    private static void eject(JavaTickFixture f) {
        f.setPassengers(MINECART, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        assertEquals(null, f.player.compensatedEntities.getEntity(BOAT).getRiding());
    }

    /** A passenger Rot, a MoveVehicle to {@code to} after it and the tick end; the MoveVehicle's event. */
    private static PacketReceiveEvent<ServerboundMoveVehicle> rideTick(JavaTickFixture f, Vec3 to, boolean onGround) {
        var player = f.player;
        var rot = new ServerboundMovePlayer(0, 0, 0, player.xRot, player.yRot, onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), player.xRot, player.yRot, onGround, true);
        var event = f.event(ServerboundPackets.MOVE_VEHICLE, move);
        f.listener.onMoveVehicle(event, player, move);
        f.tickEnd();
        return event;
    }

    private static int minecart() {
        for (EntityTypes.Type type : EntityTypes.defaults().types())
            if (type.key().equals("minecraft:minecart")) return type.id();
        throw new IllegalStateException("minecraft:minecart");
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    /** Observe-only mode, so a mount or dismount sends no resync teleport. */
    private static AutoCloseable observeOnly() throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, true);
        return () -> field.setBoolean(manager, original);
    }
}
