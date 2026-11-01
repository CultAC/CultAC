package ac.cult.cultac.utils.lazy;

import java.util.function.Supplier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public interface LazyHolder<T> {
    @Contract(value = "_ -> new", pure = true)
    static <T> @NotNull LazyHolder<T> simple(Supplier<T> supplier) {
        return new SimpleLazyHolder<>(supplier);
    }

    T get();
}

final class SimpleLazyHolder<T> implements LazyHolder<T> {
    private T value;
    private Supplier<T> supplier;

    SimpleLazyHolder(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    @Override
    public T get() {
        if (supplier != null) {
            value = supplier.get();
            supplier = null;
        }
        return value;
    }
}
