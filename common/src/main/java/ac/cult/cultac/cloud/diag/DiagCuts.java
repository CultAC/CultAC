package ac.cult.cultac.cloud.diag;

import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.wire.IntakeWire;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Turns a cut into its parts: the ring's ticks in the cut's range in seq order, traces for all, full texts for the full
 * range and for flagged ticks, header and history on part 0, coverage on the last. A bundle over its byte limit drops
 * full texts furthest from the middle of the full range first, then whole ticks the same way.
 */
final class DiagCuts {
    /** Room a part keeps for its own fields besides its ticks. */
    private static final int PART_FIELDS = 64;
    /** The most UTF-8 bytes of the header; the client's brand is in it. */
    static final int MAX_HEADER_BYTES = 256;

    private DiagCuts() {}

    record Coverage(int value, String reason) {
        static final Coverage FULL = new Coverage(DiagWire.COVERAGE_FULL, "");

        static Coverage none(String reason) {
            return new Coverage(DiagWire.COVERAGE_NONE, reason);
        }
    }

    /** What the cut covers of the ring, and why not all of it. */
    static Coverage coverage(Cut cut, DiagWatch watch, List<DiagRing.Tick> range) {
        DiagRing ring = watch.ring;
        if (range.isEmpty()) {
            if (watch.ended && watch.endReason != null) return Coverage.none(watch.endReason);
            if (watch.firstSeq == Long.MIN_VALUE || watch.firstSeq > cut.toSeq()) return Coverage.none("late-start");
            if (ring != null && ring.evictedThrough() >= cut.toSeq()) return Coverage.none("evicted");
            return Coverage.none("dropped");
        }
        String reason = null;
        if (watch.firstSeq > cut.fromSeq()) reason = "late-start";
        else if (ring.evictedThrough() >= cut.fromSeq()) reason = "evicted";
        else if (ring.newest() < cut.toSeq())
            reason = watch.ended && watch.endReason != null ? watch.endReason : "dropped";
        else if (watch.droppedWithin(cut.fromSeq(), cut.toSeq())) reason = "dropped";
        return reason == null ? Coverage.FULL : new Coverage(DiagWire.COVERAGE_PARTIAL, reason);
    }

    /** The encoded parts of a bundle: records the socket queue takes. */
    static List<byte[]> parts(
            UUID player,
            Cut cut,
            String header,
            String history,
            List<DiagRing.Tick> range,
            Coverage coverage,
            int partBytes,
            int bundleBytes) {
        header = truncate(header, MAX_HEADER_BYTES);
        if (history != null) history = truncate(history, partBytes / 2);
        long middle = cut.fullFromSeq() + (cut.fullToSeq() - cut.fullFromSeq()) / 2;
        List<Entry> entries = new ArrayList<>(range.size());
        for (DiagRing.Tick tick : range) {
            boolean full = tick.full != null
                    && (tick.seq >= cut.fullFromSeq() && tick.seq <= cut.fullToSeq() || !tick.flags.isEmpty());
            entries.add(new Entry(tick, full));
        }
        int fixed = utf8(header) + (history == null ? 0 : utf8(history)) + 4 * PART_FIELDS;
        long budget = Math.max(0, bundleBytes - fixed);
        long total = 0;
        for (Entry entry : entries) total += entry.size;
        if (total > budget) {
            List<Entry> byDistance = new ArrayList<>(entries);
            byDistance.sort(Comparator.comparingLong((Entry entry) -> -Math.abs(entry.tick.seq - middle)));
            for (Entry entry : byDistance) {
                if (total <= budget) break;
                if (!entry.full) continue;
                total -= entry.size;
                entry.full(false);
                total += entry.size;
            }
            for (Entry entry : byDistance) {
                if (total <= budget) break;
                total -= entry.size;
                entry.dropped = true;
            }
        }

        ByteString playerBytes = ByteString.copyFrom(IntakeWire.uuid(player));
        ByteString bundle = ByteString.copyFrom(cut.bundle());
        int tickBudget = partBytes - PART_FIELDS;
        List<byte[]> parts = new ArrayList<>();
        DiagPart.Builder part = newPart(playerBytes, bundle, 0);
        part.setHeader(header);
        if (history != null) part.setHistory(history);
        int partSize = part.build().getSerializedSize();
        boolean lost = false;
        for (Entry entry : entries) {
            lost |= entry.dropped;
            if (entry.dropped) continue;
            if (entry.size > tickBudget && entry.full) entry.full(false);
            if (entry.size > tickBudget) {
                lost = true;
                continue;
            }
            if (partSize + entry.size > tickBudget && part.getTicksCount() > 0) {
                parts.add(DiagWire.record(part.build()));
                part = newPart(playerBytes, bundle, parts.size());
                partSize = part.build().getSerializedSize();
            }
            part.addTicks(entry.encoded);
            partSize += entry.size;
        }
        if (lost && coverage.value() == DiagWire.COVERAGE_FULL) {
            coverage = new Coverage(DiagWire.COVERAGE_PARTIAL, "dropped");
        }
        part.setLast(true).setCoverage(DiagCoverage.forNumber(coverage.value()));
        if (!coverage.reason().isEmpty()) part.setReason(coverage.reason());
        parts.add(DiagWire.record(part.build()));
        return parts;
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** {@code text} cut to at most {@code maxBytes} UTF-8 bytes, never mid-character. */
    static String truncate(String text, int maxBytes) {
        if (text.length() <= maxBytes / 3) return text;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** A bundle's closing part with no ticks: part {@code index}, coverage and reason. */
    static byte[] closing(UUID player, byte[] bundle, int index, Coverage coverage) {
        DiagPart.Builder part = newPart(
                        ByteString.copyFrom(IntakeWire.uuid(player)), ByteString.copyFrom(bundle), index)
                .setLast(true)
                .setCoverage(DiagCoverage.forNumber(coverage.value()));
        if (!coverage.reason().isEmpty()) part.setReason(coverage.reason());
        return DiagWire.record(part.build());
    }

    private static DiagPart.Builder newPart(ByteString player, ByteString bundle, int index) {
        return DiagPart.newBuilder().setPlayer(player).setBundle(bundle).setPart(index);
    }

    private static final class Entry {
        final DiagRing.Tick tick;
        boolean full;
        boolean dropped;
        DiagTick encoded;
        int size;

        Entry(DiagRing.Tick tick, boolean full) {
            this.tick = tick;
            full(full);
        }

        void full(boolean full) {
            this.full = full;
            encoded = DiagWire.tick(tick.seq, tick.offset, tick.flags, tick.trace, full ? tick.full : null);
            int length = encoded.getSerializedSize();
            size = CodedOutputStream.computeTagSize(5) + CodedOutputStream.computeUInt32SizeNoTag(length) + length;
        }
    }
}
