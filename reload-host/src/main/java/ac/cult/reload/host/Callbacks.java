package ac.cult.reload.host;

import ac.cult.reload.api.ResourceScope;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Native schedulers may retain cancelled entries; their callbacks must remain host-owned. */
public final class Callbacks {
    public static GenericFutureListener<Future<Object>> future(ResourceScope scope, Runnable callback) {
        var guarded = scope.guard(callback);
        return ignored -> guarded.run();
    }

    private Callbacks() {}

    public static <T> Consumer<T> run(Runnable guarded) {
        return ignored -> guarded.run();
    }

    public static <A, B> BiConsumer<A, B> guard(ResourceScope scope, BiConsumer<A, B> callback) {
        var guarded = new Completion<A, B>(scope, callback);
        scope.track(guarded, () -> guarded.callback = null);
        return guarded;
    }

    private static final class Completion<A, B> implements BiConsumer<A, B> {
        private final ResourceScope scope;
        private volatile BiConsumer<A, B> callback;

        Completion(ResourceScope scope, BiConsumer<A, B> callback) {
            this.scope = scope;
            this.callback = callback;
        }

        public void accept(A first, B second) {
            var action = callback;
            if (action != null) scope.run(() -> action.accept(first, second));
        }
    }
}
