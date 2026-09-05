package ac.cult.cultac.utils.data.tags;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class stores tags that the client is aware of.
 */
public final class SyncedTags {

    public static final Identifier CLIMBABLE = Identifier.withDefaultNamespace("climbable");
    public static final Identifier MINEABLE_AXE = Identifier.withDefaultNamespace("mineable/axe");
    public static final Identifier MINEABLE_PICKAXE = Identifier.withDefaultNamespace("mineable/pickaxe");
    public static final Identifier MINEABLE_SHOVEL = Identifier.withDefaultNamespace("mineable/shovel");
    public static final Identifier MINEABLE_HOE = Identifier.withDefaultNamespace("mineable/hoe");
    public static final Identifier NEEDS_DIAMOND_TOOL = Identifier.withDefaultNamespace("needs_diamond_tool");
    public static final Identifier NEEDS_IRON_TOOL = Identifier.withDefaultNamespace("needs_iron_tool");
    public static final Identifier NEEDS_STONE_TOOL = Identifier.withDefaultNamespace("needs_stone_tool");
    public static final Identifier SWORD_EFFICIENT = Identifier.withDefaultNamespace("sword_efficient");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());
    private static final Identifier BLOCK = SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21)
            ? Identifier.withDefaultNamespace("block") : Identifier.withDefaultNamespace("blocks");
    private final CultPlayer player;
    private final Map<Identifier, Map<Identifier, SyncedTag<?>>> synced = new HashMap<>();
    private boolean receivedBlockTagSync;

    public SyncedTags(CultPlayer player) {
        this.player = player;
        ClientVersion version = player.getClientVersion();
        trackTags(BLOCK,
                SyncedTag.<Block>builder(CLIMBABLE).defaults(defaultBlockTagValues(BlockTags.CLIMBABLE)).supported(version.getProtocolVersion() >= 735), // PE ClientVersion.V_1_16
                SyncedTag.<Block>builder(MINEABLE_AXE).defaults(defaultBlockTagValues(BlockTags.MINEABLE_WITH_AXE)).supported(version.getProtocolVersion() >= 755), // PE ClientVersion.V_1_17
                SyncedTag.<Block>builder(MINEABLE_PICKAXE).defaults(defaultBlockTagValues(BlockTags.MINEABLE_WITH_PICKAXE)).supported(version.getProtocolVersion() >= 755), // PE ClientVersion.V_1_17
                SyncedTag.<Block>builder(MINEABLE_SHOVEL).defaults(defaultBlockTagValues(BlockTags.MINEABLE_WITH_SHOVEL)).supported(version.getProtocolVersion() >= 755), // PE ClientVersion.V_1_17
                SyncedTag.<Block>builder(MINEABLE_HOE).defaults(defaultBlockTagValues(BlockTags.MINEABLE_WITH_HOE)).supported(version.getProtocolVersion() >= 755), // PE ClientVersion.V_1_17
                SyncedTag.<Block>builder(NEEDS_DIAMOND_TOOL).defaults(defaultBlockTagValues(BlockTags.NEEDS_DIAMOND_TOOL)).supported(version.getProtocolVersion() >= 755), // PE ClientVersion.V_1_17
                SyncedTag.<Block>builder(NEEDS_IRON_TOOL).defaults(defaultBlockTagValues(BlockTags.NEEDS_IRON_TOOL)).supported(version.getProtocolVersion() >= 755), // PE ClientVersion.V_1_17
                SyncedTag.<Block>builder(NEEDS_STONE_TOOL).defaults(defaultBlockTagValues(BlockTags.NEEDS_STONE_TOOL)).supported(version.getProtocolVersion() >= 755), // PE ClientVersion.V_1_17
                SyncedTag.<Block>builder(SWORD_EFFICIENT).defaults(defaultBlockTagValues(BlockTags.SWORD_EFFICIENT)).supported(version.isNewerThanOrEquals(ClientVersion.V_1_20))
        );
    }

    // Vanilla's default tag contents as known by this server's block registry
    private static Set<Block> defaultBlockTagValues(TagKey<Block> tag) {
        Set<Block> values = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
            values.add(holder.value());
        }
        return values;
    }

    @SafeVarargs
    private <T> void trackTags(Identifier location, SyncedTag.Builder<T>... syncedTags) {
        final Map<Identifier, SyncedTag<?>> tags = new HashMap<>(syncedTags.length);
        for (SyncedTag.Builder<T> syncedTag : syncedTags) {
            final SyncedTag<T> built = syncedTag.build();
            tags.put(built.location(), built);
        }
        synced.put(location, tags);
    }

    public SyncedTag<Block> block(Identifier tag) {
        final Map<Identifier, SyncedTag<?>> blockTags = synced.get(BLOCK);
        return (SyncedTag<Block>) blockTags.get(tag);
    }

    /**
     * Evaluates a tool-component block holder set against the tags visible to this client.
     * Named holder sets use the compensated tag payload when one has been received; direct
     * holder sets retain their packet/registry values.
     */
    public boolean containsBlock(HolderSet<Block> blocks, Block block) {
        var tagKey = blocks.unwrapKey();
        if (tagKey.isPresent()) {
            SyncedTag<Block> syncedTag = block(tagKey.get().location());
            if (syncedTag != null) {
                return syncedTag.contains(block);
            }
            // Once the client has replaced its block-tag registry, a named set
            // omitted from that payload is empty on the client. Falling back to
            // this server's holder binding would reintroduce state the client no
            // longer has.
            if (receivedBlockTagSync) {
                return false;
            }
        }

        for (Holder<Block> holder : blocks) {
            if (holder.value() == block) {
                return true;
            }
        }
        return false;
    }

    public void handleTagSync(ClientboundUpdateTagsPacket tags) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_13)) return;
        tags.getTags().forEach((registryKey, payload) -> {
            if (!synced.containsKey(registryKey.identifier())) return;
            final Map<Identifier, SyncedTag<?>> syncedTags = synced.get(registryKey.identifier());
            // Only block tags are tracked, so resolve against the block registry
            if (!registryKey.identifier().equals(BLOCK)) return;
            TagLoader.LoadResult<Block> result = payload.resolve(BuiltInRegistries.BLOCK);
            receivedBlockTagSync = true;

            // The client replaces this registry's complete tag binding set for each payload.
            // Clear previously received dynamic tags which disappeared from the replacement.
            for (SyncedTag<?> syncedTag : syncedTags.values()) {
                ((SyncedTag<Block>) syncedTag).readTagValues(List.of());
            }

            result.tags().forEach((tagKey, holders) -> {
                final SyncedTag<?> syncedTag = syncedTags.computeIfAbsent(tagKey.location(), location ->
                        SyncedTag.<Block>builder(location)
                                .defaults(Collections.newSetFromMap(new IdentityHashMap<>()))
                                .build());
                ((SyncedTag<Block>) syncedTag).readTagValues(holders);
            });
        });
    }
}
