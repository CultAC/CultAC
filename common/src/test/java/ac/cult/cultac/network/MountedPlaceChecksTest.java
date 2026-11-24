package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsG;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.scaffolding.FarPlace;
import ac.cult.cultac.checks.impl.scaffolding.MultiPlace;
import ac.cult.cultac.checks.impl.scaffolding.RotationPlace;
import ac.cult.cultac.checks.impl.visibility.PlaceVisibility;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Minecraft#tick runs handleKeybinds, whose places pick from where the last Entity#rideTick left the rider, before
 * ClientLevel#tickEntities. A rider's places are judged by the same checks as a player's on foot.
 */
class MountedPlaceChecksTest {
    private static final int CAMEL = 72;
    private static final int BOAT = 100;
    private static final long GAME_TIME = 1027398L;
    // A standing camel facing east at (0.3, 64, 0.6) seats its driver's eye at (0.8, 67.02, 0.6).
    private static final Vec3 CAMEL_AT = new Vec3(0.3, FLOOR_Y, 0.6);
    private static final float EAST = -90F;
    private static final Vec3 BOAT_AT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double STANDING_EYE = 1.62;
    private static final List<BlockPos> FLOOR = List.of(
            new BlockPos(2, FLOOR_Y - 1, 0),
            new BlockPos(0, FLOOR_Y - 1, 3),
            new BlockPos(0, FLOOR_Y - 1, -2),
            new BlockPos(-2, FLOOR_Y - 1, 0),
            new BlockPos(2, FLOOR_Y - 1, 3),
            new BlockPos(2, FLOOR_Y - 1, -2));

