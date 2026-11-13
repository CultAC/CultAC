package ac.cult.cultac.utils.latency;

import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.ApiStatus;

public final class SectionPool {
    private static final CachedSection CANONICAL_AIR = createCanonicalAir();

    static final long STABILITY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int WIRE_CANDIDATES = 8;

    public static final AtomicLong internCallCounter = new AtomicLong(0);
    public static final AtomicLong internDedupCounter = new AtomicLong(0);
    public static final AtomicLong tryReinternSuccessCounter = new AtomicLong(0);
    /** Block containers offered to a pool's wire match, each counted once whatever it holds. */
    public static final LongAdder wireOfferCounter = new LongAdder();
    /** Offered block containers that share the storage of a pooled section. */
    public static final LongAdder wireHitCounter = new LongAdder();
    /** Pooled sections compared with offered block containers. */
    public static final LongAdder wireCandidateCounter = new LongAdder();
    /**
     * Offered block containers that no pooled section held, read in full or shared with a {@link SectionStore} entry,
     * into storage with a non-zero bit width, including a uniform container written with a multi-entry palette. A hit
     * always has a non-zero bit width, so hits / (hits + this) is the share of such containers that share a pooled
     * section's storage.
     */
    public static final LongAdder multiValueFullReadCounter = new LongAdder();
    /**
     * Storage clones on a write to a section whose storage is shared only through a hit: the pooled section's next
     * write after any hit, and the first write of a section a hit returned.
     */
    public static final LongAdder sharedCloneCounter = new LongAdder();

    private List<CachedSection> nonUniformSections;

    SectionPool() {}

    /** The air section every pool interns an empty section without fluid to; shared, so writers replace it first. */
    static CachedSection canonicalAir() {
        return CANONICAL_AIR;
    }

    private static CachedSection createCanonicalAir() {
        CachedSection air = CachedSection.createAirSection();
        air.markShared();
        return air;
    }

    public static SectionPool forChunk(String dimension, int chunkX, int sectionIndex, int chunkZ) {
        return SectionPoolRegistry.forChunk(dimension, chunkX, sectionIndex, chunkZ);
    }

    /** The pool at a section position if one exists; null otherwise and for a null dimension. Creates nothing. */
    public static SectionPool existing(String dimension, int chunkX, int sectionIndex, int chunkZ) {
        return SectionPoolRegistry.existing(dimension, chunkX, sectionIndex, chunkZ);
    }

    /**
     * A copy-on-write view of a pooled section whose contents the block container at the reader index holds exactly,
     * read past that container; null, with the reader index unchanged, when none does. A pooled section's storage is
     * not written until it leaves the pool under this lock, and the view marks it shared before then. The match carries
     * the pooled section's fluid count, which equal contents share.
     */
    synchronized PalettedSection.PooledRead sharedBlocks(
            ByteBuf input,
            boolean lengthPrefixed,
            int sourceRegistrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize) {
        return sharedBlocks(input, lengthPrefixed, sourceRegistrySize, mapper, targetRegistrySize, ReadCounts.NOW);
    }

    /** As {@link #sharedBlocks(ByteBuf, boolean, int, IntUnaryOperator, int)}, its counts going to {@code counts}. */
    synchronized PalettedSection.PooledRead sharedBlocks(
            ByteBuf input,
            boolean lengthPrefixed,
            int sourceRegistrySize,
            IntUnaryOperator mapper,
            int targetRegistrySize,
            ReadCounts counts) {
        counts.offer();
        if (nonUniformSections == null) return null;
        int copiedAt = PalettedSection.NO_WORD_COPY;
        int candidates = Math.min(nonUniformSections.size(), WIRE_CANDIDATES);
        for (int i = 0; i < candidates; i++) {
            CachedSection pooled = nonUniformSections.get(i);
            PalettedSection states = pooled.states();
            copiedAt = states.skipIfEncodes(
                    input, lengthPrefixed, sourceRegistrySize, mapper, targetRegistrySize, copiedAt);
            if (copiedAt == PalettedSection.MATCHED) {
                counts.hit(i + 1);
                return new PalettedSection.PooledRead(states.copyOnWriteFromPool(), pooled.fluidCount());
            }
        }
        counts.miss(candidates);
        return null;
    }

    /**
     * The wire match and full read counts of one block container read: added to the counters as they occur, or held
     * until the consumer that keeps the read adds them. A holder is confined to the thread that reads.
     */
    @ApiStatus.Internal
    public static final class ReadCounts {
        static final ReadCounts NOW = new ReadCounts();
        private static final int OFFER = 1, HIT = 2, FULL_READ = 4, CANDIDATES = 3;
        private int held;

