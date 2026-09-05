package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SyncedToolTagCompensationTest {
    @Test
    public void dynamicClientTagControlsIndependentToolRuleProperties() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            Identifier tagId = Identifier.fromNamespaceAndPath("cult", "test_tool_blocks");
            TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagId);
            HolderSet<Block> namedBlocks = HolderSet.emptyNamed(BuiltInRegistries.BLOCK, tagKey);
            Tool tool = new Tool(List.of(
                    new Tool.Rule(namedBlocks, Optional.of(12.0F), Optional.empty()),
                    new Tool.Rule(namedBlocks, Optional.of(3.0F), Optional.of(true))
            ), 1.0F, 1, true);

            player.tagManager.handleTagSync(tagPacket(tagId, Blocks.STONE));
            assertTrue(player.tagManager.containsBlock(namedBlocks, Blocks.STONE));

            Object matched = modernToolData(player, tool);
            assertEquals(12.0F, floatComponent(matched, "speed"), 0.0F);
            assertTrue(booleanComponent(matched, "correctForDrops"));

            // Update-tags replaces the complete block-tag binding set. Omitting the
            // dynamic tag must clear its old value instead of retaining stale state.
            player.tagManager.handleTagSync(emptyTagPacket());
            assertFalse(player.tagManager.containsBlock(namedBlocks, Blocks.STONE));

            Object cleared = modernToolData(player, tool);
            assertEquals(1.0F, floatComponent(cleared, "speed"), 0.0F);
            assertFalse(booleanComponent(cleared, "correctForDrops"));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void omittedPreviouslyUntrackedNamedTagDoesNotFallBackToServerBindings() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            HolderSet.Named<Block> serverLogs = BuiltInRegistries.BLOCK.getTags()
                    .filter(tag -> tag.key().equals(BlockTags.LOGS))
                    .findFirst()
                    .orElseThrow();

            assertTrue(player.tagManager.containsBlock(serverLogs, Blocks.OAK_LOG));

            player.tagManager.handleTagSync(emptyTagPacket());

            assertFalse(player.tagManager.containsBlock(serverLogs, Blocks.OAK_LOG));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static ClientboundUpdateTagsPacket tagPacket(Identifier tagId, Block block) {
        int blockId = BuiltInRegistries.BLOCK.getId(block);
        TagNetworkSerialization.NetworkPayload payload =
                new TagNetworkSerialization.NetworkPayload(Map.of(tagId, new IntArrayList(new int[]{blockId})));
        return new ClientboundUpdateTagsPacket(Map.of(Registries.BLOCK, payload));
    }

    private static ClientboundUpdateTagsPacket emptyTagPacket() {
        return new ClientboundUpdateTagsPacket(Map.of(
                Registries.BLOCK,
                TagNetworkSerialization.NetworkPayload.EMPTY));
    }

    private static Object modernToolData(CultPlayer player, Tool tool) throws Exception {
        Method method = BlockBreakSpeed.class.getDeclaredMethod(
                "modernToolData",
                CultPlayer.class,
                Tool.class,
                net.minecraft.world.level.block.state.BlockState.class);
        method.setAccessible(true);
        return method.invoke(null, player, tool, Blocks.STONE.defaultBlockState());
    }

    private static float floatComponent(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (float) method.invoke(record);
    }

    private static boolean booleanComponent(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (boolean) method.invoke(record);
    }

    private static CultPlayer offlineJavaPlayer() {
        UUID playerId = UUID.fromString("9c5e440b-265d-435f-98f1-1f539659c004");
        User user = new User(
                new User.Profile(playerId, ".Tool_Tag_Test"),
                null,
                null,
                null,
                new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
