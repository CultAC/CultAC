package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/** Client item-on-block branches whose server implementations require a real player or fork config. */
final class ClientBlockItemUse {
    private static final DataComponentType<?> COMPOSTABLE = compostableComponent();
    private static final java.util.Map<?, ?> LEGACY_COMPOSTABLES = legacyCompostables();

    private ClientBlockItemUse() {
    }

    static @Nullable InteractionResult simulate(BlockState state, Level level, BlockPos pos, PlacementSnapshot snapshot) {
        if (state.getBlock() instanceof RedStoneOreBlock) {
            // MCP RedStoneOreBlock#useItemOn: client particles do not change LIT.
            // Placement eligibility uses the same snapshot context as BlockItem.
            return snapshot.getItemStack().getItem() instanceof BlockItem
                    && NmsBlockPlaceResolver.createPlacementContext(level, snapshot).canPlace()
                    ? InteractionResult.PASS : InteractionResult.SUCCESS;
        }
        if (state.getBlock() instanceof JukeboxBlock) {
            // MCP JukeboxPlayable#tryInsertIntoJukebox changes inventory/BE only on the server.
            return snapshot.getItemStack().has(DataComponents.JUKEBOX_PLAYABLE)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (state.getBlock() instanceof ComposterBlock && state.getValue(ComposterBlock.LEVEL) < 8) {
            // MCP ComposterBlock#useItemOn: random composting and item consumption are server-only.
            return (COMPOSTABLE != null ? snapshot.getItemStack().has(COMPOSTABLE)
                    : LEGACY_COMPOSTABLES.containsKey(snapshot.getItemStack().getItem()))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        String id = NmsIdentifierUtil.registryKey(BuiltInRegistries.BLOCK, state.getBlock());
        if (id.endsWith("copper_golem_statue")) {
            // MCP CopperGolemStatueBlock/WeatheringCopperGolemStatueBlock#useItemOn.
            // These classes and their pose enum are absent on 1.21.3. Resolve the
            // property on the registered state; cycling follows getNextPose's enum order.
            if (snapshot.getItemStack().is(ItemTags.AXES)
                    || snapshot.getItemStack().getItem() == Items.HONEYCOMB && !id.startsWith("minecraft:waxed_")) {
                return InteractionResult.PASS;
            }
            for (Property<?> property : state.getProperties()) {
                if (property.getName().equals("copper_golem_pose")) {
                    level.setBlock(pos, state.cycle(property), 3);
                    return InteractionResult.SUCCESS;
                }
            }
        }
        return null;
    }

    private static DataComponentType<?> compostableComponent() {
        try {
            // 26.3 moved the vanilla compostables table to an item component.
            return (DataComponentType<?>) DataComponents.class.getField("COMPOSTABLE").get(null);
        } catch (NoSuchFieldException olderRuntime) {
            return null;
        } catch (IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static java.util.Map<?, ?> legacyCompostables() {
        if (COMPOSTABLE != null) return java.util.Map.of();
        try {
            return (java.util.Map<?, ?>) ComposterBlock.class.getField("COMPOSTABLES").get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
