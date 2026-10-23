package ac.cult.cultac.cloud.perf;

import java.util.List;

/** Which frames are CultAC's, and which thread group a sampled thread belongs to. */
final class Attribution {
    /** CultAC's packages; the plugin jars relocate every shaded library under {@code ac.cult.cultac.shaded}. */
    static final List<String> PACKAGES =
            List.of("ac.cult.cultac.", "ac.cult.blocksim.", "ac.cult.reload.", "ac.cult.velocity.");

    /**
     * The thread groups by id, as {@code PerfProfile.thread_groups} names them. "perf" is the recorder's own threads,
     * whose samples are its overhead and never the anticheat's.
     */
    static final List<String> GROUPS = List.of("tick", "netty", "anticheat", "other", "perf");

    /**
     * Packages besides CultAC's that a profile without {@code wholeJvm} names: the JDK, the server and proxy, and
     * zstd-jni, which the plugin jars ship unrelocated.
     */
    static final List<String> PUBLIC_PACKAGES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "io.netty.",
            "net.minecraft.",
            "com.mojang.",
            "org.bukkit.",
            "org.spigotmc.",
            "io.papermc.",
            "com.destroystokyo.paper.",
            "ca.spottedleaf.",
            "com.velocitypowered.",
            "com.github.luben.zstd.");

    /** The name a class outside {@link #named} has in a profile without {@code wholeJvm}. */
    static final String OTHER_NAME = "(other)";

    static final int TICK = 0;
    static final int NETTY = 1;
    static final int OWN = 2;
    static final int OTHER = 3;
    static final int PERF = 4;

    private Attribution() {}

    /** Whether a class is CultAC's own code. */
    static boolean own(String type) {
        for (int i = 0; i < PACKAGES.size(); i++) {
            if (type.startsWith(PACKAGES.get(i))) return true;
        }
        return false;
    }

    /**
     * Whether a profile without {@code wholeJvm} may name a class: CultAC's, of {@link #PUBLIC_PACKAGES}, the
     * recorder's own parenthesized markers, or an array of a primitive or of such a class, as JFR names arrays.
     */
    static boolean named(String type) {
        int dims = 0;
        while (dims < type.length() && type.charAt(dims) == '[') dims++;
        if (dims > 0) {
            if (type.length() == dims + 1) return true;
            if (type.charAt(dims) != 'L' || !type.endsWith(";")) return false;
            type = type.substring(dims + 1, type.length() - 1);
        }
        if (type.startsWith("(") || own(type)) return true;
        for (int i = 0; i < PUBLIC_PACKAGES.size(); i++) {
            if (type.startsWith(PUBLIC_PACKAGES.get(i))) return true;
        }
        return false;
    }

    static boolean hasOwnFrame(StackTrie.StackTraceView stack) {
        for (int i = 0; i < stack.size(); i++) {
            if (own(stack.type(i))) return true;
        }
        return false;
    }

    /** The group of a thread by its Java name; {@link #OTHER} for a thread without one. */
    static int threadGroup(String name) {
        if (name == null) return OTHER;
        if (name.equals("Server thread")) return TICK;
        if (name.startsWith("Netty ")) return NETTY;
        if (name.startsWith("cult-")) return OWN;
        return OTHER;
    }

    /** The group of a sampled thread: {@link #PERF} for the recording or the stream's dispatch thread, else by name. */
    static int threadGroup(String name, long threadId, long recordingThread, long dispatchThread) {
        if (threadId >= 0 && (threadId == recordingThread || threadId == dispatchThread)) return PERF;
        return threadGroup(name);
    }
}