        void offer() {
            if (this == NOW) wireOfferCounter.increment();
            else held |= OFFER;
        }

        void hit(int candidates) {
            if (this == NOW) {
                wireCandidateCounter.add(candidates);
                wireHitCounter.increment();
            } else held |= HIT | candidates << CANDIDATES;
        }

        void miss(int candidates) {
            if (this == NOW) wireCandidateCounter.add(candidates);
            else held |= candidates << CANDIDATES;
        }

        void fullRead() {
            if (this == NOW) multiValueFullReadCounter.increment();
            else held |= FULL_READ;
        }

        /** The counts held since the last take, never negative; none are held afterwards. */
        public int take() {
            int taken = held;
            held = 0;
            return taken;
        }

        /** Adds counts {@link #take} gave to the counters. */
        public static void add(int counts) {
            if ((counts & OFFER) != 0) wireOfferCounter.increment();
            wireCandidateCounter.add(counts >>> CANDIDATES);
            if ((counts & HIT) != 0) wireHitCounter.increment();
            if ((counts & FULL_READ) != 0) multiValueFullReadCounter.increment();
        }
    }

    CachedSection internRetained(CachedSection section) {
        return intern(section, 0, ComparisonBudget.unlimited(), true).section();
    }

    private ReinternResult intern(
            CachedSection section,
            int startComparisonIndex,
            ComparisonBudget comparisonBudget,
            boolean retainReference) {
        internCallCounter.incrementAndGet();

        if (section.isEmpty() && !section.hasFluid()) {
            internDedupCounter.incrementAndGet();
            return new ReinternResult(CANONICAL_AIR, true, 0);
        }

        if (isUniform(section)) {
            return new ReinternResult(section, true, 0);
        }

        synchronized (this) {
            int startIndex = Math.max(0, startComparisonIndex);
            if (nonUniformSections == null || startIndex >= nonUniformSections.size()) {
                return addNonUniformSection(section, retainReference);
            }

            if (!ensureContentHash(section, comparisonBudget)) {
                return new ReinternResult(section, false, startIndex);
            }

            int hash = section.cachedContentHash();
            for (int i = startIndex; i < nonUniformSections.size(); i++) {
                if (!comparisonBudget.consumeAttemptedComparison()) {
                    return new ReinternResult(section, false, i);
                }

                CachedSection candidate = nonUniformSections.get(i);
                if (!ensureContentHash(candidate, comparisonBudget)) {
                    return new ReinternResult(section, false, i);
                }

                if (hash != candidate.cachedContentHash()) {
                    continue;
                }
                if (!comparisonBudget.consumeContentComparison()) {
                    return new ReinternResult(section, false, i);
                }
                if (contentEquals(section, candidate)) {
                    internDedupCounter.incrementAndGet();
                    if (retainReference) {
                        candidate.retainPoolReference();
                    }
                    return new ReinternResult(candidate, true, 0);
                }
            }
            return addNonUniformSection(section, retainReference);
        }
    }

    private ReinternResult addNonUniformSection(CachedSection section, boolean retainReference) {
        if (nonUniformSections == null) {
            nonUniformSections = new ArrayList<>(2);
        }
        section.markShared();
        section.clearPoolReferences();
        if (retainReference) {
            section.retainPoolReference();
        }
        nonUniformSections.add(section);
        return new ReinternResult(section, true, 0);
    }

    ReinternResult tryReinternRetained(
            CachedSection section, int startComparisonIndex, ComparisonBudget comparisonBudget) {
        return tryReintern(section, startComparisonIndex, comparisonBudget, true);
    }

    private ReinternResult tryReintern(
            CachedSection section,
            int startComparisonIndex,
            ComparisonBudget comparisonBudget,
            boolean retainReference) {
        if (section.isShared()) {
            return new ReinternResult(section, true, 0);
        }
        if (!isStableForReintern(section, System.nanoTime())) {
            return new ReinternResult(section, true, 0);
        }

        ReinternResult result = intern(section, startComparisonIndex, comparisonBudget, retainReference);
        if (result.complete() && result.section() != section) {
            tryReinternSuccessCounter.incrementAndGet();
        }
        return result;
    }

    static boolean isStableForReintern(CachedSection section, long nowNanos) {
        return nowNanos - section.getLastMutatedNanos() >= STABILITY_DELAY_NANOS;
    }

    private static boolean isUniform(CachedSection section) {
        return section.states().isUniform();
    }

    synchronized int nonUniformPoolSize() {
        return nonUniformSections == null ? 0 : nonUniformSections.size();
    }

    public static int globalNonUniformPoolSize() {
        return SectionPoolRegistry.globalNonUniformPoolSize();
    }

