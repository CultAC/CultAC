package ac.cult.cultac.utils.latency;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class PalettedContainerAccessor {
    private static final Field DATA_FIELD;
    private static final Method DATA_STORAGE_METHOD;
    private static final Method DATA_PALETTE_METHOD;

    static {
        try {
            DATA_FIELD = PalettedContainer.class.getDeclaredField("data");
            DATA_FIELD.setAccessible(true);

            Class<?> dataClass = null;
            for (Class<?> inner : PalettedContainer.class.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("Data")) {
                    dataClass = inner;
                    break;
                }
            }
            if (dataClass == null) {
                throw new NoSuchFieldException("PalettedContainer.Data inner class not found");
            }

            DATA_STORAGE_METHOD = dataClass.getMethod("storage");
            DATA_STORAGE_METHOD.setAccessible(true);
            DATA_PALETTE_METHOD = dataClass.getMethod("palette");
            DATA_PALETTE_METHOD.setAccessible(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PalettedContainerAccessor() {
    }

    static RawView getRawView(PalettedContainer<?> container) {
        Object data = getDataRecord(container);
        return new RawView(getStorage(data), getPalette(data));
    }

    private static Object getDataRecord(PalettedContainer<?> container) {
        try {
            return DATA_FIELD.get(container);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access PalettedContainer.data", e);
        }
    }

    private static BitStorage getStorage(Object dataRecord) {
        try {
            return (BitStorage) DATA_STORAGE_METHOD.invoke(dataRecord);
        } catch (Exception e) {
            throw new RuntimeException("Cannot invoke Data.storage()", e);
        }
    }

    private static Palette<?> getPalette(Object dataRecord) {
        try {
            return (Palette<?>) DATA_PALETTE_METHOD.invoke(dataRecord);
        } catch (Exception e) {
            throw new RuntimeException("Cannot invoke Data.palette()", e);
        }
    }

    static final class RawView {
        final BitStorage storage;
        final Palette<?> palette;

        RawView(BitStorage storage, Palette<?> palette) {
            this.storage = storage;
            this.palette = palette;
        }
    }
}
