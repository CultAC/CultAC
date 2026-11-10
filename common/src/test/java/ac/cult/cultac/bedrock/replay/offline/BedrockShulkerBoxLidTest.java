package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.player.BedrockShulkerBoxLids;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.Map;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A Bedrock player's shulker box: the lid steps 0.05 per client tick towards the side the last block event named, and
 * the box's face on its facing side stands {@code 0.5 * (1 - (1 - openness)^3)} out from the block.
 */
public class BedrockShulkerBoxLidTest {
    private static final BlockPos BOX = new BlockPos(3, 119, 5);
    private static final int UP = shulkerBox("up");
    private static final float GRAVITY_TICK = -0.0784F;

    // The top of the box above its block as its lid closes, from 0.70 open to shut.
    private static final double[] CLOSING = {
        1.48650, 1.47856, 1.46800, 1.45444, 1.43750, 1.41681, 1.39200, 1.36269, 1.32850, 1.28906, 1.24400, 1.19294,
        1.13550, 1.07131, 1.00000
    };

    @Test
    public void theLidRaisesTheTopOfTheBox() {
        for (int i = 0; i < CLOSING.length; i++) {
            float openness = 0.70F - 0.05F * i;
            assertEquals("openness " + openness, CLOSING[i], 1.0 + BedrockShulkerBoxLids.lift(openness), 1.0E-5);
        }
        assertEquals(1.5, 1.0 + BedrockShulkerBoxLids.lift(1.0F), 0.0);
        assertEquals(1.0, 1.0 + BedrockShulkerBoxLids.lift(0.0F), 0.0);
    }

    @Test
    public void aHandledEventMovesTheLidOneStepPerClientTickAndItStaysOpen() {
        var lids = new BedrockShulkerBoxLids();
        long box = BOX.asLong();
        lids.blockEvent(box, true);
        assertEquals("the tick after the event still meets the closed box", 0.0F, lids.openness(box), 0.0F);
        for (int tick = 1; tick <= BedrockShulkerBoxLids.OPEN_STEPS + 5; tick++) {
            lids.tick(position -> true);
            assertEquals(Math.min(tick, 20) * 0.05F, lids.openness(box), 1.0E-6F);
        }
        lids.blockEvent(box, true);
        lids.tick(position -> true);
        assertEquals("another viewer leaves it open", 1.0F, lids.openness(box), 0.0F);

        lids.blockEvent(box, false);
        assertEquals(1.0F, lids.openness(box), 0.0F);
        for (int tick = 1; tick <= 6; tick++) lids.tick(position -> true);
        assertEquals(0.70F, lids.openness(box), 1.0E-6F);
        lids.blockEvent(box, true);
        lids.tick(position -> true);
        assertEquals("a lid reopened on its way down turns around where it is", 0.75F, lids.openness(box), 1.0E-6F);

        lids.tick(position -> false);
        assertEquals("a replaced box has no lid", 0.0F, lids.openness(box), 0.0F);
    }

    /** Feet heights on the lid by client tick, tick 1 being the first one after the block event is handled. */
    @Test
    public void theFirstTickAfterAnEventMeetsTheLidWhereItWasAndEachLaterOneAStepOn() {
        var lids = new BedrockShulkerBoxLids();
        lids.blockEvent(BOX.asLong(), true);
        assertFeetByTick(lids, new double[][] {
            {1, 120.00001}, {8, 120.36270}, {10, 120.41682}, {12, 120.45445}, {18, 120.49832}, {21, 120.5}, {30, 120.5}
        });
        lids.blockEvent(BOX.asLong(), false);
        assertFeetByTick(lids, new double[][] {
            {1, 120.50001}, {2, 120.49995}, {9, 120.46801}, {18, 120.19295}, {20, 120.07132}, {21, 120.0}
        });
    }

    /** Steps {@code lids} through the ticks up to the last of {@code feetByTick}, each a tick and the feet in it. */
    private static void assertFeetByTick(BedrockShulkerBoxLids lids, double[][] feetByTick) {
        int next = 0;
        for (int tick = 1; next < feetByTick.length; tick++) {
            if (tick == (int) feetByTick[next][0]) {
                double top = BOX.getY() + 1.0 + lids.lift(BOX.asLong());
                assertEquals("tick " + tick, feetByTick[next][1], top, 2.0E-5);
                next++;
            }
            lids.tick(position -> true);
        }
    }

    /**
     * The face on a box's facing side, which a player walking or jumping into it meets, moves out along the facing by
     * the lid's lift; the other five faces stay the block's.
     */
    @Test
    public void everyFacingMovesItsFaceOutWithTheLid() {
        // facing, openness, the face's coordinate past the block's own face on that side
        assertLidFace("up", 0.70F, 0.4865);
        assertLidFace("east", 0.60F, 0.468);
        assertLidFace("north", 0.75F, 0.49219);
        assertLidFace("south", 0.55F, 0.45444);
        assertLidFace("west", 0.95F, 0.49994);
        assertLidFace("down", 0.10F, 0.1355);
        assertLidFace("north", 0.0F, 0.0);
    }

