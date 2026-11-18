package ac.cult.cultac.utils.latency;

/** The section pools' sizes, kept by counters: no lock, and no block state loaded to read them. */
public final class SectionPoolCounters {
    private SectionPoolCounters() {}

    /** Dimensions with pools. */
    public static int dimensions() {
        return SectionPoolRegistry.dimensionCount();
    }

    /** {@link SectionPool#poolCount()}. */
    public static int pools() {
        return SectionPoolRegistry.livePoolCount();
    }

    /** {@link SectionPool#totalReferences()}. */
    public static long references() {
        return SectionPoolRegistry.liveReferenceCount();
    }
}
