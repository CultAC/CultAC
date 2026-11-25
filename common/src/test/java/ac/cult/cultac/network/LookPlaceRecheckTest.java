package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A use-on straight below the eye gives no yaw, so it is predicted with the last yaw sent and, when its effects read
 * the yaw, again with the yaw its client tick sends. The player stands in the cell the door goes into: facing west the
 * door clears its box, facing east the box obstructs it.
 */
class LookPlaceRecheckTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final Vec3 STANDING = new Vec3(0.4386616011393, FLOOR_Y, 0.300000011920929);
    private static final float WEST = 72.885F;
    private static final float EAST = -90;

    @Test
    void aDoorPlacedStraightDownFacesTheYawItsTickSends() throws Exception {
        try (var f = fixture(EAST)) {
            held(f, "cherry_door");
            useOn(f, Hand.MAIN_HAND);
            assertEquals("minecraft:air", block(f, FLOOR_Y), "the last yaw sent faces the door into the player");
            assertEquals(5, f.player.getInventory().getHeldItem().count());

            move(f, WEST);
            assertDoor(f, "west");
            assertEquals(4, f.player.getInventory().getHeldItem().count());
        }
    }

    @Test
    void aDoorTheTicksYawObstructsIsTakenBack() throws Exception {
        try (var f = fixture(WEST)) {
            held(f, "cherry_door");
            useOn(f, Hand.MAIN_HAND);
            assertDoor(f, "west");
            assertEquals(4, f.player.getInventory().getHeldItem().count());

            move(f, EAST);
            assertEquals("minecraft:air", block(f, FLOOR_Y));
            assertEquals("minecraft:air", block(f, FLOOR_Y + 1));
            assertEquals(5, f.player.getInventory().getHeldItem().count());
        }
    }

    @Test
    void thePlacementPredictedAgainEndsWithItsAcknowledgement() throws Exception {
        for (float lastYaw : new float[] {EAST, WEST}) {
            try (var f = fixture(lastYaw)) {
                held(f, "cherry_door");
                useOn(f, Hand.MAIN_HAND);
                move(f, lastYaw == EAST ? WEST : EAST);
                var world = f.player.compensatedWorld;
                assertTrue(world.hasPendingBlockPrediction(new BlockPos(0, FLOOR_Y, 0)));

                // The server placed nothing: its acknowledgement ends every cell either prediction wrote.
                world.handlePredictionConfirmation(1);
                f.pongThrough(f.player.lastTransactionSent.get());
                assertFalse(world.hasPendingBlockPredictions(), "a prediction outlived its acknowledgement");
                assertEquals("minecraft:air", block(f, FLOOR_Y));
                assertEquals("minecraft:air", block(f, FLOOR_Y + 1));
            }
        }
    }

    @Test
    void aUseItemOfTheSameClickGivesTheOffHandPlacementItsYaw() throws Exception {
        try (var f = fixture(EAST)) {
            held(f, "stick");
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_OFFHAND, OfflineCultTestBootstrap.item("minecraft:cherry_door", 5));
            useOn(f, Hand.MAIN_HAND);
            f.useItem(Hand.MAIN_HAND, WEST, 90);
            useOn(f, Hand.OFF_HAND);
            assertDoor(f, "west");
        }
    }

    @Test
    void aPlacementThatDoesNotReadTheYawIsNotPredictedAgain() throws Exception {
        try (var f = fixture(EAST)) {
            held(f, "stone_pressure_plate");
            useOn(f, Hand.MAIN_HAND);
            assertEquals("minecraft:stone_pressure_plate", block(f, FLOOR_Y));

            // A second prediction would put the plate back.
            f.player.compensatedWorld.applyBlockChangeRawDANGER(0, FLOOR_Y, 0, 0);
            move(f, WEST);
            assertEquals("minecraft:air", block(f, FLOOR_Y));
            assertEquals(4, f.player.getInventory().getHeldItem().count());
        }
    }

    @Test
    void theEndOfTheClientTickKeepsThePlacementOfTheLastYawSent() throws Exception {
        try (var f = fixture(EAST)) {
            held(f, "cherry_door");
            useOn(f, Hand.MAIN_HAND);
            f.tickEnd();

            move(f, WEST);
            assertEquals("minecraft:air", block(f, FLOOR_Y), "the next tick's yaw is not the click's");
            assertEquals(5, f.player.getInventory().getHeldItem().count());
        }
    }

    private static JavaTickFixture fixture(float lastYaw) throws Exception {
        var f = new JavaTickFixture();
        f.place(STANDING, Vec3.ZERO);
        f.player.packetStateData.clientSidePosition = STANDING;
        f.player.xRot = lastYaw;
        f.player.yRot = 90;
        return f;
    }

    private static void held(JavaTickFixture f, String item) {
        f.player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:" + item, 5));
    }

    /** The click of a camera looking straight down: the cursor is the eye's own offset in the block below. */
    private static void useOn(JavaTickFixture f, Hand hand) {
        var packet = new ServerboundUseItemOn(
                hand,
                new BlockPos(0, FLOOR_Y - 1, 0),
                Direction.UP,
                new Vec3d((float) STANDING.x, 1, (float) STANDING.z),
                false,
                false,
                hand == Hand.MAIN_HAND ? 1 : 3);
        f.listener.onUseItemOn(f.event(ServerboundPackets.USE_ITEM_ON, packet), f.player, packet);
    }

    private static void move(JavaTickFixture f, float yaw) {
        var packet = new ServerboundMovePlayer(STANDING.x, STANDING.y, STANDING.z, yaw, 90, true, false, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static String block(JavaTickFixture f, int y) {
        return REGISTRY.block(f.player.compensatedWorld.getBlockStateIdAt(0, y, 0))
                .key();
    }

    private static void assertDoor(JavaTickFixture f, String facing) {
        int lower = f.player.compensatedWorld.getBlockStateIdAt(0, FLOOR_Y, 0);
        int upper = f.player.compensatedWorld.getBlockStateIdAt(0, FLOOR_Y + 1, 0);
        assertEquals("minecraft:cherry_door", REGISTRY.block(lower).key());
        assertEquals("minecraft:cherry_door", REGISTRY.block(upper).key());
        assertEquals("lower", REGISTRY.value(lower, "half"));
        assertEquals("upper", REGISTRY.value(upper, "half"));
        assertEquals(facing, REGISTRY.value(lower, "facing"));
        assertEquals(facing, REGISTRY.value(upper, "facing"));
    }
}
