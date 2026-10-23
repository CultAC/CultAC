package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.cloud.intake.v3.PerfStack;
import com.github.luben.zstd.Zstd;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Samples merged into one call tree per thread group, each node weighted by CPU samples, native samples and allocated
 * bytes, and encoded as a {@link PerfProfile}'s frames and stacks. One thread at a time. Adding a stack whose frames
 * and path are already known allocates nothing.
 */
final class StackTrie {
    /** Frames kept per stack, nearest the leaf. */
    static final int MAX_DEPTH = 128;
    /** A node below this fraction of every kind's total weight folds into its parent. */
    static final double PRUNE_FRACTION = 0.0001;
    /**
     * Past this many nodes the rest of a new path is one {@link #TRUNCATED} child of its deepest existing node, which
     * the cap does not count: at most two per node.
     */
    static final int MAX_NODES = 1 << 18;

    /** The frame standing for the cut-off rest of a path past {@link #MAX_NODES}. */
    static final String TRUNCATED = "(truncated)";
    /** {@link #TRUNCATED} for a rest with a CultAC frame, named as CultAC's so the intake attributes it. */
    static final String TRUNCATED_OWN = Attribution.PACKAGES.getFirst() + TRUNCATED;

    private static final int TRUNCATED_ID = 0;
    private static final int TRUNCATED_OWN_ID = 1;

    private static final Pattern HIDDEN_SUFFIX = Pattern.compile("[/.+]0x\\p{XDigit}+$");
    private static final Pattern NUMBERED_LAMBDA = Pattern.compile("(\\$\\$Lambda)\\$\\d+$");
    private static final Pattern NUMBERED_ACCESSOR =
            Pattern.compile("(Generated(?:Method|Constructor|SerializationConstructor)Accessor)\\d+$");

    static final int ZSTD_LEVEL = 3;

    /** A sampled stack, leaf first. */
    interface StackTraceView {
        int size();

        /** The class name of the frame {@code fromLeaf} calls from the leaf. */
        String type(int fromLeaf);

        /** The method name of the frame {@code fromLeaf} calls from the leaf. */
        String method(int fromLeaf);
    }

    private static final class Node {
        final int frame;
        /** Open-addressed by frame id; null slots are empty. */
        Node[] children;

        int childCount;
        long cpu;
        long nat;
        long alloc;
        long inclusiveCpu;
        long inclusiveNat;
        long inclusiveAlloc;

        Node(int frame) {
            this.frame = frame;
        }

        Node child(int frame) {
            if (children == null) return null;
            int mask = children.length - 1;
            for (int i = slot(frame) & mask; ; i = (i + 1) & mask) {
                Node child = children[i];
                if (child == null || child.frame == frame) return child;
            }
        }

        Node addChild(int frame) {
            if (children == null) children = new Node[4];
            else if ((childCount + 1) * 2 > children.length) {
                Node[] old = children;
                children = new Node[old.length * 2];
                for (Node child : old) {
                    if (child != null) place(child);
                }
            }
            Node child = new Node(frame);
            place(child);
            childCount++;
            return child;
        }

        private void place(Node child) {
            int mask = children.length - 1;
            int i = slot(child.frame) & mask;
            while (children[i] != null) i = (i + 1) & mask;
            children[i] = child;
        }

        private static int slot(int frame) {
            int h = frame * 0x9E3779B9;
            return h ^ (h >>> 16);
        }
    }

    private final Node[] roots = new Node[Attribution.GROUPS.size()];
    /** Frame ids by class name as sampled, then method name. */
    private final Map<String, Map<String, Integer>> frameIds = new HashMap<>();
    /** Frame ids by frame name, which several sampled class names may share. */
    private final Map<String, Integer> frameNames = new HashMap<>();

    private final List<String> frames = new ArrayList<>(List.of(TRUNCATED, TRUNCATED_OWN));
    private final BitSet ownFrames = new BitSet();
    /** Frames a profile without {@code wholeJvm} names; the rest are {@link Attribution#OTHER_NAME}. */
    private final BitSet namedFrames = new BitSet();

    private int nodes;
    private long capped;
    private long cpuTotal;
    private long natTotal;
    private long allocTotal;

    StackTrie() {
        ownFrames.set(TRUNCATED_OWN_ID);
        namedFrames.set(TRUNCATED_ID);
        namedFrames.set(TRUNCATED_OWN_ID);
    }