    private static void assertLidFace(String facing, float openness, double lift) {
        float shown = BedrockShulkerBoxLids.lift(openness);
        var context = new ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext(
                position -> shown,
                ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext.NONE.movingBlockBoxes(),
                ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame.IDENTITY);
        var builder = new BedrockCollisionWorldBuilder(BedrockClientBlockShapeMappings.catalog(), context);
        var position = new BlockPosition(BOX.getX(), BOX.getY(), BOX.getZ());
        var boxes = builder.build(Map.of(position, shulkerBox(facing)))
                .blockAt(position)
                .orElseThrow()
                .collisionBoxes();
        assertEquals(1, boxes.size());
        var box = boxes.getFirst();
        double[] expected = {BOX.getX(), BOX.getY(), BOX.getZ(), BOX.getX() + 1, BOX.getY() + 1, BOX.getZ() + 1};
        int side = switch (facing) {
            case "west" -> 0;
            case "down" -> 1;
            case "north" -> 2;
            case "east" -> 3;
            case "up" -> 4;
            default -> 5;
        };
        expected[side] += side < 3 ? -lift : lift;
        double[] actual = {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()};
        for (int i = 0; i < 6; i++) assertEquals(facing + " " + openness + " face " + i, expected[i], actual[i], 1.0E-5);
    }

    /** ShulkerBoxBlockEntity#triggerEvent: the event type is 1 and its parameter is the number of viewers. */
    @Test
    public void anyViewerOpensTheLidAndNoViewerClosesIt() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            placeBox(player);
            var lids = player.bedrockState.shulkerBoxLids;
            blockEvent(player, 0, 1);
            player.checkManager.endBedrockClientTick();
            assertEquals("another event type leaves the lid shut", 0.0F, lids.openness(BOX.asLong()), 0.0F);

