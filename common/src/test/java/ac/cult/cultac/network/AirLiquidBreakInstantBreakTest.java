package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.breaking.AirLiquidBreak;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A survival START whose MultiPlayerGameMode#startDestroyBlock destroys the block at once leaves the fluid's legacy
 * block. Minecraft#startAttack ends the attack only when that block is air, so Minecraft#continueAttack's
 * MultiPlayerGameMode#continueDestroyBlock starts digging the water in the same tick and sends a second START.
 */
class AirLiquidBreakInstantBreakTest {
    private static final BlockPos LEAVES = new BlockPos(373, 62, -219);
    private static final int WATERLOGGED_LEAVES = BlockProps.WATERLOGGED.with(
            DataTables.defaults().registry().block("minecraft:oak_leaves").defaultState(), true);

    @BeforeAll
    static void registerVerboseTags() {
        VerboseCodecs.ensureRegistered();
    }

    @Test
    void shearsBreakingWaterloggedLeavesStartDiggingTheWaterLeftBehind() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = digger(f, "minecraft:shears", GameMode.SURVIVAL);
            start(f.player, check, WATERLOGGED_LEAVES, 2);
            start(f.player, check, BlockIds.WATER.defaultState(), 3);
            assertEquals(0, check.getViolations());
        }
    }

    @Test
    void aHandBreakingKelpStartsDiggingTheWaterLeftBehind() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = digger(f, "minecraft:stick", GameMode.SURVIVAL);
            start(f.player, check, BlockIds.KELP.defaultState(), 2);
            start(f.player, check, BlockIds.WATER.defaultState(), 3);
            assertEquals(0, check.getViolations());
        }
    }

    @Test
    void theWaterLeftBehindIsDugBeforeTheClientTickEnds() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = digger(f, "minecraft:shears", GameMode.SURVIVAL);
            start(f.player, check, WATERLOGGED_LEAVES, 2);
            check.onClientTickEnd(null, f.player, null);
            start(f.player, check, BlockIds.WATER.defaultState(), 3);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void theWaterLeftBehindIsDugBeforeTheTicksMovement() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = digger(f, "minecraft:shears", GameMode.SURVIVAL);
            start(f.player, check, WATERLOGGED_LEAVES, 2);
            check.onMovePlayer(null, f.player, null);
            start(f.player, check, BlockIds.WATER.defaultState(), 3);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void aHandStartOnWaterloggedLeavesLeavesNoWaterToDig() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = digger(f, "minecraft:stick", GameMode.SURVIVAL);
            start(f.player, check, WATERLOGGED_LEAVES, 2);
            start(f.player, check, BlockIds.WATER.defaultState(), 3);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void aCreativeBreakDelaysTheNextDigPastTheTick() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = digger(f, "minecraft:shears", GameMode.CREATIVE);
            start(f.player, check, WATERLOGGED_LEAVES, 2);
            start(f.player, check, BlockIds.WATER.defaultState(), 3);
            assertEquals(1, check.getViolations());
        }
    }

    private static AirLiquidBreak digger(RecordReceiveFixture f, String item, GameMode gameMode) {
        f.user.bind(mock(PlatformPlayer.class));
        f.player.gamemode = gameMode;
        f.player.packetStateData.packetPlayerOnGround = true;
        f.player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item(item));
        return f.player.checkManager.getCheck(AirLiquidBreak.class);
    }

    private static void start(CultPlayer player, AirLiquidBreak check, int block, int sequence) {
        check.onBlockBreak(new BlockBreak(
                player,
                LEAVES,
                Direction.UP,
                Direction.UP.ordinal(),
                PlayerAction.START_DESTROY_BLOCK,
                sequence,
                block));
    }
}
