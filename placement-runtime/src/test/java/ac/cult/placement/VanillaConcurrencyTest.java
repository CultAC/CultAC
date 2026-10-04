package ac.cult.placement;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.placement.api.GeometryTags;
import ac.cult.placement.api.InteractionEngine;
import ac.cult.placement.api.PlacementEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Real vanilla calls with controlled overlap; no host Minecraft or Bukkit classes. */
class VanillaConcurrencyTest {
    private static PlacementRuntime open() throws Exception {
        return PlacementRuntime.openVanilla(Path.of(System.getProperty("placementRuntimeJar")));
    }

    @Test
    void actionsOverlapAndCloseDrainsTheirReaders() throws Exception {
        var entered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        try (var runtime = open();
                var pool = Executors.newFixedThreadPool(3)) {
            int stone = VanillaInteractionTest.state(runtime, "minecraft:stone", Map.of());
            var jobs = new ArrayList<java.util.concurrent.Future<InteractionEngine.Result>>();
            try {
                for (int i = 0; i < 2; i++) {
                    var request = request(blockedWorld(stone, entered, finish), "minecraft:dirt");
                    jobs.add(pool.submit(() -> {
                        var context = Thread.currentThread().getContextClassLoader();
                        var result = runtime.interact(request);
                        assertSame(context, Thread.currentThread().getContextClassLoader());
                        return result;
                    }));
                }
                assertTrue(
                        entered.await(10, TimeUnit.SECONDS), "Both native actions must enter before either finishes");
                var closing = new CountDownLatch(1);
                var closed = pool.submit(() -> {
                    closing.countDown();
                    runtime.close();
                    return null;
                });
                assertTrue(closing.await(5, TimeUnit.SECONDS));
                assertThrows(
                        TimeoutException.class,
                        () -> closed.get(100, TimeUnit.MILLISECONDS),
                        "Closing must wait for both active action snapshots");
                finish.countDown();
                for (var job : jobs) assertTrue(job.get(10, TimeUnit.SECONDS).consumes());
                closed.get(10, TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class, runtime::stateCount);
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void interleavedCustomTagsBelongToEachAction() throws Exception {
        var support = new GeometryTags(
                Map.of("minecraft:supports_vegetation", List.of("minecraft:stone")), Map.of(), Map.of());
        var empty = new GeometryTags(Map.of(), Map.of(), Map.of());
        try (var runtime = open();
                var pool = Executors.newFixedThreadPool(8)) {
            int stone = VanillaInteractionTest.state(runtime, "minecraft:stone", Map.of());
            var yes = request(taggedWorld(stone, support), "minecraft:dandelion");
            var no = request(taggedWorld(stone, empty), "minecraft:dandelion");
            var start = new CountDownLatch(1);
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int lane = 0; lane < 8; lane++) {
                int offset = lane;
                jobs.add(pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < 100; i++) {
                        boolean expected = (i + offset) % 2 == 0;
                        var result = runtime.interact(expected ? yes : no);
                        assertEquals(expected, result.consumes(), "The native support tag must match this request");
                    }
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void fallbackPublishesWhileReadersKeepTheirOwnGenerationAndTags() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var runtime = open();
                var pool = Executors.newSingleThreadExecutor()) {
            int stone = VanillaInteractionTest.state(runtime, "minecraft:stone", Map.of());
            var support = new GeometryTags(
                    Map.of("minecraft:supports_vegetation", List.of("minecraft:stone")), Map.of(), Map.of());
            assertTrue(runtime.narrowed());
            var active = pool.submit(
                    () -> runtime.interact(request(blockedWorld(stone, entered, finish), "minecraft:dirt")));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var broken = new PlacementEngine.World() {
                    public int stateAt(int x, int y, int z) {
                        throw new NoClassDefFoundError("intentional fallback test");
                    }

                    public int minY() {
                        return -64;
                    }

                    public int height() {
                        return 384;
                    }

                    public boolean loaded(int x, int z) {
                        return true;
                    }
                };
                var context = Thread.currentThread().getContextClassLoader();
                assertThrows(NoClassDefFoundError.class, () -> runtime.interact(request(broken, "minecraft:dirt")));
                assertSame(context, Thread.currentThread().getContextClassLoader());
                until(() -> !runtime.narrowed(), "Fallback publication must not wait for an unrelated request");
                assertFalse(active.isDone(), "The old request must retain its model while fallback serves new ones");
                assertTrue(runtime.interact(request(taggedWorld(stone, support), "minecraft:dandelion"))
                        .consumes());
            } finally {
                finish.countDown();
            }
            assertTrue(active.get(10, TimeUnit.SECONDS).consumes());
            until(() -> !runtime.narrowed(), "The completed action must permit fallback publication");
            var ordinary = taggedWorld(stone, support);
            assertTrue(
                    runtime.interact(request(ordinary, "minecraft:dandelion")).consumes(),
                    "The replacement must receive the installed custom support tag");
        }
    }

    @Test
    void closingDuringFallbackDoesNotReopenTheModel() throws Exception {
        try (var runtime = open()) {
            var broken = new PlacementEngine.World() {
                public int stateAt(int x, int y, int z) {
                    throw new NoClassDefFoundError("intentional close during fallback");
                }

                public int minY() {
                    return -64;
                }

                public int height() {
                    return 384;
                }

                public boolean loaded(int x, int z) {
                    return true;
                }
            };
            assertThrows(NoClassDefFoundError.class, () -> runtime.interact(request(broken, "minecraft:dirt")));
            runtime.close();
            until(
                    () -> Thread.getAllStackTraces().keySet().stream()
                            .noneMatch(thread ->
                                    thread.isAlive() && thread.getName().equals("cult-vanilla-full-model")),
                    "The unused fallback model must finish closing");
            assertFalse(runtime.narrowed());
            assertThrows(IllegalStateException.class, runtime::stateCount);
        }
    }

    private static InteractionEngine.Request request(PlacementEngine.World world, String item) {
        return VanillaInteractionTest.request(
                InteractionEngine.Operation.USE_ON,
                world,
                InteractionEngine.Stack.vanilla(item, 3),
                new PlacementEngine.Pos(0, 63, 0),
                20);
    }

    private static PlacementEngine.World taggedWorld(int stone, GeometryTags tags) {
        return new PlacementEngine.World() {
            public int stateAt(int x, int y, int z) {
                return y == 63 ? stone : 0;
            }

            public int minY() {
                return -64;
            }

            public int height() {
                return 384;
            }

            public boolean loaded(int x, int z) {
                return true;
            }

            public GeometryTags tags() {
                return tags;
            }
        };
    }

    private static PlacementEngine.World blockedWorld(int stone, CountDownLatch entered, CountDownLatch finish) {
        return new PlacementEngine.World() {
            private final AtomicBoolean first = new AtomicBoolean(true);

            public int stateAt(int x, int y, int z) {
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    await(finish);
                }
                return y == 63 ? stone : 0;
            }

            public int minY() {
                return -64;
            }

            public int height() {
                return 384;
            }

            public boolean loaded(int x, int z) {
                return true;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "The test must release the native world callback");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void until(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(10);
        }
    }
}
