package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.visibility.PlaceVisibility;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A standing camel's driver sits with its eye 3.02 above the camel's feet, in the block layer just above the camel's
 * 2.375-tall box. BlockItem#canPlace asks Level#isUnobstructed, whose EntityGetter#isUnobstructed(null, shape) tests
 * every building-blocking entity's box, the local rider's own seat box included. A block the client refuses there
 * must not be predicted, or the next pick through that cell is judged against a block the client never had.
 */
class CamelRiderPlacementTest {
    private static final int CAMEL = 72;
    private static final int LAST_POSE_CHANGE_TICK = 20;
    private static final long GAME_TIME = 1027398L;
    // Facing east, the camel at (0.3, 64, 0.6) seats its driver at (0.8, 65.4, 0.6): the rider's box spans x 0.5 to
    // 1.1, y 65.4 to 67.2 and z 0.3 to 0.9, and its eye is at (0.8, 67.02, 0.6) in the air cell (0, 67, 0).
    private static final Vec3 CAMEL_AT = new Vec3(0.3, FLOOR_Y, 0.6);
    private static final float EAST = -90F;
    private static final BlockPos HEAD = new BlockPos(0, FLOOR_Y + 3, 0);

    @Test
    void theDriverCannotPlaceIntoItsOwnHeadAndKeepsSeeingTheFaceBehindIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f);
            BlockPos leaves = new BlockPos(1, FLOOR_Y + 3, 0);
            block(f, leaves, "spruce_leaves");
            var visibility = f.player.checkManager.getCheck(PlaceVisibility.class);
            visibility.setEnabled(true);

            // Holding use repeats every four ticks; each use targets the rider's head cell from the leaves' west face.
            for (int use = 1; use <= 3; use++) {
                assertFalse(use(f, leaves, Direction.WEST, use).isCancelled(), "use " + use);
                assertEquals(state("air"), f.player.compensatedWorld.getBlockStateIdAt(HEAD), "use " + use);
                for (int tick = 0; tick < 4; tick++) assertExact(ride(f));
            }
            assertEquals(0, visibility.violations);
        }
    }

    @Test
    void theDriverStillPlacesBesideItsOwnBox() throws Exception {
        try (var f = new JavaTickFixture()) {
            mount(f);
            BlockPos leaves = new BlockPos(-2, FLOOR_Y + 3, 0);
            block(f, leaves, "spruce_leaves");
            var visibility = f.player.checkManager.getCheck(PlaceVisibility.class);
            visibility.setEnabled(true);

            // (-1, 67, 0) lies above the camel's box and beside the rider's.
            assertFalse(use(f, leaves, Direction.EAST, 1).isCancelled());
            assertEquals(state("stone"), f.player.compensatedWorld.getBlockStateIdAt(leaves.east()));
            assertEquals(0, visibility.violations);
        }
    }

    /** A saddled camel that stood up long ago, driven by the local player holding stone. */
    private static void mount(JavaTickFixture f) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(CAMEL_AT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, CAMEL_AT, EAST, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        f.tickEnd();
        // LivingEntity#travelInAir: a grounded camel carries (0 - 0.08) * 0.98F.
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, -0.0784000015258789, 0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        for (int tick = 0; tick < 3; tick++) assertExact(ride(f));
        f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:stone", 64));
    }

    /** One client tick of a driver that holds no key: its Rot, the camel's move in place and the tick end. */
    private static PredictionResult ride(JavaTickFixture f) {
        PredictionResult previous =
                f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, EAST, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(CAMEL_AT.x, CAMEL_AT.y, CAMEL_AT.z), EAST, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != previous, "the vehicle move was not predicted");
        return result;
    }

    /** Minecraft#startUseItem's use_item_on, through the early checks and then the placement prediction. */
    private static PacketReceiveEvent<ServerboundUseItemOn> use(
            JavaTickFixture f, BlockPos pos, Direction face, int sequence) {
        var cursor = new Vec3d(face == Direction.EAST ? 1 : face == Direction.WEST ? 0 : .5, .5, .5);
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

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
