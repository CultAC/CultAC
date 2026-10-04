package ac.cult.placement;

import ac.cult.placement.api.PlacementEngine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** A model generation stays alive until its last admitted request finishes. */
final class PlacementModel {
    final PlacementEngine engine;
    final InteractionRuntime interactions;
    final ArchiveLoader loader;
    private final AtomicInteger users = new AtomicInteger(1); // Publication owns one reference.
    final CompletableFuture<Void> closed = new CompletableFuture<>();

    PlacementModel(PlacementEngine engine, InteractionRuntime interactions, ArchiveLoader loader) {
        this.engine = engine;
        this.interactions = interactions;
        this.loader = loader;
    }

    boolean acquire() {
        int count;
        do {
            count = users.get();
            if (count == 0) return false;
        } while (!users.compareAndSet(count, count + 1));
        return true;
    }

    void release() {
        if (users.decrementAndGet() != 0) return;
        Throwable failure = null;
        try {
            interactions.close();
        } catch (Throwable caught) {
            failure = caught;
        }
        try {
            if (loader != null) loader.close();
        } catch (Throwable caught) {
            if (failure == null) failure = caught;
            else failure.addSuppressed(caught);
        }
        if (failure == null) closed.complete(null);
        else closed.completeExceptionally(failure);
    }

    ClassLoader classLoader() {
        return loader == null ? interactions.classLoader() : loader;
    }
}
