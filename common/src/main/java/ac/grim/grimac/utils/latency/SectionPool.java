package ac.grim.grimac.utils.latency;

import ac.grim.grimac.utils.latency.CompensatedWorld.CachedSection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class SectionPool {
    private static final CachedSection CANONICAL_AIR = createCanonicalAir();

    static final long STABILITY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(5);

    public static final java.util.concurrent.atomic.AtomicLong internCallCounter = new java.util.concurrent.atomic.AtomicLong(0);
    public static final java.util.concurrent.atomic.AtomicLong internDedupCounter = new java.util.concurrent.atomic.AtomicLong(0);
    public static final java.util.concurrent.atomic.AtomicLong tryReinternSuccessCounter = new java.util.concurrent.atomic.AtomicLong(0);

    private List<CachedSection> nonUniformSections;

    SectionPool() {
    }

    private static CachedSection createCanonicalAir() {
        CachedSection air = CachedSection.createAirSection();
        air.markShared();
        return air;
    }

    public static SectionPool forChunk(String dimension, int chunkX, int sectionIndex, int chunkZ) {
        return SectionPoolRegistry.forChunk(dimension, chunkX, sectionIndex, chunkZ);
    }

    public static SectionPool forChunk(int chunkX, int sectionIndex, int chunkZ) {
        return forChunk("minecraft:overworld", chunkX, sectionIndex, chunkZ);
    }

    static CachedSection canonicalAirSection() {
        return CANONICAL_AIR;
    }

    CachedSection getAirSection() {
        return CANONICAL_AIR;
    }

    public CachedSection intern(CachedSection section) {
        return intern(section, 0, ComparisonBudget.unlimited(), false).section();
    }

    CachedSection internRetained(CachedSection section) {
        return intern(section, 0, ComparisonBudget.unlimited(), true).section();
    }

    private ReinternResult intern(CachedSection section, int startComparisonIndex, ComparisonBudget comparisonBudget, boolean retainReference) {
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

    public CachedSection tryReintern(CachedSection section) {
        return tryReintern(section, 0, ComparisonBudget.unlimited(), false).section();
    }

    ReinternResult tryReintern(CachedSection section, int startComparisonIndex, ComparisonBudget comparisonBudget) {
        return tryReintern(section, startComparisonIndex, comparisonBudget, false);
    }

    ReinternResult tryReinternRetained(CachedSection section, int startComparisonIndex, ComparisonBudget comparisonBudget) {
        return tryReintern(section, startComparisonIndex, comparisonBudget, true);
    }

    private ReinternResult tryReintern(CachedSection section, int startComparisonIndex, ComparisonBudget comparisonBudget, boolean retainReference) {
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
        PalettedContainerAccessor.RawView view = PalettedContainerAccessor.getRawView(section.states());
        int first = view.storage.get(0);
        for (int i = 1; i < view.storage.getSize(); i++) {
            if (view.storage.get(i) != first) {
                return false;
            }
        }
        return true;
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

    static boolean tryMakeExclusiveForMutation(String dimension, int chunkX, int sectionIndex, int chunkZ, CachedSection section) {
        return SectionPoolRegistry.tryMakeExclusiveForMutation(dimension, chunkX, sectionIndex, chunkZ, section);
    }

    static void releaseSectionReference(String dimension, int chunkX, int sectionIndex, int chunkZ, CachedSection section) {
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

        PalettedContainerAccessor.RawView view = PalettedContainerAccessor.getRawView(section.states());
        int hash = 1;
        hash = 31 * hash + view.storage.getBits();
        hash = 31 * hash + view.storage.getSize();
        hash = 31 * hash + Arrays.hashCode(view.storage.getRaw());
        hash = 31 * hash + view.palette.getClass().hashCode();
        if (view.palette instanceof GlobalPalette<?>) {
            section.cacheContentHash(hash);
            return true;
        }

        int paletteSize = view.palette.getSize();
        hash = 31 * hash + paletteSize;
        for (int i = 0; i < paletteSize; i++) {
            hash = 31 * hash + Block.getId((BlockState) view.palette.valueFor(i));
        }
        section.cacheContentHash(hash);
        return true;
    }

    private static boolean contentEquals(CachedSection a, CachedSection b) {
        if (a.isEmpty() != b.isEmpty()) return false;
        if (a.hasFluid() != b.hasFluid()) return false;
        if (a.isEmpty() && !a.hasFluid()) return true;

        PalettedContainerAccessor.RawView va = PalettedContainerAccessor.getRawView(a.states());
        PalettedContainerAccessor.RawView vb = PalettedContainerAccessor.getRawView(b.states());

        if (va.storage.getBits() != vb.storage.getBits()
                || !Arrays.equals(va.storage.getRaw(), vb.storage.getRaw())) {
            return false;
        }

        boolean aGlobal = va.palette instanceof GlobalPalette<?>;
        boolean bGlobal = vb.palette instanceof GlobalPalette<?>;
        if (aGlobal || bGlobal) {
            return aGlobal == bGlobal;
        }

        if (va.palette.getSize() != vb.palette.getSize()) {
            return false;
        }

        int paletteSize = va.palette.getSize();
        for (int i = 0; i < paletteSize; i++) {
            if (Block.getId((BlockState) va.palette.valueFor(i)) != Block.getId((BlockState) vb.palette.valueFor(i))) {
                return false;
            }
        }
        return true;
    }

    static final class ComparisonBudget {
        private static final ComparisonBudget UNLIMITED = new ComparisonBudget(-1, -1, -1);

        private int contentComparisonsRemaining;
        private int hashCalculationsRemaining;
        private int attemptedComparisonsRemaining;

        private ComparisonBudget(int contentComparisonsRemaining, int hashCalculationsRemaining, int attemptedComparisonsRemaining) {
            this.contentComparisonsRemaining = contentComparisonsRemaining;
            this.hashCalculationsRemaining = hashCalculationsRemaining;
            this.attemptedComparisonsRemaining = attemptedComparisonsRemaining;
        }

        static ComparisonBudget limited(int contentComparisons, int hashCalculations, int attemptedComparisons) {
            return new ComparisonBudget(
                    Math.max(0, contentComparisons),
                    Math.max(0, hashCalculations),
                    Math.max(0, attemptedComparisons));
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

    record ReinternResult(CachedSection section, boolean complete, int nextComparisonIndex) {
    }

    public record DimensionStats(String dimension, int poolCount, int sectionReferences) {
    }
}