            blockEvent(player, 1, 1);
            player.checkManager.endBedrockClientTick();
            assertEquals(0.05F, lids.openness(BOX.asLong()), 1.0E-6F);
            blockEvent(player, 1, 2);
            player.checkManager.endBedrockClientTick();
            assertEquals("a second viewer keeps it opening", 0.10F, lids.openness(BOX.asLong()), 1.0E-6F);
            blockEvent(player, 1, 1);
            player.checkManager.endBedrockClientTick();
            assertEquals("one of two viewers leaving keeps it opening", 0.15F, lids.openness(BOX.asLong()), 1.0E-6F);
            blockEvent(player, 1, 0);
            player.checkManager.endBedrockClientTick();
            assertEquals("the last viewer leaving closes it", 0.10F, lids.openness(BOX.asLong()), 1.0E-6F);
            assertTrue("the lid adds no push envelope", player.compensatedWorld.openShulkerBoxes.isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** A player standing on a lid that closes under it is lowered with it, one lid step per tick. */
    @Test
    public void aPlayerOnAClosingLidIsLoweredWithIt() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            placeBox(player);
            blockEvent(player, 1);
            for (int tick = 0; tick < 20; tick++) player.checkManager.endBedrockClientTick();
            blockEvent(player, 0);
            for (int tick = 0; tick < 6; tick++) player.checkManager.endBedrockClientTick();
            assertEquals(0.70F, player.bedrockState.shulkerBoxLids.openness(BOX.asLong()), 1.0E-6F);

            standAt(player, BOX.getY() + CLOSING[0]);
            for (int i = 0; i < CLOSING.length; i++) {
                frame(player, 37827 + i, BOX.getY() + CLOSING[i], GRAVITY_TICK, true);
                player.checkManager.endBedrockClientTick();
            }
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /**
     * A player standing on a box that opens: the tick after the event still stands on the closed box, and the lid's
     * first step, 0.0713 high, takes the player into the box, through which it falls. The box sits in the floor, so
     * the end-of-tick push finds no free side to push the player towards.
     */
    @Test
    public void aPlayerOnAnOpeningBoxFallsIntoItFromTheFirstLidStep() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            placeBox(player);
            standAt(player, 120.0);
            frame(player, 196735, 120.0, GRAVITY_TICK, true);
            player.checkManager.endBedrockClientTick();
            blockEvent(player, 1);
            frame(player, 196736, 120.0, GRAVITY_TICK, true);
            player.checkManager.endBedrockClientTick();
            frame(player, 196737, 119.9216, -0.155232F, false);
            player.checkManager.endBedrockClientTick();
            frame(player, 196738, 119.76637, -0.23052737F, false);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /**
     * A box facing east opens into the player standing beside it, 0.01 from the closed box: the tick after the event
     * meets the closed box, the next one the lid's first step 0.0713 out, which the move leaves and the end-of-tick
     * push then pushes the player out of at 0.1 east; the step after that still overlaps and pushes again.
     */
    @Test
    public void aLidOpeningIntoThePlayerBesideItPushesThePlayerAway() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int east = shulkerBox("east");
            placeBox(player, east, BOX.getY());
            var still = new Vec3(0, GRAVITY_TICK, 0);
            var beside = new Vec3(BOX.getX() + 1.31, BOX.getY(), BOX.getZ() + 0.5);
            standAt(player, beside);
            frame(player, 51400, beside, still, true);
            player.checkManager.endBedrockClientTick();
            blockEvent(player, 1, 1, east);
            frame(player, 51401, beside, still, true);
            player.checkManager.endBedrockClientTick();
            frame(player, 51402, beside, new Vec3(0.1, GRAVITY_TICK, 0), true);
            player.checkManager.endBedrockClientTick();
            frame(player, 51403, beside.add(0.1, 0, 0), new Vec3(0.1, GRAVITY_TICK, 0), true);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static int shulkerBox(String facing) {
        var registry = DataTables.defaults().registry();
        return registry.with(registry.block("minecraft:shulker_box").defaultState(), "facing", facing);
    }

    /** A box facing up set into a stone floor level with its top. */
    private static void placeBox(CultPlayer player) {
        placeBox(player, UP, BOX.getY() + 1);
    }

    /** {@code state} at {@link #BOX} with stone around it below {@code floorTop}. */
    private static void placeBox(CultPlayer player, int state, int floorTop) {
        int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
        for (int x = BOX.getX() - 2; x <= BOX.getX() + 2; x++)
            for (int z = BOX.getZ() - 2; z <= BOX.getZ() + 2; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                for (int y = BOX.getY() - 1; y < floorTop; y++) player.compensatedWorld.updateBlock(x, y, z, stone);
            }
        player.compensatedWorld.updateBlock(BOX.getX(), BOX.getY(), BOX.getZ(), state);
    }

    /** A handled block event of {@code viewers} viewers. */
    private static void blockEvent(CultPlayer player, int viewers) {
        blockEvent(player, 1, viewers);
    }

    private static void blockEvent(CultPlayer player, int type, int parameter) {
        blockEvent(player, type, parameter, UP);
    }

    private static void blockEvent(CultPlayer player, int type, int parameter, int state) {
        player.compensatedWorld.applyClientBlockEvent(
                player.compensatedWorld.getLastClientboundDimension().dimension(),
                new ClientboundBlockEvent(BOX, type, parameter, DataTables.defaults().registry().blockIndex(state)));
    }

    private static void standAt(CultPlayer player, double y) {
        standAt(player, new Vec3(BOX.getX() + 0.5, y, BOX.getZ() + 0.5));
    }

    private static void standAt(CultPlayer player, Vec3 feet) {
        player.x = player.lastX = feet.x;
        player.y = player.lastY = feet.y;
        player.z = player.lastZ = feet.z;
        player.onGround = player.lastOnGround = true;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
    }

    /** One client tick that ends with the feet at {@code y}, which the engine must predict without a flag. */
    private static void frame(CultPlayer player, long tick, double y, float velocityY, boolean verticalCollision) {
        var feet = new Vec3(BOX.getX() + 0.5, (float) y, BOX.getZ() + 0.5);
        frame(player, tick, feet, new Vec3(0, velocityY, 0), verticalCollision);
    }

    /** One client tick that ends at {@code feet} with {@code velocity}, which the engine must predict without a flag. */
    private static void frame(CultPlayer player, long tick, Vec3 feet, Vec3 velocity, boolean verticalCollision) {
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(feet)
                .packetPosition(new Vec3(feet.x, (float) (feet.y + 1.62001), feet.z))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(verticalCollision ? 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal() : 0L)
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull("tick " + tick, result);
        var state = result.getProfileResult(BedrockPredictionResult.class).nextTickBaseState();
        assertNotNull("tick " + tick, state);
        assertEquals(
                "tick " + tick, 0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        double offset = state.physicalFeetPosition()
                .subtract(new Vec3d(feet.x, feet.y, feet.z))
                .length();
        assertTrue("tick " + tick + " position offset " + offset, offset <= 0.001);
        double velocityOffset = state.velocity()
                .subtract(new Vec3d(velocity.x, velocity.y, velocity.z))
                .length();
        assertTrue("tick " + tick + " velocity offset " + velocityOffset, velocityOffset <= 0.001);
    }
}
