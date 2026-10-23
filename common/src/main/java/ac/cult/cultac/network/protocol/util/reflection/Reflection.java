package ac.cult.cultac.network.protocol.util.reflection;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class Reflection {
    private Reflection() {}

    public static Field getField(Class<?> owner, Class<?> fieldType, int index) {
        List<Field> matches = new ArrayList<>();
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    matches.add(field);
                }
            }
        }
        if (index < 0 || index >= matches.size()) {
            throw new IllegalArgumentException(
                    "Unable to find field " + index + " of type " + fieldType.getName() + " in " + owner.getName());
        }
        Field field = matches.get(index);
        field.setAccessible(true);
        return field;
    }
}