    public static int globalUniformPoolSize() {
        return SectionPoolRegistry.globalUniformPoolSize();
    }

    public static int poolCount() {
        return SectionPoolRegistry.poolCount();
    }

    public static int totalReferences() {
        return SectionPoolRegistry.totalReferences();
    }

    public static List<DimensionStats> dimensionStats() {
        return SectionPoolRegistry.dimensionStats();
    }

    static boolean tryMakeExclusiveForMutation(
            String dimension, int chunkX, int sectionIndex, int chunkZ, CachedSection section) {
        return SectionPoolRegistry.tryMakeExclusiveForMutation(dimension, chunkX, sectionIndex, chunkZ, section);
    }

    static void releaseSectionReference(
            String dimension, int chunkX, int sectionIndex, int chunkZ, CachedSection section) {
        SectionPoolRegistry.releaseSectionReference(dimension, chunkX, sectionIndex, chunkZ, section);
    }

    synchronized void releaseSectionReference(CachedSection section) {
        int sectionIndex = indexOfPooledSection(section);
        if (sectionIndex >= 0 && section.releasePoolReference() <= 0) {
            removePooledSection(sectionIndex, section);
        }
    }

    synchronized boolean detachForSingleReferenceMutation(CachedSection section) {
        if (section == CANONICAL_AIR || nonUniformSections == null) {
            return false;
        }

        int sectionIndex = indexOfPooledSection(section);
        if (sectionIndex < 0 || section.poolReferences() > 1) {
            return false;
        }

        removePooledSection(sectionIndex, section);
        return true;
    }

    private int indexOfPooledSection(CachedSection section) {
        if (section == CANONICAL_AIR || nonUniformSections == null) {
            return -1;
        }

        for (int i = 0; i < nonUniformSections.size(); i++) {
            if (nonUniformSections.get(i) == section) {
                return i;
            }
        }
        return -1;
    }

    private void removePooledSection(int sectionIndex, CachedSection section) {
        nonUniformSections.remove(sectionIndex);
        section.clearPoolReferences();
        section.markPrivate();
        if (nonUniformSections.isEmpty()) {
            nonUniformSections = null;
        }
    }

    private static boolean ensureContentHash(CachedSection section, ComparisonBudget comparisonBudget) {
        if (section.hasCachedContentHash()) {
            return true;
        }
        if (!comparisonBudget.consumeHashCalculation()) {
            return false;
        }

        section.cacheContentHash(section.states().contentHash());
        return true;
    }

    private static boolean contentEquals(CachedSection a, CachedSection b) {
        if (a.isEmpty() != b.isEmpty()) return false;
        if (a.hasFluid() != b.hasFluid()) return false;
        if (a.isEmpty() && !a.hasFluid()) return true;

        return a.states().sameContents(b.states());
    }

    static final class ComparisonBudget {
        private static final ComparisonBudget UNLIMITED = new ComparisonBudget(-1, -1, -1);

        private int contentComparisonsRemaining;
        private int hashCalculationsRemaining;
        private int attemptedComparisonsRemaining;

        private ComparisonBudget(
                int contentComparisonsRemaining, int hashCalculationsRemaining, int attemptedComparisonsRemaining) {
            this.contentComparisonsRemaining = contentComparisonsRemaining;
            this.hashCalculationsRemaining = hashCalculationsRemaining;
            this.attemptedComparisonsRemaining = attemptedComparisonsRemaining;
        }

        static ComparisonBudget limited(int contentComparisons, int hashCalculations, int attemptedComparisons) {
            return new ComparisonBudget(
                    Math.max(0, contentComparisons), Math.max(0, hashCalculations), Math.max(0, attemptedComparisons));
        }

        static ComparisonBudget unlimited() {
            return UNLIMITED;
        }

        boolean consumeAttemptedComparison() {
            if (attemptedComparisonsRemaining < 0) return true;
            if (attemptedComparisonsRemaining == 0) return false;
            attemptedComparisonsRemaining--;
            return true;
        }

        boolean consumeHashCalculation() {
            if (hashCalculationsRemaining < 0) return true;
            if (hashCalculationsRemaining == 0) return false;
            hashCalculationsRemaining--;
            return true;
        }

        boolean consumeContentComparison() {
            if (contentComparisonsRemaining < 0) return true;
            if (contentComparisonsRemaining == 0) return false;
            contentComparisonsRemaining--;
            return true;
        }
    }

    record ReinternResult(CachedSection section, boolean complete, int nextComparisonIndex) {}

    public record DimensionStats(String dimension, int poolCount, int sectionReferences) {}
}
