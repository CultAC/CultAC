package ac.cult.cultac.utils.common.arguments;

public record SystemArgument<T>(String key, Class<T> clazz, T value, boolean set, Visibility visibility) {

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        SystemArgument<?> that = (SystemArgument<?>) o;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    public enum Visibility {
        VISIBLE, // visible from commands
        HIDDEN, // only visible in console
        SECRET // only visible in console if set
    }
}
