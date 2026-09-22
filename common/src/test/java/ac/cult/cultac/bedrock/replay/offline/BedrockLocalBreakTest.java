package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedrockLocalBreakTest {
    private static final BlockPos WALL = new BlockPos(2, 64, 0);

    @Test public void walkIntoSameTickPredictedBreakPassesButUnbrokenWallRejects() {
        for (boolean breakWall : new boolean[]{false, true}) {
            OfflineCultTestBootstrap.installConfig();
            var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
            try {
                player.gamemode = GameMode.SURVIVAL;
                player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
                for (int x = 0; x < 8; x++) for (int z = 0; z < 4; z++) {
                    player.compensatedWorld.updateBlock(x, 63, z, Blocks.STONE.defaultBlockState());
                }
                player.compensatedWorld.updateBlock(2, 64, 0, Blocks.DIRT.defaultBlockState());
                var start = new Vec3(3.3000001907348633, 64, 0.5);
                player.x = player.lastX = start.x; player.y = player.lastY = start.y; player.z = player.lastZ = start.z;
                player.onGround = player.lastOnGround = true;
                player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
                var seed = frame(player, 1, start, 0);
                assertNotNull(BedrockFrameProcessor.process(player, seed, BedrockPredictionTrigger.OFFLINE_REPLAY));
                var actions = List.of(action(PlayerActionType.BLOCK_CONTINUE_DESTROY, WALL),
                        action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL));
                var input = frame(player, 2, start.add(-0.098, 0, 0), -0.053508);
                var state = BedrockFrameProcessor.process(player, input, BedrockPredictionTrigger.OFFLINE_REPLAY,
                        () -> {}, resolved -> {
                            if (breakWall) player.bedrockState.blockBreakActions.apply(player,
                                    resolved.getCoordinateFrame(), actions);
                        });
                assertEquals(breakWall, player.compensatedWorld.getBlockStateAt(WALL).isAir());
                if (breakWall) {
                    assertNotNull(state);
                    assertEquals(input.getPosition().x, state.physicalFeetPosition().x(), 0.001);
                    assertEquals(-0.053508, state.velocity().x(), 0.001);
                    assertFalse(player.getSetbackTeleportUtil().isPendingSetback());
                    assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
                    player.compensatedWorld.updateBlock(2, 64, 0, Blocks.DIRT.defaultBlockState());
                    assertNull(BedrockFrameProcessor.process(player, input, BedrockPredictionTrigger.OFFLINE_REPLAY,
                            () -> {}, resolved -> fail("Duplicate input reached local block processing")));
                    assertEquals(Blocks.DIRT.defaultBlockState(), player.compensatedWorld.getBlockStateAt(WALL));
                } else {
                    assertTrue(state == null || Math.abs(state.physicalFeetPosition().x() - input.getPosition().x) > 0.001
                            || player.checkManager.getListener(BedrockMovement.class).violations > 0);
                }
                assertEquals(PlayerActionType.BLOCK_CONTINUE_DESTROY, actions.getFirst().getAction());
                assertEquals(PlayerActionType.BLOCK_PREDICT_DESTROY, actions.getLast().getAction());
            } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
        }
    }

    @Test public void abortUnmatchedAndUnbreakableActionsDoNotRemoveBlocks() {
        OfflineCultTestBootstrap.installConfig();
        var p = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            p.gamemode = GameMode.SURVIVAL;
            p.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            p.compensatedWorld.updateBlock(2, 64, 0, Blocks.DIRT.defaultBlockState());
            var handler = p.bedrockState.blockBreakActions;
            handler.apply(p, BedrockCoordinateFrame.IDENTITY, List.of(action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertFalse(p.compensatedWorld.getBlockStateAt(WALL).isAir());
            handler.apply(p, BedrockCoordinateFrame.IDENTITY, List.of(action(PlayerActionType.START_BREAK, WALL),
                    action(PlayerActionType.ABORT_BREAK, WALL), action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertFalse(p.compensatedWorld.getBlockStateAt(WALL).isAir());
            p.compensatedWorld.updateBlock(2, 64, 0, Blocks.BEDROCK.defaultBlockState());
            handler.apply(p, BedrockCoordinateFrame.IDENTITY, List.of(action(PlayerActionType.START_BREAK, WALL),
                    action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertEquals(Blocks.BEDROCK.defaultBlockState(), p.compensatedWorld.getBlockStateAt(WALL));
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(p); }
    }

    @Test public void instantBreaksAndDropsAreModeledWithoutGeyser() {
        OfflineCultTestBootstrap.installConfig();
        var p = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            p.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            p.gamemode = GameMode.SURVIVAL;
            p.compensatedWorld.updateBlock(2, 64, 0, Blocks.SLIME_BLOCK.defaultBlockState());
            p.bedrockState.blockBreakActions.apply(p, BedrockCoordinateFrame.IDENTITY,
                    List.of(action(PlayerActionType.START_BREAK, WALL)));
            assertTrue(p.compensatedWorld.getBlockStateAt(WALL).isAir());
            p.gamemode = GameMode.CREATIVE;
            p.compensatedWorld.updateBlock(2, 64, 0, Blocks.STONE.defaultBlockState());
            p.bedrockState.blockBreakActions.apply(p, BedrockCoordinateFrame.IDENTITY,
                    List.of(action(PlayerActionType.BLOCK_CONTINUE_DESTROY, WALL)));
            assertTrue(p.compensatedWorld.getBlockStateAt(WALL).isAir());
            p.compensatedWorld.updateBlock(2, 64, 0, Blocks.STONE.defaultBlockState());
            p.getInventory().inventory.setHeldItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_SWORD));
            p.bedrockState.blockBreakActions.apply(p, BedrockCoordinateFrame.IDENTITY,
                    List.of(action(PlayerActionType.START_BREAK, WALL), action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL)));
            assertEquals(Blocks.STONE.defaultBlockState(), p.compensatedWorld.getBlockStateAt(WALL));
            p.getInventory().inventory.setHeldItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIRT, 3));
            p.bedrockState.blockBreakActions.apply(p, BedrockCoordinateFrame.IDENTITY,
                    List.of(action(PlayerActionType.DROP_ITEM, BlockPos.ZERO)));
            assertEquals(2, p.getInventory().getHeldItem().getAmount());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(p); }
    }

    @Test public void rebasedWaterloggedBreakRetainsItsWater() {
        OfflineCultTestBootstrap.installConfig();
        var p = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            p.gamemode = GameMode.SURVIVAL;
            var coordinates = new BedrockCoordinateFrame(2880, -96, 1);
            var world = new BlockPos(2882, 64, -96);
            p.compensatedWorld.ensureValidationChunkLoaded(world.getX() >> 4, world.getZ() >> 4);
            p.compensatedWorld.updateBlock(world.getX(), world.getY(), world.getZ(), Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true));
            var actions = List.of(action(PlayerActionType.BLOCK_CONTINUE_DESTROY, WALL),
                    action(PlayerActionType.BLOCK_PREDICT_DESTROY, WALL));
            p.bedrockState.blockBreakActions.apply(p, coordinates, actions);
            assertEquals(Blocks.WATER.defaultBlockState(), p.compensatedWorld.getBlockStateAt(world));
            p.bedrockState.blockBreakActions.apply(p, coordinates, actions);
            assertEquals(Blocks.WATER.defaultBlockState(), p.compensatedWorld.getBlockStateAt(world));
            assertEquals(Vector3i.from(2, 64, 0), actions.getFirst().getBlockPosition());
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(p); }
    }

    private static PlayerBlockActionData action(PlayerActionType type, BlockPos pos) {
        var action = new PlayerBlockActionData();
        action.setAction(type); action.setFace(1);
        action.setBlockPosition(Vector3i.from(pos.getX(), pos.getY(), pos.getZ()));
        return action;
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick, Vec3 feet, double vx) {
        return BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(2193).clientTick(tick).inputMode(1).playMode(2).deviceId(3)
                .position(feet).packetPosition(feet.add(0, 1.6200103759765625, 0))
                .rotation(90, 0, 90).moveVector(0, tick == 1 ? 0 : 1)
                .delta(new Vec3(vx, -0.0784, 0)).reportedEndOfTickVelocity(new Vec3(vx, -0.0784, 0))
                .rawInputFlags(1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal()).build();
    }
}
