package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.player.BedrockPistonBlocks;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Before;
import org.junit.Test;

/**
 * A Bedrock player's moving block collides as the block its piston carries, at the cell of the piston's last
 * progress and at the cell of its progress, as one box. The piston's data sets its state and the client steps the
 * progress half a block per tick before it moves.
 *
 * <p>A sticky piston at x 67 pulls a slime block from x 69 back to x 68: the tick after the moving block arrives at
 * x 68 with the arm fully out still stops a player walking west at x 70.0, the tick after that lets it through, and
 * the settled block stops it at x 69.0.
 */
public class BedrockMovingBlockTest {
    private static final BlockPos PISTON = new BlockPos(67, 121, -20);
    private static final BlockPos MOVING = new BlockPos(68, 121, -20);
    private static final int AIR = BlockIds.AIR.defaultState();
    private static final int STICKY = BlockProps.EXTENDED.with(
            BlockProps.FACING.with(state("minecraft:sticky_piston"), Direction.EAST.get3DDataValue()), true);
    private static final int MOVING_BLOCK = state("minecraft:moving_piston");
    private static final int SLIME = state("minecraft:slime_block");
    private static final int EXPANDING = 1;
    private static final int RETRACTING = 3;
    private static final ToIntFunction<BlockPos> WORLD =
            position -> position.equals(PISTON) ? STICKY : position.equals(MOVING) ? MOVING_BLOCK : AIR;

    @Before
    public void loadShapes() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void theCellFollowsTheProgressAlongTheArm() {
        assertEquals(69, BedrockPistonBlocks.cell(MOVING, Direction.EAST, 1.0F, false).getX());
        assertEquals(68, BedrockPistonBlocks.cell(MOVING, Direction.EAST, 0.5F, false).getX());
        assertEquals(68, BedrockPistonBlocks.cell(MOVING, Direction.EAST, 0.0F, false).getX());
        // An expanding piston starts its block one cell back.
        assertEquals(67, BedrockPistonBlocks.cell(MOVING, Direction.EAST, 0.0F, true).getX());
        assertEquals(67, BedrockPistonBlocks.cell(MOVING, Direction.EAST, 0.5F, true).getX());
        assertEquals(68, BedrockPistonBlocks.cell(MOVING, Direction.EAST, 1.0F, true).getX());
        assertEquals(67, BedrockPistonBlocks.cell(MOVING, Direction.WEST, 1.0F, false).getX());
        assertEquals(67, BedrockPistonBlocks.cell(MOVING, Direction.WEST, 0.5F, false).getX());
        assertEquals(122, BedrockPistonBlocks.cell(MOVING, Direction.UP, 1.0F, false).getY());
        assertEquals(new BlockPos(68, 121, -21), BedrockPistonBlocks.cell(MOVING, Direction.SOUTH, 0.5F, true));
    }

    @Test
    public void armDataSetsTheStateAndEachClientTickStepsTheProgress() {
        var pistons = new BedrockPistonBlocks();
        assertNull("a moving block without data", pistons.carried(MOVING, WORLD));
        pistons.movingBlock(MOVING, SLIME, PISTON, false);
        assertCells("no arm yet", pistons, 68, 68);
        pistons.pistonArm(PISTON, RETRACTING);
        assertCells("an arm that starts to retract is out", pistons, 69, 69);
        // Data that repeats the state before the client has ticked does not move the arm.
        pistons.pistonArm(PISTON, RETRACTING);
        pistons.tick(WORLD);
        assertCells("last progress 1, progress 0.5", pistons, 69, 68);
        pistons.pistonArm(PISTON, RETRACTING);
        pistons.tick(WORLD);
        assertCells("last progress 0.5, progress 0", pistons, 68, 68);
        // The stroke has ended: late data of it starts nothing.
        pistons.pistonArm(PISTON, RETRACTING);
        pistons.tick(WORLD);
        pistons.tick(WORLD);
        assertCells("retracted", pistons, 68, 68);

        pistons.movingBlock(MOVING, SLIME, PISTON, true);
        pistons.pistonArm(PISTON, EXPANDING);
        pistons.tick(WORLD);
        assertCells("last progress 0, progress 0.5", pistons, 67, 67);
        pistons.tick(WORLD);
        assertCells("last progress 0.5, progress 1", pistons, 67, 68);
        pistons.tick(WORLD);
        pistons.tick(WORLD);
        assertCells("extended", pistons, 68, 68);

        // A settled arm keeps its progress.
        pistons.pistonArm(PISTON, 2);
        pistons.movingBlock(MOVING, SLIME, PISTON, false);
        pistons.tick(WORLD);
        assertCells("settled", pistons, 69, 69);

        pistons.movingBlock(MOVING, SLIME, null, false);
        assertCells("no piston named", pistons, 68, 68);
        pistons.movingBlock(MOVING, SLIME, PISTON, false);
        assertCells("another block where the piston was", pistons.carried(MOVING, position -> AIR), 68, 68);
        pistons.tick(position -> position.equals(MOVING) ? MOVING_BLOCK : AIR);
        assertCells("the replaced piston lost its arm", pistons, 68, 68);
        pistons.tick(position -> AIR);
        assertNull("the replaced moving block lost its data", pistons.carried(MOVING, WORLD));
    }

