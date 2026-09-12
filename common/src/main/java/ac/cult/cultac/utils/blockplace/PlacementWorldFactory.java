package ac.cult.cultac.utils.blockplace;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class PlacementWorldFactory {
    private static final boolean MODERN_LEVEL_ABI = classExists(
            "net.minecraft.world.attribute.EnvironmentAttributeReader");
    private static final boolean CLOCK_LEVEL_ABI = classExists("net.minecraft.world.clock.ClockManager");
    private static final boolean RC1_LEVEL_ABI = classExists("net.minecraft.world.entity.SteppedInterpolationHandler");

    private PlacementWorldFactory() {
    }

    static PlacementWorldAdapter create(PlacementBlockAccess blockAccess, PlacementSnapshot snapshot) {
        if (RC1_LEVEL_ABI) {
            return CompensatedPlacementWorld.create(blockAccess, snapshot);
        }
        try {
            Class<?> implementation = Class.forName(
                    CLOCK_LEVEL_ABI ? "ac.cult.cultac.utils.blockplace.CompensatedPlacementWorld26_2"
                            : MODERN_LEVEL_ABI ? "ac.cult.cultac.utils.blockplace.CompensatedPlacementWorld1_21_11"
                            : "ac.cult.cultac.utils.blockplace.LegacyCompensatedPlacementWorld",
                    true,
                    PlacementWorldFactory.class.getClassLoader());
            Method create = implementation.getMethod("create", PlacementBlockAccess.class, PlacementSnapshot.class);
            return (PlacementWorldAdapter) create.invoke(null, blockAccess, snapshot);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Legacy compensated placement adapter is unavailable", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Legacy compensated placement adapter failed", cause);
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, PlacementWorldFactory.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