    void add(StackTraceView stack, int threadGroup, long cpu, long nat, long allocBytes) {
        cpuTotal += cpu;
        natTotal += nat;
        allocTotal += allocBytes;
        Node node = roots[threadGroup];
        if (node == null) node = roots[threadGroup] = new Node(-1);
        for (int i = Math.min(stack.size(), MAX_DEPTH) - 1; i >= 0; i--) {
            String type = stack.type(i);
            String method = stack.method(i);
            int id = frameId(type, method);
            Node child = id < 0 ? null : node.child(id);
            if (child == null) {
                if (nodes >= MAX_NODES) {
                    node = truncated(node, stack, i);
                    capped++;
                    break;
                }
                if (id < 0) {
                    id = intern(type, method);
                    child = node.child(id);
                }
                if (child == null) {
                    child = node.addChild(id);
                    nodes++;
                }
            }
            node = child;
        }
        node.cpu += cpu;
        node.nat += nat;
        node.alloc += allocBytes;
    }

    /** The {@link #TRUNCATED} child of {@code node} for the stack's frames from {@code fromLeaf} to the leaf. */
    private static Node truncated(Node node, StackTraceView stack, int fromLeaf) {
        int frame = TRUNCATED_ID;
        for (int i = fromLeaf; i >= 0; i--) {
            if (Attribution.own(stack.type(i))) {
                frame = TRUNCATED_OWN_ID;
                break;
            }
        }
        Node child = node.child(frame);
        return child != null ? child : node.addChild(frame);
    }

    /** Multiplies the allocated bytes of every node of {@code threadGroup} by {@code factor}, rounded. */
    void scaleAlloc(int threadGroup, double factor) {
        if (roots[threadGroup] != null) scaleAlloc(roots[threadGroup], factor);
    }

    private static void scaleAlloc(Node node, double factor) {
        node.alloc = Math.round(node.alloc * factor);
        if (node.children == null) return;
        for (Node child : node.children) {
            if (child != null) scaleAlloc(child, factor);
        }
    }

    /** Sets the allocated bytes the profile totals, which pruning measures against. */
    void allocTotal(long bytes) {
        allocTotal = bytes;
    }

    long cpuTotal() {
        return cpuTotal;
    }

    /** Nodes of sampled frames, at most {@link #MAX_NODES}. */
    int nodes() {
        return nodes;
    }

    /** Samples whose path was cut at {@link #MAX_NODES}. */
    long capped() {
        return capped;
    }

    /**
     * The tree as a profile's frames, thread groups, stacks and totals: nodes under {@link #PRUNE_FRACTION} of their
     * kind's weight fold into their parent, and the fraction doubles until zstd of the profile fits
     * {@code maxEncodedBytes}. Without {@code wholeJvm} only stacks with a CultAC frame, or of the recorder's own
     * threads, are kept, and a frame of a class outside {@link Attribution#named} is {@link Attribution#OTHER_NAME};
     * totals count all.
     */
    PerfProfile.Builder encode(boolean wholeJvm, long maxEncodedBytes) {
        return encode(wholeJvm, maxEncodedBytes, () -> false);
    }

    /** {@link #encode(boolean, long)} that stops shrinking once {@code cancelled}, with the profile it has. */
    PerfProfile.Builder encode(boolean wholeJvm, long maxEncodedBytes, BooleanSupplier cancelled) {
        for (Node root : roots) {
            if (root != null) inclusive(root);
        }
        double fraction = PRUNE_FRACTION;
        while (true) {
            PerfProfile.Builder profile = emit(wholeJvm, fraction);
            if (fraction >= 1
                    || cancelled.getAsBoolean()
                    || zstd(profile.build().toByteArray()).length <= maxEncodedBytes) {
                return profile;
            }
            fraction *= 2;
        }
    }

    /**
     * The class name of a frame as every JVM names it: hidden classes lose their address suffix, numbered lambda and
     * reflection accessor classes their number.
     */
    static String generatedClass(String type) {
        String name = HIDDEN_SUFFIX.matcher(type).replaceFirst("");
        name = NUMBERED_LAMBDA.matcher(name).replaceFirst("$1");
        return NUMBERED_ACCESSOR.matcher(name).replaceFirst("$1");
    }

    static byte[] zstd(byte[] raw) {
        return Zstd.compress(raw, ZSTD_LEVEL);
    }