    @Test
    void aRiderPlacingOnSixBlocksInOneTickIsFlaggedAndCancelled() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.setExperimentalChecks(true);
            mountCamel(f);
            var multi = enable(f, MultiPlace.class);
            List<PacketReceiveEvent<ServerboundUseItemOn>> events = new ArrayList<>();
            int sequence = 1;
            for (BlockPos pos : FLOOR) events.add(use(f, pos, Direction.UP, new Vec3d(.5, 1, .5), sequence++));
            assertTrue(multi.violations >= 5, "MultiPlace violations " + multi.violations);
            assertTrue(events.getLast().isCancelled());
        }
    }

    @Test
    void aRiderPlacingOnAFaceBehindItIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.setExperimentalChecks(true);
            mountCamel(f);
            var rotation = enable(f, RotationPlace.class);
            BlockPos leaves = new BlockPos(-2, FLOOR_Y + 3, 0);
            block(f, leaves, "spruce_leaves");

            use(f, leaves, Direction.EAST, new Vec3d(1, 0.02, 0.6), 1);
            rideCamel(f);

            assertTrue(rotation.violations > 0, "RotationPlace violations " + rotation.violations);
        }
    }

    @Test
    void aRiderPlacingOnTheFaceItLooksAtIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.setExperimentalChecks(true);
            mountCamel(f);
            var rotation = enable(f, RotationPlace.class);
            var far = enable(f, FarPlace.class);
            var multi = enable(f, MultiPlace.class);
            var visibility = enable(f, PlaceVisibility.class);
            var hands = enable(f, MultiActionsG.class);
            BlockPos leaves = new BlockPos(3, FLOOR_Y + 3, 0);
            block(f, leaves, "spruce_leaves");

            assertFalse(use(f, leaves, Direction.WEST, new Vec3d(0, 0.02, 0.6), 1).isCancelled());
            for (int tick = 0; tick < 3; tick++) rideCamel(f);

            assertEquals(state("stone"), f.player.compensatedWorld.getBlockStateIdAt(leaves.west()));
            assertEquals(0, rotation.violations, "RotationPlace");
            assertEquals(0, far.violations, "FarPlace");
            assertEquals(0, multi.violations, "MultiPlace");
            assertEquals(0, visibility.violations, "PlaceVisibility");
            assertEquals(0, hands.violations, "MultiActionsG");
        }
    }

    @Test
    void aBoatDriverPlacingWhileHoldingASteeringKeyIsFlagged() throws Exception {
        try (var f = boardBoat(ClientVersion.V_1_21_11)) {
            var hands = enable(f, MultiActionsG.class);
            BlockPos stone = new BlockPos(0, FLOOR_Y + 1, 3);
            block(f, stone, "stone");
            f.input(LEFT);
            rot(f, 0, 0);
            moveBoat(f, 0);
            f.tickEnd();

            use(f, stone, Direction.NORTH, new Vec3d(.5, .5, 0), 1);

            assertTrue(hands.violations > 0, "MultiActionsG violations " + hands.violations);
        }
    }

    /**
     * A driver releases the left key and its boat keeps turning by AbstractBoat#floatBoat's decaying deltaRotation. It
     * places on a face it looks at near the face's edge, where the boat's turn of that tick would carry the look off it.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aBoatDriverPlacingOnTheFaceItLooksAtWhileItsBoatTurnsIsNotFlagged(ClientVersion version) throws Exception {
        try (var f = boardBoat(version)) {
            var rotation = enable(f, RotationPlace.class);
            var far = enable(f, FarPlace.class);
            var visibility = enable(f, PlaceVisibility.class);
            var hands = enable(f, MultiActionsG.class);
            BlockPos stone = new BlockPos(0, FLOOR_Y + 1, 3);
            block(f, stone, "stone");
            Vec3 aim = new Vec3(0.97, FLOOR_Y + 1.5, 3);
            boolean sendsTurnOfItsTick = !version.isOlderThan(ClientVersion.V_26_3);
            float boatYaw = 0;
            float turn = 0;
            int placeTick = 5;
            int releaseTick = 4;
            for (int tick = 0; tick < 8; tick++) {
                Vec3 origin = f.player.compensatedEntities.pickPositions().getFirst();
                double dx = aim.x - origin.x;
                double dy = aim.y - (origin.y + STANDING_EYE);
                double dz = aim.z - origin.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
                if (tick == placeTick) {
                    var cursor = new Vec3d(aim.x - stone.x(), aim.y - stone.y(), 0);
                    assertFalse(use(f, stone, Direction.NORTH, cursor, 1).isCancelled());
                }
                if (tick == 0) f.input(LEFT);
                if (tick == releaseTick) f.input(RELEASED);
                turn *= landFriction();
                if (tick >= 1 && tick <= releaseTick) turn--;
                boatYaw += turn;
                rot(f, sendsTurnOfItsTick ? clampRotation(yaw + turn, boatYaw) : yaw, pitch);
                moveBoat(f, boatYaw);
                f.tickEnd();
            }

            assertEquals(state("stone"), f.player.compensatedWorld.getBlockStateIdAt(stone.north()));
            assertEquals(0, rotation.violations, "RotationPlace");
            assertEquals(0, far.violations, "FarPlace");
            assertEquals(0, visibility.violations, "PlaceVisibility");
            assertEquals(0, hands.violations, "MultiActionsG");
        }
    }

    /**
     * Before 26.3 a driver's Rot goes out before AbstractBoat#positionRider turns it by the boat's deltaRotation, so a
     * still mouse picks the next tick from the turned yaw. After a place behind it, the driver places on a face edge
     * that its turned yaw looks at and the yaws it sent do not.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_2"})
    void aBoatDriverPlacingWithTheLookItsBoatTurnedItToIsNotFlagged(ClientVersion version) throws Exception {
        try (var f = boardBoat(version)) {
            var rotation = enable(f, RotationPlace.class);
            BlockPos behind = new BlockPos(0, FLOOR_Y + 1, -3);
            BlockPos stone = new BlockPos(0, FLOOR_Y + 1, 3);
            block(f, behind, "stone");
            block(f, stone, "stone");
            Vec3 aim = new Vec3(0.03, FLOOR_Y + 1.5, 3);
            int placeTick = 5;
            int releaseTick = 4;
            float[] turns = new float[placeTick + 1];
            float turn = 0;
            for (int tick = 0; tick <= placeTick; tick++) {
                turn *= landFriction();
                if (tick >= 1 && tick <= releaseTick) turn--;
                turns[tick] = turn;
            }
            float boatYaw = 0;
            double flagged = 0;
            for (int tick = 0; tick <= placeTick; tick++) {
                Vec3 origin = f.player.compensatedEntities.pickPositions().getFirst();
                double dx = aim.x - origin.x;
                double dy = aim.y - (origin.y + STANDING_EYE);
                double dz = aim.z - origin.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
                if (tick == 0) use(f, behind, Direction.SOUTH, new Vec3d(.5, .5, 1), 1);
                if (tick == placeTick) {
                    var cursor = new Vec3d(aim.x - stone.x(), aim.y - stone.y(), 0);
                    assertFalse(use(f, stone, Direction.NORTH, cursor, 2).isCancelled());
                }
                if (tick == 0) f.input(LEFT);
                if (tick == releaseTick) f.input(RELEASED);
                boatYaw += turns[tick];
                float sent = yaw;
                if (tick == placeTick - 1) sent = yaw - turns[tick];
                if (tick == placeTick - 2) sent = yaw - turns[tick + 1] - turns[tick];
                rot(f, sent, pitch);
                moveBoat(f, boatYaw);
                f.tickEnd();
                if (tick == 0) flagged = rotation.violations;
            }

            assertTrue(flagged > 0, "RotationPlace violations after the place behind " + flagged);
            assertEquals(flagged, rotation.violations, "RotationPlace");
            assertEquals(state("stone"), f.player.compensatedWorld.getBlockStateIdAt(stone.north()));
        }
    }

    private static <T extends Check> T enable(JavaTickFixture f, Class<T> type) {
        T check = f.player.checkManager.getCheck(type);
        check.setEnabled(true);
        return check;
    }

    /** A saddled camel that stood up long ago, driven by the local player holding stone. */
    private static void mountCamel(JavaTickFixture f) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(CAMEL_AT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, CAMEL_AT, EAST, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL, List.of(new EntityMetadata.Entry(20, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(RELEASED);
        f.tickEnd();
        // LivingEntity#travelInAir: a grounded camel carries (0 - 0.08) * 0.98F.
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, -0.0784000015258789, 0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        for (int tick = 0; tick < 3; tick++) rideCamel(f);
        f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:stone", 64));
    }

    /** One client tick of a camel driver that holds no key and looks east: its Rot, the camel's move and tick end. */
    private static void rideCamel(JavaTickFixture f) {
        rot(f, EAST, 0);
        var move = new ServerboundMoveVehicle(new Vec3d(CAMEL_AT.x, CAMEL_AT.y, CAMEL_AT.z), EAST, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
    }

    /** A client of {@code version} holding stone, the only passenger of an oak boat at yaw 0 on stone. */
    private static JavaTickFixture boardBoat(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.setExperimentalChecks(true);
        f.place(BOAT_AT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:stone", 64));
        return f;
    }

    private static void rot(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static void moveBoat(JavaTickFixture f, float boatYaw) {
        var packet = new ServerboundMoveVehicle(new Vec3d(BOAT_AT.x, BOAT_AT.y, BOAT_AT.z), boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
    }

    // AbstractBoat#getGroundFriction: the mean friction of the nine stone blocks under the boat.
    private static float landFriction() {
        float friction = 0;
        for (int block = 0; block < 9; block++) friction += 0.6F;
        return friction / 9;
    }

    // AbstractBoat#clampRotation's yaw for a player passenger, in Mth's float operations.
    private static float clampRotation(float yaw, float boatYaw) {
        float delta = (yaw - boatYaw) % 360.0F;
        if (delta >= 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        float target = delta < -105.0F ? -105.0F : Math.min(delta, 105.0F);
        return yaw + target - delta;
    }

    /** Minecraft#startUseItem's use_item_on, through the early checks and then the placement prediction. */
    private static PacketReceiveEvent<ServerboundUseItemOn> use(
            JavaTickFixture f, BlockPos pos, Direction face, Vec3d cursor, int sequence) {
        var packet = new ServerboundUseItemOn(Hand.MAIN_HAND, pos, face, cursor, false, false, sequence);
        var event =
                new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.USE_ITEM_ON, packet);
        f.player.checkManager.dispatchEarlyReceive(event);
        if (!event.isCancelled()) f.listener.onUseItemOn(event, f.player, packet);
        return event;
    }

    private static void block(JavaTickFixture f, BlockPos pos, String name) {
        f.player.compensatedWorld.applyBlockChangeRawDANGER(pos.x(), pos.y(), pos.z(), state(name));
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }
}
