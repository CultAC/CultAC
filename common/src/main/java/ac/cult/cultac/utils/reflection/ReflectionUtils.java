package ac.cult.cultac.utils.reflection;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class ReflectionUtils {

    public static boolean hasClass(String className) {
        return getClass(className) != null;
    }

    public static @Nullable Class<?> getClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
