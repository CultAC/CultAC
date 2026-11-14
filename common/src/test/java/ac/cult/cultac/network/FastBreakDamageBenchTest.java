package ac.cult.cultac.network;

import static ac.cult.cultac.network.WorldBookkeepingBenchTest.interleaved;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.breaking.BlockDamageMemo;
import ac.cult.cultac.checks.impl.breaking.FastBreak;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import ac.cult.cultac.utils.nmsutil.ClientMiningState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** FastBreak's per-movement block damage, direct beside memoized, reps interleaved. CULT_BENCH=1. */
@EnabledIfEnvironmentVariable(named = "CULT_BENCH", matches = "1")
class FastBreakDamageBenchTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);

    @Test
    void movementAfterADig() throws Exception {
        VerboseCodecs.ensureRegistered();
        try (var services = new RecordConsumerServices();
                var record = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            record.user.bind(mock(PlatformPlayer.class));
            CultPlayer player = record.player;
            player.packetStateData.packetPlayerOnGround = true;
            player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:diamond_pickaxe"));
            player.compensatedEntities.updateAttributes(
                    player.entityID,
                    List.of(new AttributeSnapshot("minecraft:mining_efficiency", 26.0, List.of())));
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            player.compensatedWorld.updateBlock(TARGET, BlockIds.OBSIDIAN.defaultState());
            FastBreak check = player.checkManager.getCheck(FastBreak.class);
            int block = player.compensatedWorld.getBlockStateIdAt(TARGET);
            check.onBlockBreak(new BlockBreak(
                    player, TARGET, Direction.DOWN, Direction.DOWN.get3DDataValue(),
                    PlayerAction.START_DESTROY_BLOCK, 1, block));
            var memo = new BlockDamageMemo();
            var bodies = new LinkedHashMap<String, LongUnaryOperator>();
            bodies.put("damage direct", units -> {
                double maximum = 0;
                for (long unit = 0; unit < units; unit++)
                    maximum = Math.max(maximum, BlockBreakSpeed.getBlockDamage(player, TARGET));
                return (long) (maximum * 1000);
            });
            bodies.put("damage memo", units -> {
                double maximum = 0;
                for (long unit = 0; unit < units; unit++) {
                    int state = player.compensatedWorld.getBlockStateIdAt(TARGET);
                    maximum = Math.max(maximum, memo.damage(player, player.getInventory().getHeldItem(), state));
                }
                return (long) (maximum * 1000);
            });
            bodies.put("check onMovePlayer", units -> {
                for (long unit = 0; unit < units; unit++) check.onMovePlayer(null, player, null);
                return 1;
            });
            bodies.put("part binding", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    seen += System.identityHashCode(ClientBlockActions.binding(player));
                return seen;
            });
            bodies.put("part mining state", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++)
                    seen += ClientMiningState.of(player).haste();
                return seen;
            });
            bodies.put("part block lookup", units -> {
                long seen = 0;
                for (long unit = 0; unit < units; unit++) seen += player.compensatedWorld.getBlockStateIdAt(TARGET);
                return seen;
            });
            interleaved(1, bodies);
        }
    }
}
