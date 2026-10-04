package ac.cult.cultac.vanilla;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.resources.*;
import net.minecraft.tags.*;
import org.junit.jupiter.api.Test;

class VanillaContextTest {
    private static final ResourceKey<Registry<String>> KEY =
            ResourceKey.createRegistryKey(Identifier.parse("test:context"));
    private static final TagKey<String> TAG = TagKey.create(KEY, Identifier.parse("test:tag"));
    private static final DataComponentType<Integer> VALUE =
            DataComponentType.<Integer>builder().persistent(Codec.INT).build();

    @Test
    void concurrentReadsUseTheirSnapshotAndLeaveBoundDefaultsUntouched() throws Exception {
        var registry = new MappedRegistry<String>(KEY, Lifecycle.stable());
        var holder = registry.register(
                ResourceKey.create(KEY, Identifier.parse("test:entry")), "entry", RegistrationInfo.BUILT_IN);
        registry.bindTags(Map.of(TAG, List.of(holder)));
        registry.freeze();
        var defaults = DataComponentMap.builder().set(VALUE, 0).build();
        holder.bindComponents(defaults);
        var named = registry.get(TAG).orElseThrow();
        var first = context(registry, holder, true, 1);
        var second = context(registry, holder, false, 2);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<Future<?>>();
            for (var snapshot : List.of(first, second))
                jobs.add(workers.submit(() -> VanillaContext.run(snapshot, () -> {
                    entered.countDown();
                    await(release);
                    boolean member = snapshot == first;
                    for (int i = 0; i < 1000; i++) {
                        assertEquals(member, holder.is(TAG));
                        assertEquals(member, holder.tags().anyMatch(TAG::equals));
                        assertEquals(member, named.contains(holder));
                        assertEquals(
                                member ? List.of(holder) : List.of(),
                                named.stream().toList());
                        assertEquals(member ? 1 : 2, holder.components().get(VALUE));
                    }
                })));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "Contexts must overlap without a model lock");
            } finally {
                release.countDown();
            }
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            assertNull(workers.submit(VanillaContext::current).get());
        }
        assertNull(VanillaContext.current());
        assertTrue(holder.is(TAG));
        assertTrue(named.contains(holder));
        assertEquals(List.of(holder), named.stream().toList());
        assertSame(defaults, holder.components());
        VanillaContext.run(first, () -> {
            assertThrows(
                    IllegalStateException.class,
                    () -> VanillaContext.run(second, () -> {
                        assertEquals(2, holder.components().get(VALUE));
                        throw new IllegalStateException("nested failure");
                    }));
            assertSame(first, VanillaContext.current());
            assertEquals(1, holder.components().get(VALUE));
        });
        assertNull(VanillaContext.current());
    }

    private static VanillaContext context(
            Registry<String> registry, Holder.Reference<String> holder, boolean member, int value) {
        var tags = new VanillaTagBindings<>(
                registry, new TagLoader.LoadResult<>(KEY, Map.of(TAG, member ? List.of(holder) : List.of())));
        var components = new VanillaComponentBindings<>(new DataComponentInitializers.PendingComponents<String>() {
            public ResourceKey<? extends Registry<? extends String>> key() {
                return KEY;
            }

            public void forEach(BiConsumer<Holder.Reference<String>, DataComponentMap> action) {
                action.accept(
                        holder, DataComponentMap.builder().set(VALUE, value).build());
            }

            public void apply() {
                throw new AssertionError("A context must never apply its bindings");
            }
        });
        return new VanillaContext(List.of(tags), List.of(components));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
