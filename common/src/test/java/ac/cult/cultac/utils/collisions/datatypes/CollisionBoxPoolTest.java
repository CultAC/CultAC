package ac.cult.cultac.utils.collisions.datatypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import io.netty.util.concurrent.FastThreadLocalThread;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CollisionBoxPoolTest {
    private static final VoxelShape SLAB = Shapes.create(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
    private static final VoxelShape POST = Shapes.create(0.375, 0.0, 0.375, 0.625, 1.5, 0.625);

    @Test
    void reusedColliderMatchesAFreshOne() {
        var pool = CollisionBoxPool.current();
        int mark = pool.mark();
        try {
            var dirty = pool.voxel(POST, false).offset(3, 70, -2).offset(0.25, 0, 0);
            dirty.voxelShape();
            dirty.expand(1.0);
            pool.release(mark);
            for (var shape : List.of(SLAB, Shapes.block(), POST)) {
                boolean fullBlock = shape == Shapes.block();
                var reused = pool.voxel(shape, fullBlock);
                assertSame(dirty, reused);
                assertEquals(
                        describe(new VoxelShapeCollisionBox(shape, fullBlock).offset(-5, 64, 9)),
                        describe(reused.offset(-5, 64, 9)));
                pool.release(mark);
            }
        } finally {
            pool.release(mark);
        }
    }

    @Test
    void nestedMarksNeverShareAColliderAndReleaseRestoresTheOuterOnes() {
        var pool = CollisionBoxPool.current();
        int outer = pool.mark();
        try {
            var outerBoxes = List.of(pool.voxel(SLAB, false), pool.voxel(SLAB, false), pool.voxel(SLAB, false));
            String before = describe(outerBoxes.get(2));
            int inner = pool.mark();
            var innerBoxes = List.of(pool.voxel(POST, false), pool.voxel(POST, false));
            var seen = new IdentityHashMap<VoxelShapeCollisionBox, Boolean>();
            for (var box : outerBoxes) seen.put(box, true);
            for (var box : innerBoxes) assertEquals(null, seen.put(box, true));
            pool.release(inner);
            assertSame(innerBoxes.get(0), pool.voxel(Shapes.block(), true));
            assertEquals(before, describe(outerBoxes.get(2)));
        } finally {
            pool.release(outer);
        }
        assertEquals(outer, pool.mark());
    }

    @Test
    void collidersPastTheBoundAreFreshEveryTime() {
        var pool = CollisionBoxPool.current();
        int mark = pool.mark();
        try {
            var first = new ArrayList<VoxelShapeCollisionBox>();
            for (int i = 0; i < 300; i++) first.add(pool.voxel(SLAB, false));
            pool.release(mark);
            int retained = 0;
            for (int i = 0; i < 300; i++) {
                var box = pool.voxel(SLAB, false);
                if (box == first.get(i)) retained++;
                else assertNotSame(first.get(i), box);
            }
            assertEquals(256 - mark, retained);
        } finally {
            pool.release(mark);
        }
    }

    @Test
    void aLiveHostThreadDoesNotKeepThePoolsLoaderReachable() throws Exception {
        var used = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var loader = new AtomicReference<WeakReference<ClassLoader>>();
        var thread = new FastThreadLocalThread(() -> {
            try {
                loader.set(usePoolInOwnLoader());
            } catch (Throwable t) {
                failure.set(t);
            }
            used.countDown();
            try {
                finish.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        thread.start();
        try {
            used.await();
            assertNull(failure.get());
            assertTrue(thread.isAlive());
            assertTrue(collected(loader.get()), "a live FastThreadLocalThread keeps the pool's loader reachable");
            assertTrue(thread.isAlive());
        } finally {
            finish.countDown();
            thread.join();
        }
    }

    private static WeakReference<ClassLoader> usePoolInOwnLoader() throws Exception {
        URL[] classes = {
            CollisionBoxPool.class.getProtectionDomain().getCodeSource().getLocation(),
            Shapes.class.getProtectionDomain().getCodeSource().getLocation()
        };
        try (var loader = new CultFirstLoader(classes, CollisionBoxPoolTest.class.getClassLoader())) {
            Class<?> pools = loader.loadClass(CollisionBoxPool.class.getName());
            Class<?> shapes = loader.loadClass(Shapes.class.getName());
            Class<?> voxelShape = loader.loadClass(VoxelShape.class.getName());
            assertNotSame(CollisionBoxPool.class, pools);
            Object block = shapes.getMethod("block").invoke(null);
            Object pool = pools.getMethod("current").invoke(null);
            Method voxel = pools.getMethod("voxel", voxelShape, boolean.class);
            int mark = (int) pools.getMethod("mark").invoke(pool);
            for (int i = 0; i < 3; i++) voxel.invoke(pool, block, true);
            pools.getMethod("release", int.class).invoke(pool, mark);
            return new WeakReference<>(loader);
        }
    }

    private static boolean collected(WeakReference<ClassLoader> loader) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && loader.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        return loader.get() == null;
    }

    private static final class CultFirstLoader extends URLClassLoader {
        CultFirstLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("ac.cult.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }

    private static String describe(VoxelShapeCollisionBox box) {
        var out = new StringBuilder();
        out.append(bits(box.minX)).append(bits(box.minY)).append(bits(box.minZ));
        out.append(bits(box.maxX)).append(bits(box.maxY)).append(bits(box.maxZ));
        out.append(box.isFullBlock())
                .append(box.usesStrictFullBlockIntersection())
                .append(" steps=");
        for (int i = 0; i < box.stepCoordinateCount(); i++) out.append(bits(box.stepCoordinateAt(i)));
        var parts = new ArrayList<SimpleCollisionBox>();
        box.downCast(parts);
        for (var part : parts)
            out.append(" part=")
                    .append(bits(part.minX))
                    .append(bits(part.minY))
                    .append(bits(part.minZ))
                    .append(bits(part.maxX))
                    .append(bits(part.maxY))
                    .append(bits(part.maxZ));
        var moving = new SimpleCollisionBox(-5.2, 63.4, 8.8, -4.6, 65.2, 9.4, false);
        out.append(" x=").append(bits(box.collideX(moving, 0.7, SimpleCollisionBox.AxisEpsilon.JAVA)));
        out.append(" y=").append(bits(box.collideY(moving, -0.9, SimpleCollisionBox.AxisEpsilon.JAVA)));
        out.append(" z=").append(bits(box.collideZ(moving, -0.4, SimpleCollisionBox.AxisEpsilon.JAVA)));
        out.append(" collided=").append(box.isCollided(moving)).append(box.isIntersected(moving));
        return out.toString();
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value)) + ',';
    }
}