    private int frameId(String type, String method) {
        Map<String, Integer> methods = frameIds.get(type);
        Integer id = methods == null ? null : methods.get(method);
        return id == null ? -1 : id;
    }

    private int intern(String type, String method) {
        String frame = generatedClass(type) + "." + method;
        Integer known = frameNames.get(frame);
        int id = known == null ? frames.size() : known;
        if (known == null) {
            frames.add(frame);
            frameNames.put(frame, id);
            if (Attribution.own(type)) ownFrames.set(id);
            if (Attribution.named(type)) namedFrames.set(id);
        }
        frameIds.computeIfAbsent(type, ignored -> new HashMap<>()).put(method, id);
        return id;
    }

    private static void inclusive(Node node) {
        node.inclusiveCpu = node.cpu;
        node.inclusiveNat = node.nat;
        node.inclusiveAlloc = node.alloc;
        if (node.children == null) return;
        for (Node child : node.children) {
            if (child == null) continue;
            inclusive(child);
            node.inclusiveCpu += child.inclusiveCpu;
            node.inclusiveNat += child.inclusiveNat;
            node.inclusiveAlloc += child.inclusiveAlloc;
        }
    }

    private PerfProfile.Builder emit(boolean wholeJvm, double fraction) {
        PerfProfile.Builder profile = PerfProfile.newBuilder()
                .addAllThreadGroups(Attribution.GROUPS)
                .setCpuSamplesTotal(cpuTotal)
                .setNativeSamplesTotal(natTotal)
                .setAllocBytesTotal(allocTotal)
                .setWholeJvm(wholeJvm);
        Emitter emitter = new Emitter(profile, wholeJvm, fraction);
        for (int group = 0; group < roots.length; group++) {
            if (roots[group] != null) emitter.visit(roots[group], group, 0, group == Attribution.PERF);
        }
        return profile;
    }

    private final class Emitter {
        private final PerfProfile.Builder profile;
        private final boolean wholeJvm;
        private final double cpuMin;
        private final double natMin;
        private final double allocMin;
        private final int[] path = new int[MAX_DEPTH];
        private final int[] outputIds = new int[frames.size()];
        private int otherId = -1;

        Emitter(PerfProfile.Builder profile, boolean wholeJvm, double fraction) {
            this.profile = profile;
            this.wholeJvm = wholeJvm;
            cpuMin = cpuTotal * fraction;
            natMin = natTotal * fraction;
            allocMin = allocTotal * fraction;
            Arrays.fill(outputIds, -1);
        }

        /** {@code leaves}: whether the path may leave without {@code wholeJvm}. */
        void visit(Node node, int group, int depth, boolean leaves) {
            long cpu = node.cpu;
            long nat = node.nat;
            long alloc = node.alloc;
            if (node.children != null) {
                for (Node child : node.children) {
                    if (child == null) continue;
                    if (kept(child)) {
                        path[depth] = child.frame;
                        visit(child, group, depth + 1, leaves || ownFrames.get(child.frame));
                    } else {
                        cpu += child.inclusiveCpu;
                        nat += child.inclusiveNat;
                        alloc += child.inclusiveAlloc;
                    }
                }
            }
            if ((cpu | nat | alloc) == 0 || !(wholeJvm || leaves)) return;
            PerfStack.Builder stack = PerfStack.newBuilder()
                    .setThreadGroup(group)
                    .setCpuSamples(cpu)
                    .setNativeSamples(nat)
                    .setAllocBytes(alloc);
            for (int i = 0; i < depth; i++) stack.addFrames(outputId(path[i]));
            profile.addStacks(stack);
        }

        private boolean kept(Node node) {
            return (cpuTotal > 0 && node.inclusiveCpu >= cpuMin)
                    || (natTotal > 0 && node.inclusiveNat >= natMin)
                    || (allocTotal > 0 && node.inclusiveAlloc >= allocMin);
        }

        private int outputId(int frame) {
            if (!wholeJvm && !namedFrames.get(frame)) {
                if (otherId < 0) {
                    otherId = profile.getFramesCount();
                    profile.addFrames(Attribution.OTHER_NAME);
                }
                return otherId;
            }
            int id = outputIds[frame];
            if (id < 0) {
                id = profile.getFramesCount();
                profile.addFrames(frames.get(frame));
                outputIds[frame] = id;
            }
            return id;
        }
    }
}
