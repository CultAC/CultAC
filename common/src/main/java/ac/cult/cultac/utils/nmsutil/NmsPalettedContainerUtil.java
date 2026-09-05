package ac.cult.cultac.utils.nmsutil;

import net.minecraft.core.IdMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class NmsPalettedContainerUtil {
    private NmsPalettedContainerUtil() {
    }

    @SuppressWarnings("unchecked")
    public static PalettedContainer<BlockState> createBlockStates(BlockState defaultState) {
        try {
            Class<?> strategyClass = Class.forName("net.minecraft.world.level.chunk.Strategy");
            Method create = strategyClass.getMethod("createForBlockStates", IdMap.class);
            Object strategy = create.invoke(null, Block.BLOCK_STATE_REGISTRY);
            Constructor<PalettedContainer> constructor = PalettedContainer.class.getConstructor(Object.class, strategyClass);
            return (PalettedContainer<BlockState>) constructor.newInstance(defaultState, strategy);
        } catch (ClassNotFoundException ignored) {
            try {
                Class<?> strategyClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Strategy");
                Field sectionStates = strategyClass.getField("SECTION_STATES");
                Object strategy = sectionStates.get(null);
                Constructor<PalettedContainer> constructor = PalettedContainer.class.getConstructor(IdMap.class, Object.class, strategyClass);
                return (PalettedContainer<BlockState>) constructor.newInstance(Block.BLOCK_STATE_REGISTRY, defaultState, strategy);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to construct legacy block-state palette", exception);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to construct block-state palette", exception);
        }
    }
}