    @Test
    public void theBoxSpansTheCarriedBlockAtBothCells() {
        run(player -> {
            var pistons = player.bedrockState.pistonBlocks;
            assertBox("nothing carried yet is a cube", player, 68, 69);
            pistons.movingBlock(MOVING, BedrockPistonBlocks.NO_BLOCK, PISTON, false);
            assertBox("a block the world cannot hold is a cube", player, 68, 69);
            pistons.movingBlock(MOVING, SLIME, PISTON, false);
            assertBox("no arm", player, 68, 69);
            pistons.pistonArm(PISTON, RETRACTING);
            assertBox("last progress 1, progress 1", player, 69, 70);
            tick(player);
            assertBox("last progress 1, progress 0.5", player, 68, 70);
            tick(player);
            assertBox("last progress 0.5, progress 0", player, 68, 69);

            pistons.movingBlock(MOVING, SLIME, PISTON, true);
            pistons.pistonArm(PISTON, EXPANDING);
            tick(player);
            assertBox("expanding, last progress 0, progress 0.5", player, 67, 68);
            tick(player);
            assertBox("expanding, last progress 0.5, progress 1", player, 67, 69);

            pistons.movingBlock(MOVING, AIR, PISTON, true);
            assertTrue(
                    "a carried block without a shape",
                    ClientBlockShapes.movement(player, MOVING_BLOCK, MOVING.getX(), MOVING.getY(), MOVING.getZ())
                            .isNull());
        });
    }

    /** A player with its feet at y 120 beside the row, strafing west against whatever stops it. */
    @Test
    public void aPlayerIsStoppedByTheBoxACellFromTheMovingBlock() {
        run(player -> {
            var pistons = player.bedrockState.pistonBlocks;
            pistons.movingBlock(MOVING, SLIME, PISTON, false);
            pistons.pistonArm(PISTON, RETRACTING);
            standAt(player, 70.3);
            // The first tick after the data: the box still reaches x 70 and the player stays against it.
            tick(player);
            assertEquals(0.0, blockedFrame(player, 194762), 0.001);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            // The next tick the box ends at x 69 and the player walks on.
            pistons.pistonArm(PISTON, RETRACTING);
            tick(player);
            assertTrue(blockedFrame(player, 194763) > 0.05);
        });
    }

    private static void assertCells(String message, BedrockPistonBlocks pistons, int lastX, int currentX) {
        assertCells(message, pistons.carried(MOVING, WORLD), lastX, currentX);
    }

    private static void assertCells(String message, BedrockPistonBlocks.Carried carried, int lastX, int currentX) {
        assertNotNull(message, carried);
        assertEquals(message, SLIME, carried.state());
        assertEquals(message, new BlockPos(lastX, 121, -20), carried.last());
        assertEquals(message, new BlockPos(currentX, 121, -20), carried.current());
    }

    /** Both readers of the shape, the shared collision collector and the engine's world, give the one box. */
    private static void assertBox(String message, CultPlayer player, double minX, double maxX) {
        List<SimpleCollisionBox> collected = new ArrayList<>();
        ClientBlockShapes.movement(player, MOVING_BLOCK, MOVING.getX(), MOVING.getY(), MOVING.getZ())
                .downCast(collected);
        assertEquals(message, 1, collected.size());
        assertEquals(message, minX, collected.getFirst().minX, 0.0);
        assertEquals(message, maxX, collected.getFirst().maxX, 0.0);
        assertEquals(message, 121.0, collected.getFirst().minY, 0.0);
        assertEquals(message, 122.0, collected.getFirst().maxY, 0.0);
        assertEquals(message, -20.0, collected.getFirst().minZ, 0.0);
        assertEquals(message, -19.0, collected.getFirst().maxZ, 0.0);

        var position = new BlockPosition(MOVING.getX(), MOVING.getY(), MOVING.getZ());
        var engine = new BedrockCollisionWorldBuilder(
                        BedrockClientBlockShapeMappings.catalog(), player.bedrockState.blockShapeContext(player))
                .block(position, MOVING_BLOCK, BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
        assertEquals(message, 1, engine.size());
        assertEquals(message, minX, engine.getFirst().minX(), 0.0);
        assertEquals(message, maxX, engine.getFirst().maxX(), 0.0);
    }

    private static void tick(CultPlayer player) {
        player.bedrockState.pistonBlocks.tick(player.compensatedWorld::getBlockStateIdAt);
    }

    private static void run(java.util.function.Consumer<CultPlayer> body) {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int stone = state("minecraft:stone");
            for (int x = 64; x <= 74; x++)
                for (int z = -24; z <= -17; z++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                    player.compensatedWorld.updateBlock(x, 119, z, stone);
                }
            player.compensatedWorld.updateBlock(PISTON, STICKY);
            player.compensatedWorld.updateBlock(MOVING, MOVING_BLOCK);
            body.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void standAt(CultPlayer player, double x) {
        player.x = player.lastX = x;
        player.y = player.lastY = 120.0;
        player.z = player.lastZ = -19.5;
        player.onGround = player.lastOnGround = true;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
    }

    /**
     * One client tick of strafing west that reports the player where it stood, as a block in its way leaves it.
     * Returns how far the engine's own position for the tick is from there.
     */
    private static double blockedFrame(CultPlayer player, long tick) {
        var feet = new Vec3(70.3F, 120.0, -19.5);
        var velocity = new Vec3(0, -0.0784F, 0);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(feet)
                .packetPosition(new Vec3(feet.x, 121.62001F, feet.z))
                .rotation(0, 0, 0)
                .moveVector(-1, 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(1L << PlayerAuthInputData.RIGHT.ordinal()
                        | 1L << PlayerAuthInputData.HORIZONTAL_COLLISION.ordinal()
                        | 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal())
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull("tick " + tick, result);
        var prediction = result.getProfileResult(BedrockPredictionResult.class);
        assertNotNull("tick " + tick, prediction);
        return prediction.observation().positionOffset();
    }

    private static int state(String block) {
        return DataTables.defaults().registry().block(block).defaultState();
    }
}
