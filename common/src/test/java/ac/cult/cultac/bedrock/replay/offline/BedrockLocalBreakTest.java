package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.junit.Test;

public class BedrockLocalBreakTest {
    private static final BlockPos WALL = new BlockPos(2, 64, 0);

    @Test
    public void walkIntoSameTickPredictedBreakPassesButUnbrokenWallRejects() {
        for (boolean breakWall : new boolean[] {false, true}) {
            OfflineCultTestBootstrap.installConfig();
            var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
            try {
                player.gamemode = GameMode.SURVIVAL;
                player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
                for (int x = 0; x < 8; x++)
                    for (int z = 0; z < 4; z++) {
                        player.compensatedWorld.updateBlock(
                                x,
                                63,
                                z,
                                DataTables.defaults()
                                        .registry()
                                        .block("minecraft:stone")
                                        .defaultState());
                    }
                player.compensatedWorld.updateBlock(
                        2,
                        64,
                        0,
                        DataTables.defaults().registry().block("minecraft:dirt").defaultState());
                var start = new Vec3(3.3000001907348633, 64, 0.5);
                player.x = player.lastX = start.x;
                player.y = player.lastY = start.y;
                player.z = player.lastZ = start.z;
                player.onGround = player.lastOnGround = true;
                player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
                var seed = frame(player, 1, start, 0);
                assertNotNull(BedrockFrameProcessor.process(player, seed, BedrockPredictionTrigger.OFFLINE_REPLAY));
                var actions = List.of(
                        action(PlayerActionType.BLOCK_CONTINUE_DESTROY, WALL),
                        action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL));
                var input = frame(player, 2, start.add(-0.098, 0, 0), -0.053508);
                var state = BedrockFrameProcessor.process(
                        player, input, BedrockPredictionTrigger.OFFLINE_REPLAY, () -> {}, resolved -> {
                            if (breakWall)
                                player.bedrockState.blockBreakActions.apply(
                                        player, resolved.getCoordinateFrame(), actions);
                        });
                assertEquals(
                        breakWall,
                        DataTables.defaults()
                                .registry()
                                .facts(player.compensatedWorld.getBlockStateIdAt(WALL))
                                .has(ac.cult.blocksim.data.StateFacts.AIR));
                if (breakWall) {
                    assertNotNull(state);
                    assertEquals(
                            input.getPosition().x, state.physicalFeetPosition().x(), 0.001);
                    assertEquals(-0.053508, state.velocity().x(), 0.001);
                    assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
                    assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
                    player.compensatedWorld.updateBlock(
                            2,
                            64,
                            0,
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:dirt")
                                    .defaultState());
                    assertNull(BedrockFrameProcessor.process(
                            player,
                            input,
                            BedrockPredictionTrigger.OFFLINE_REPLAY,
                            () -> {},
                            resolved -> fail("Duplicate input reached local block processing")));
                    assertEquals(
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:dirt")
                                    .defaultState(),
                            player.compensatedWorld.getBlockStateIdAt(WALL));
                } else {
                    assertTrue(state == null
                            || Math.abs(state.physicalFeetPosition().x() - input.getPosition().x) > 0.001
                            || player.checkManager.getListener(BedrockMovement.class).violations > 0);
                }
                assertEquals(
                        PlayerActionType.BLOCK_CONTINUE_DESTROY,
                        actions.getFirst().getAction());
                assertEquals(
                        PlayerActionType.BLOCK_PREDICT_DESTROY,
                        actions.getLast().getAction());
            } finally {
                OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            }
        }
    }

    @Test
    public void abortUnmatchedAndUnbreakableActionsDoNotRemoveBlocks() {
        OfflineCultTestBootstrap.installConfig();
        var p = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            p.gamemode = GameMode.SURVIVAL;
            p.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            p.compensatedWorld.updateBlock(
                    2,
                    64,
                    0,
                    DataTables.defaults().registry().block("minecraft:dirt").defaultState());
            var handler = p.bedrockState.blockBreakActions;
            handler.apply(
                    p, BedrockCoordinateFrame.IDENTITY, List.of(action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertFalse(DataTables.defaults()
                    .registry()
                    .facts(p.compensatedWorld.getBlockStateIdAt(WALL))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            handler.apply(
                    p,
                    BedrockCoordinateFrame.IDENTITY,
                    List.of(
                            action(PlayerActionType.START_BREAK, WALL),
                            action(PlayerActionType.ABORT_BREAK, WALL),
                            action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertFalse(DataTables.defaults()
                    .registry()
                    .facts(p.compensatedWorld.getBlockStateIdAt(WALL))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            p.compensatedWorld.updateBlock(
                    2,
                    64,
                    0,
                    DataTables.defaults().registry().block("minecraft:bedrock").defaultState());
            handler.apply(
                    p,
                    BedrockCoordinateFrame.IDENTITY,
                    List.of(
                            action(PlayerActionType.START_BREAK, WALL),
                            action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:bedrock").defaultState(),
                    p.compensatedWorld.getBlockStateIdAt(WALL));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(p);
        }
    }

    @Test
    public void instantBreaksAndDropsAreModeledWithoutGeyser() {
        OfflineCultTestBootstrap.installConfig();
        var p = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            p.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            p.gamemode = GameMode.SURVIVAL;
            p.compensatedWorld.updateBlock(
                    2,
                    64,
                    0,
                    DataTables.defaults()
                            .registry()
                            .block("minecraft:slime_block")
                            .defaultState());
            p.bedrockState.blockBreakActions.apply(
                    p, BedrockCoordinateFrame.IDENTITY, List.of(action(PlayerActionType.START_BREAK, WALL)));
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(p.compensatedWorld.getBlockStateIdAt(WALL))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            p.gamemode = GameMode.CREATIVE;
            p.compensatedWorld.updateBlock(
                    2,
                    64,
                    0,
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            p.bedrockState.blockBreakActions.apply(
                    p, BedrockCoordinateFrame.IDENTITY, List.of(action(PlayerActionType.BLOCK_CONTINUE_DESTROY, WALL)));
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(p.compensatedWorld.getBlockStateIdAt(WALL))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            p.compensatedWorld.updateBlock(
                    2,
                    64,
                    0,
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            p.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:diamond_sword"));
            p.bedrockState.blockBreakActions.apply(
                    p,
                    BedrockCoordinateFrame.IDENTITY,
                    List.of(
                            action(PlayerActionType.START_BREAK, WALL),
                            action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:stone").defaultState(),
                    p.compensatedWorld.getBlockStateIdAt(WALL));
            p.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:dirt", 3));
            p.bedrockState.blockBreakActions.apply(
                    p, BedrockCoordinateFrame.IDENTITY, List.of(action(PlayerActionType.DROP_ITEM, BlockPos.ZERO)));
            assertEquals(2, p.getInventory().getHeldItem().getCount());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(p);
        }
    }

    @Test
    public void rebasedWaterloggedBreakRetainsItsWater() {
        OfflineCultTestBootstrap.installConfig();
        var p = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            p.gamemode = GameMode.SURVIVAL;
            var coordinates = new BedrockCoordinateFrame(2880, -96, 1);
            var world = new BlockPos(2882, 64, -96);
            p.compensatedWorld.ensureValidationChunkLoaded(world.getX() >> 4, world.getZ() >> 4);
            p.compensatedWorld.updateBlock(
                    world.getX(),
                    world.getY(),
                    world.getZ(),
                    DataTables.defaults()
                            .registry()
                            .with(
                                    DataTables.defaults()
                                            .registry()
                                            .block("minecraft:oak_slab")
                                            .defaultState(),
                                    "waterlogged",
                                    "true"));
            var actions = List.of(
                    action(PlayerActionType.BLOCK_CONTINUE_DESTROY, WALL),
                    action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL));
            p.bedrockState.blockBreakActions.apply(p, coordinates, actions);
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:water").defaultState(),
                    p.compensatedWorld.getBlockStateIdAt(world));
            p.bedrockState.blockBreakActions.apply(p, coordinates, actions);
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:water").defaultState(),
                    p.compensatedWorld.getBlockStateIdAt(world));
            assertEquals(Vector3i.from(2, 64, 0), actions.getFirst().getBlockPosition());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(p);
        }
    }

    private static PlayerBlockActionData action(PlayerActionType type, BlockPos pos) {
        var action = new PlayerBlockActionData();
        action.setAction(type);
        action.setFace(1);
        action.setBlockPosition(Vector3i.from(pos.getX(), pos.getY(), pos.getZ()));
        return action;
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick, Vec3 feet, double vx) {
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(feet)
                .packetPosition(feet.add(0, 1.6200103759765625, 0))
                .rotation(90, 0, 90)
                .moveVector(0, tick == 1 ? 0 : 1)
                .delta(new Vec3(vx, -0.0784, 0))
                .reportedEndOfTickVelocity(new Vec3(vx, -0.0784, 0))
                .rawInputFlags(1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal())
                .build();
    }
}
