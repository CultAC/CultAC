package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Item#canDestroyBlock gates a creative break on the held stack's TOOL from 1.21.5. */
class CreativeBreakToolTest {
    private static final int STONE = BlockIds.STONE.defaultState();

    @Test
    void aModernSwordBreaksInCreativeOnlyWhenItsOwnToolAllowsIt() throws Exception {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = creative(f.player, ClientVersion.V_26_3);
            assertEquals(0, BlockBreakSpeed.getBlockDamage(player, item("minecraft:diamond_sword"), STONE));
            assertEquals(1, BlockBreakSpeed.getBlockDamage(player, withoutTool("minecraft:diamond_sword"), STONE));
            assertEquals(1, BlockBreakSpeed.getBlockDamage(player, creativeTool("minecraft:diamond_sword"), STONE));
            assertEquals(1, BlockBreakSpeed.getBlockDamage(player, item("minecraft:stick"), STONE));
        }
    }

    @Test
    void aLegacySwordRefusesByItsClassWhateverItsTool() throws Exception {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            // SwordItem#canAttackBlock returns !player.isCreative() before 1.21.5.
            var player = creative(f.player, ClientVersion.V_1_21_4);
            assertEquals(0, BlockBreakSpeed.getBlockDamage(player, withoutTool("minecraft:diamond_sword"), STONE));
            assertEquals(0, BlockBreakSpeed.getBlockDamage(player, creativeTool("minecraft:diamond_sword"), STONE));
        }
    }

    private static CultPlayer creative(CultPlayer player, ClientVersion version) throws Exception {
        var resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
        resolved.setAccessible(true);
        resolved.set(player, version);
        player.gamemode = GameMode.CREATIVE;
        return player;
    }

    private static SimItemStack withoutTool(String key) {
        var stack = item(key);
        stack.removeComponent("minecraft:tool");
        return stack;
    }

    private static SimItemStack creativeTool(String key) {
        var stack = item(key);
        stack.setComponent(
                "minecraft:tool",
                new NbtValue.Compound(Map.of(
                        "rules", new NbtValue.Sequence(java.util.List.of()),
                        "can_destroy_blocks_in_creative", new NbtValue.Numeric(NbtValue.Kind.BYTE, 1))));
        return stack;
    }
}
