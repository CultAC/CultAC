package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.cloud.intake.v3.PerfStack;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StackTrieTest {

    private static final int OTHER = Attribution.OTHER;

    @Test
    void mergesIdenticalStacks() {
        StackTrie trie = new StackTrie();
        trie.add(stack("a.A.run", "b.B.go"), OTHER, 1, 0, 0);
        trie.add(stack("a.A.run", "b.B.go"), OTHER, 1, 0, 0);

        PerfProfile profile = trie.encode(true, 1 << 20).build();

        assertEquals(1, profile.getStacksCount());
        PerfStack only = profile.getStacks(0);
        assertEquals(List.of("a.A.run", "b.B.go"), names(profile, only));
        assertEquals(2, only.getCpuSamples());
        assertEquals(OTHER, only.getThreadGroup());
        assertEquals(Attribution.GROUPS, profile.getThreadGroupsList());
    }

    @Test
    void prunesBelowThreshold() {
        StackTrie trie = new StackTrie();
        for (int i = 0; i < 10_000; i++) trie.add(stack("a.A.run", "b.B.go"), OTHER, 1, 0, 0);
        trie.add(stack("a.A.run", "c.C.rare"), OTHER, 1, 0, 0);

        PerfProfile profile = trie.encode(true, 1 << 20).build();

        assertEquals(10_001, profile.getCpuSamplesTotal());
        assertFalse(profile.getFramesList().contains("c.C.rare"));
        assertEquals(10_000, cpuOf(profile, List.of("a.A.run", "b.B.go")));
        assertEquals(1, cpuOf(profile, List.of("a.A.run")));
        assertEquals(
                10_001,
                profile.getStacksList().stream()
                        .mapToLong(PerfStack::getCpuSamples)
                        .sum());
    }

    @Test
    void partnerModeKeepsOnlyOwnStacks() {
        StackTrie trie = new StackTrie();
        trie.add(stack("a.A.run", "ac.cult.cultac.X.check"), OTHER, 5, 0, 0);
        trie.add(stack("a.A.run", "partner.Plugin.tick"), OTHER, 7, 0, 0);

        PerfProfile profile = trie.encode(false, 1 << 20).build();

        assertFalse(profile.getWholeJvm());
        assertEquals(12, profile.getCpuSamplesTotal());
        assertEquals(1, profile.getStacksCount());
        assertEquals(List.of("(other)", "ac.cult.cultac.X.check"), names(profile, profile.getStacks(0)));
        assertFalse(profile.getFramesList().contains("partner.Plugin.tick"));
    }

    @Test
    void partnerModeNamesPartnerFramesOther() {
        List<String> frames = List.of(
                "java.lang.Thread.run",
                "io.netty.channel.Loop.run",
                "partner.Listener.onMove",
                "org.bukkit.plugin.EventExecutor.execute",
                "com.velocitypowered.proxy.Handler.handle",
                "ac.cult.cultac.X.check",
                "partner.Api.lookup",
                "partner.Api.lookupInner");
        StackTrie trie = new StackTrie();
        trie.add(stack(frames.toArray(String[]::new)), OTHER, 3, 0, 0);

        PerfProfile partner = trie.encode(false, 1 << 20).build();
        PerfProfile whole = trie.encode(true, 1 << 20).build();

        assertEquals(1, partner.getStacksCount());
        assertEquals(
                List.of(
                        "java.lang.Thread.run",
                        "io.netty.channel.Loop.run",
                        "(other)",
                        "org.bukkit.plugin.EventExecutor.execute",
                        "com.velocitypowered.proxy.Handler.handle",
                        "ac.cult.cultac.X.check",
                        "(other)",
                        "(other)"),
                names(partner, partner.getStacks(0)));
        assertEquals(3, partner.getStacks(0).getCpuSamples());
        assertEquals(
                1, partner.getFramesList().stream().filter("(other)"::equals).count());
        assertTrue(partner.getFramesList().stream().noneMatch(frame -> frame.startsWith("partner.")));
        assertEquals(1, whole.getStacksCount());
        assertEquals(frames, names(whole, whole.getStacks(0)));
        assertEquals(3, whole.getStacks(0).getCpuSamples());
    }

    @Test
    void shrinksUntilItFits() {
        StackTrie trie = new StackTrie();
        Random random = new Random(7);
        for (int i = 0; i < 50_000; i++) {
            List<String> frames = new ArrayList<>();
            frames.add("x.Root.run");
            for (int f = 0; f < 4; f++) frames.add("p" + Long.toHexString(random.nextLong()) + ".C.m" + i);
            trie.add(stack(frames.toArray(String[]::new)), OTHER, i < 9_000 ? 1_000 : 1, 0, 0);
        }
        int maxBytes = 64 * 1024;
        int unbounded = StackTrie.zstd(trie.encode(true, Long.MAX_VALUE).build().toByteArray()).length;

        PerfProfile profile = trie.encode(true, maxBytes).build();

        assertTrue(unbounded > maxBytes, "the unpruned profile must not fit already: " + unbounded);
        assertTrue(StackTrie.zstd(profile.toByteArray()).length <= maxBytes);
        assertEquals(9_041_000, profile.getCpuSamplesTotal());
        assertEquals(
                9_041_000,
                profile.getStacksList().stream()
                        .mapToLong(PerfStack::getCpuSamples)
                        .sum());
    }

    @Test
    void truncatesLeafSide() {
        String[] frames = new String[300];
        for (int i = 0; i < frames.length; i++) frames[i] = "f.F.m" + i;
        StackTrie trie = new StackTrie();
        trie.add(stack(frames), OTHER, 1, 0, 0);

        PerfProfile profile = trie.encode(true, 1 << 20).build();

        assertEquals(1, profile.getStacksCount());
        List<String> kept = names(profile, profile.getStacks(0));
        assertEquals(StackTrie.MAX_DEPTH, kept.size());
        assertEquals("f.F.m172", kept.get(0));
        assertEquals("f.F.m299", kept.get(kept.size() - 1));
    }

    @Test
    void fillsToTheNodeCapAndKeepsCounting() {
        StackTrie trie = new StackTrie();
        int stacks = StackTrie.MAX_NODES + 1_000;
        for (int i = 0; i < stacks; i++) trie.add(stack("x.Root.run", "y.Leaf.m" + i), OTHER, 1, 0, 0);

        assertEquals(StackTrie.MAX_NODES, trie.nodes());
        PerfProfile profile = trie.encode(true, Long.MAX_VALUE).build();
        assertEquals(stacks, profile.getCpuSamplesTotal());
        assertEquals(
                stacks,
                profile.getStacksList().stream()
                        .mapToLong(PerfStack::getCpuSamples)
                        .sum());
    }

    @Test
    void pathsPastTheCapEndInTruncatedAndLeaveInteriorSelfAlone() {
        StackTrie trie = new StackTrie();
        trie.add(stack("x.Root.run", "x.Mid.run", "x.Hot.run"), OTHER, 100_000, 0, 0);
        fillToTheCap(trie);

        for (int i = 0; i < 2_000; i++)
            trie.add(stack("x.Root.run", "x.Mid.run", "z.New.m" + i, "z.Deep.run"), OTHER, 1, 0, 0);

        assertEquals(StackTrie.MAX_NODES, trie.nodes());
        assertEquals(2_000, trie.capped());
        PerfProfile profile = trie.encode(true, Long.MAX_VALUE).build();
        assertEquals(0, cpuOf(profile, List.of("x.Root.run", "x.Mid.run")));
        assertEquals(100_000, cpuOf(profile, List.of("x.Root.run", "x.Mid.run", "x.Hot.run")));
        assertEquals(2_000, cpuOf(profile, List.of("x.Root.run", "x.Mid.run", StackTrie.TRUNCATED)));
        assertEquals(
                profile.getCpuSamplesTotal(),
                profile.getStacksList().stream()
                        .mapToLong(PerfStack::getCpuSamples)
                        .sum());
    }

    @Test
    void aTruncatedPathKeepsItsOwnFrame() {
        StackTrie trie = new StackTrie();
        trie.add(stack("x.Root.run", "x.Mid.run", "x.Hot.run"), OTHER, 100_000, 0, 0);
        fillToTheCap(trie);

        for (int i = 0; i < 2_000; i++) {
            trie.add(stack("x.Root.run", "x.Mid.run", "z.New.m" + i, "ac.cult.cultac.X.check"), OTHER, 1, 0, 0);
            trie.add(stack("x.Root.run", "x.Mid.run", "z.Other.m" + i, "z.Deep.run"), OTHER, 1, 0, 0);
        }

        PerfProfile partner = trie.encode(false, Long.MAX_VALUE).build();
        assertEquals(
                2_000, cpuOf(partner, List.of("(other)", "(other)", Attribution.PACKAGES.getFirst() + "(truncated)")));
        assertEquals(0, cpuOf(partner, List.of("(other)", "(other)", StackTrie.TRUNCATED)));
        assertEquals(1, partner.getStacksCount());
    }

    @Test
    void normalizesGeneratedClassNames() {
        assertEquals("ac.cult.Foo$$Lambda", StackTrie.generatedClass("ac.cult.Foo$$Lambda/0x000001234abc"));
        assertEquals("ac.cult.Foo$$Lambda", StackTrie.generatedClass("ac.cult.Foo$$Lambda.0x000001234abc"));
        assertEquals("ac.cult.Foo$$Lambda", StackTrie.generatedClass("ac.cult.Foo$$Lambda$123/0x0000000800c01234"));
        assertEquals("ac.cult.Foo$$Lambda", StackTrie.generatedClass("ac.cult.Foo$$Lambda$123"));
        assertEquals(
                "java.lang.invoke.LambdaForm$MH",
                StackTrie.generatedClass("java.lang.invoke.LambdaForm$MH/0x00000008001c0400"));
        assertEquals(
                "io.papermc.paper.event.executor.MethodHandleEventExecutorTemplate",
                StackTrie.generatedClass(
                        "io.papermc.paper.event.executor.MethodHandleEventExecutorTemplate/0x0000020001234567"));
        assertEquals(
                "jdk.internal.reflect.GeneratedMethodAccessor",
                StackTrie.generatedClass("jdk.internal.reflect.GeneratedMethodAccessor42"));
        assertEquals(
                "jdk.internal.reflect.GeneratedSerializationConstructorAccessor",
                StackTrie.generatedClass("jdk.internal.reflect.GeneratedSerializationConstructorAccessor7"));
        assertEquals("net.minecraft.world.phys.Vec3", StackTrie.generatedClass("net.minecraft.world.phys.Vec3"));
        assertEquals(
                "it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap",
                StackTrie.generatedClass("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap"));
    }

    @Test
    void generatedClassesOfDifferentJvmsShareAFrame() {
        StackTrie trie = new StackTrie();
        trie.add(stack("x.Root.run", "a.Foo$$Lambda/0x0000000800c01234.accept"), OTHER, 1, 0, 0);
        trie.add(stack("x.Root.run", "a.Foo$$Lambda/0x0000000800d05678.accept"), OTHER, 1, 0, 0);

        PerfProfile profile = trie.encode(true, 1 << 20).build();

        assertEquals(1, profile.getStacksCount());
        assertEquals(List.of("x.Root.run", "a.Foo$$Lambda.accept"), names(profile, profile.getStacks(0)));
        assertEquals(2, profile.getStacks(0).getCpuSamples());
        assertEquals(2, trie.nodes());
    }

    @Test
    void aCancelledEncodeStopsShrinking() {
        StackTrie trie = new StackTrie();
        Random random = new Random(7);
        for (int i = 0; i < 50_000; i++) {
            List<String> frames = new ArrayList<>();
            frames.add("x.Root.run");
            for (int f = 0; f < 4; f++) frames.add("p" + Long.toHexString(random.nextLong()) + ".C.m" + i);
            trie.add(stack(frames.toArray(String[]::new)), OTHER, i < 9_000 ? 1_000 : 1, 0, 0);
        }
        AtomicInteger checks = new AtomicInteger();

        PerfProfile profile = trie.encode(true, 64 * 1024, () -> checks.incrementAndGet() >= 1)
                .build();

        assertEquals(1, checks.get());
        assertTrue(StackTrie.zstd(profile.toByteArray()).length > 64 * 1024);
    }

    private static void fillToTheCap(StackTrie trie) {
        for (int i = 0; trie.nodes() < StackTrie.MAX_NODES; i++) {
            trie.add(stack("x.Root.run", "x.Filler.run", "y.Leaf.m" + i), OTHER, 1, 0, 0);
        }
    }

    @Test
    void emptyStackCountsInTotalsOnly() {
        StackTrie trie = new StackTrie();
        trie.add(stack(), OTHER, 3, 0, 0);
        trie.add(stack("ac.cult.cultac.X.check"), OTHER, 1, 0, 0);

        PerfProfile whole = trie.encode(true, 1 << 20).build();
        PerfProfile partner = trie.encode(false, 1 << 20).build();

        assertEquals(4, whole.getCpuSamplesTotal());
        assertEquals(3, cpuOf(whole, List.of()));
        assertEquals(4, partner.getCpuSamplesTotal());
        assertEquals(1, partner.getStacksCount());
        assertEquals(List.of("ac.cult.cultac.X.check"), names(partner, partner.getStacks(0)));
    }

    @Test
    void addingKnownStacksAllocatesNothing() {
        assertTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean, "needs com.sun.management");
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(threads.isThreadAllocatedMemoryEnabled(), "thread allocation accounting is off");
        StackTrie trie = new StackTrie();
        StackTrie.StackTraceView[] stacks = new StackTrie.StackTraceView[200];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = stack(
                    "x.Root.run",
                    "ac.cult.cultac.Mid" + (i % 20) + ".call",
                    "z.Foo$$Lambda/0x" + Integer.toHexString(0x800c0000 + i % 7) + ".accept",
                    "y.Leaf.m" + i);
        }
        for (int round = 0; round < 200; round++) {
            for (StackTrie.StackTraceView stack : stacks) addAndAttribute(trie, stack);
        }

        long before = threads.getCurrentThreadAllocatedBytes();
        for (int round = 0; round < 100; round++) {
            for (StackTrie.StackTraceView stack : stacks) addAndAttribute(trie, stack);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertTrue(allocated < 1024, allocated + " bytes over 20000 adds");
    }

    private static void addAndAttribute(StackTrie trie, StackTrie.StackTraceView stack) {
        trie.add(stack, Attribution.hasOwnFrame(stack) ? Attribution.OWN : OTHER, 1, 0, 64);
    }

    /** A stack given root first as {@code class.method} frames. */
    static StackTrie.StackTraceView stack(String... rootFirst) {
        String[] types = new String[rootFirst.length];
        String[] methods = new String[rootFirst.length];
        for (int i = 0; i < rootFirst.length; i++) {
            int dot = rootFirst[i].lastIndexOf('.');
            types[i] = rootFirst[i].substring(0, dot);
            methods[i] = rootFirst[i].substring(dot + 1);
        }
        return new StackTrie.StackTraceView() {
            @Override
            public int size() {
                return rootFirst.length;
            }

            @Override
            public String type(int fromLeaf) {
                return types[rootFirst.length - 1 - fromLeaf];
            }

            @Override
            public String method(int fromLeaf) {
                return methods[rootFirst.length - 1 - fromLeaf];
            }
        };
    }

    static List<String> names(PerfProfile profile, PerfStack stack) {
        return stack.getFramesList().stream().map(profile::getFrames).toList();
    }

    private static long cpuOf(PerfProfile profile, List<String> frames) {
        return profile.getStacksList().stream()
                .filter(stack -> names(profile, stack).equals(frames))
                .mapToLong(PerfStack::getCpuSamples)
                .sum();
    }
}
