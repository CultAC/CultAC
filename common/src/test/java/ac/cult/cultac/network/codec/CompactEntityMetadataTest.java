package ac.cult.cultac.network.codec;

import static ac.cult.cultac.network.ImplicitThrows.assertSameOutcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.ImplicitThrows;
import ac.cult.cultac.network.ImplicitThrows.Outcome;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.PacketOwner;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.FrameLease;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Packed metadata entries decode, compare, print and look up exactly as the list of entry objects built one by one
 * from the same wire values, for every observed version, with and without a lent frame, on valid, duplicate, unknown
 * and truncated input.
 */
class CompactEntityMetadataTest {
    private static final ItemRegistry ITEMS = new ItemRegistry(DataTables.defaults());
    private static final Function<String, PaintingSize> PAINTINGS =
            name -> name.hashCode() % 2 == 0 ? new PaintingSize(2, 1) : null;
    private static final int PACKETS = 4_000;
    private static final int WARM_THROWS = 100_000;

    private int packedSeen;

    @Test
    void everyReaderDecodesWhatEntryObjectsBuiltOneByOneDecode() {
        int failed = 0, unresolved = 0;
        for (ProtocolVersion version : ProtocolVersion.values()) {
            var registries = registries(version);
            var held = new ModelEntityMetadataValues(ProtocolCodecs.decoder(), version, registries, PAINTINGS);
            var serializers = Serializers.probe(version, registries);
            var random = new Random(version.ordinal() * 31L + 7);
            for (int packet = 0; packet < PACKETS; packet++) {
                byte[] wire = packet(random, serializers);
                for (boolean lent : new boolean[] {false, true}) {
                    var expected = decode(wire, lent, (input, lease) -> legacy(version, registries, input, lease));
                    var heldResult = decode(wire, lent, held::read);
                    var once = decode(
                            wire,
                            lent,
                            (input, lease) -> new ModelEntityMetadataValues(
                                            ProtocolCodecs.decoder(), version, registries, PAINTINGS)
                                    .readOnce(input, lease));
                    String where = version + " packet " + packet + (lent ? " lent" : " copied");
                    assertEquals(expected, heldResult, where);
                    assertEquals(expected, once, where);
                    if (expected.startsWith("throws")) failed++;
                    else if (expected.startsWith("null")) unresolved++;
                }
            }
        }
        assertTrue(
                packedSeen > 1_000 && failed > 1_000 && unresolved > 10, packedSeen + " " + failed + " " + unresolved);
    }

    @Test
    void aSinglePacketReadInsideAnotherOnTheSameThreadDecodesBothExactly() {
        var version = ProtocolVersion.V26_3;
        var registries = registries(version);
        var serializers = Serializers.probe(version, registries);
        var random = new Random(11);
        int[] nested = {0};
        Function<String, PaintingSize> reentrant = name -> {
            byte[] inner = packet(random, serializers);
            assertEquals(
                    decode(inner, false, (input, lease) -> legacy(version, registries, input, lease)),
                    decode(
                            inner,
                            false,
                            (input, lease) -> new ModelEntityMetadataValues(
                                            ProtocolCodecs.decoder(), version, registries, PAINTINGS)
                                    .readOnce(input, lease)));
            nested[0]++;
            return PAINTINGS.apply(name);
        };
        for (int packet = 0; packet < PACKETS; packet++) {
            byte[] wire = packet(random, serializers);
            var expected = decode(wire, false, (input, lease) -> legacy(version, registries, input, lease));
            var once = decode(
                    wire,
                    false,
                    (input, lease) -> new ModelEntityMetadataValues(
                                    ProtocolCodecs.decoder(), version, registries, reentrant)
                            .readOnce(input, lease));
            assertEquals(expected, once, "packet " + packet);
        }
        assertTrue(nested[0] > 100, nested[0] + " nested reads");
    }

    /** {@code version}'s registries, with 40 wire painting variants. */
    private static WireRegistryState registries(ProtocolVersion version) {
        var registries = new WireRegistryState(version, () -> null);
        var paintings = new ArrayList<WireValueDecoder.RegistryEntry>();
        for (int i = 0; i < 40; i++) paintings.add(new WireValueDecoder.RegistryEntry("p" + i, null));
        registries.append(new WireValueDecoder.RegistryValues("minecraft:painting_variant", paintings));
        return registries;
    }

    @Test
    void packedEntriesBehaveAsAnImmutableListOfTheirEntries() {
        var random = new Random(5);
        for (int round = 0; round < 2_000; round++) {
            int size = 3 + random.nextInt(round % 10 == 0 ? 80 : 6);
            int[] indices = new int[size], offsets = new int[size], lengths = new int[size];
            Object[] values = new Object[size];
            int cursor = 3;
            for (int i = 0; i < size; i++) {
                indices[i] = index(random);
                values[i] = random.nextInt(4) == 0 ? null : (byte) random.nextInt();
                offsets[i] = cursor;
                lengths[i] = 1 + random.nextInt(4);
                cursor += lengths[i];
            }
            byte[] payload = new byte[cursor + 2];
            random.nextBytes(payload);
            var compact = EntityMetadata.entries(size, indices, values, offsets, lengths, payload, 0);
            assertInstanceOf(EntityMetadata.Entries.class, compact);
            var plain = new EntityMetadata.Entry[size];
            for (int i = 0; i < size; i++)
                plain[i] = EntityMetadata.Entry.of(indices[i], values[i], payload, offsets[i], lengths[i]);
            List<EntityMetadata.Entry> list = List.of(plain);
            assertList(list, compact);
            for (int index = -3; index < 260; index++) {
                assertEquals(WatchableIndexUtil.getIndex(list, index), WatchableIndexUtil.getIndex(compact, index));
                assertEquals(WatchableIndexUtil.has(list, index), WatchableIndexUtil.has(compact, index));
                assertEquals(WatchableIndexUtil.value(list, index), WatchableIndexUtil.value(compact, index));
                var found = WatchableIndexUtil.getIndex(compact, index);
                if (found != null) assertSame(compact.get(compact.indexOf(found)), found, "first entry, kept");
            }
            var record = new EntityMetadata(9, compact);
            assertSame(compact, record.packedItems());
            assertEquals(new EntityMetadata(9, list), record);
            assertEquals(new EntityMetadata(9, list).hashCode(), record.hashCode());
            assertEquals(new EntityMetadata(9, list).toString(), record.toString());
        }
    }

    @Test
    void entriesThatAreNotOneContiguousRangeStayEntryObjects() {
        byte[] payload = new byte[16];
        int[] indices = {1, 2, 3}, lengths = {2, 2, 2};
        Object[] values = {(byte) 1, (byte) 2, (byte) 3};
        var gap = EntityMetadata.entries(3, indices, values, new int[] {0, 3, 5}, lengths, payload, 0);
        var wide = EntityMetadata.entries(3, new int[] {1, 256, 3}, values, new int[] {0, 2, 4}, lengths, payload, 0);
        var contiguous = EntityMetadata.entries(3, indices, values, new int[] {0, 2, 4}, lengths, payload, 0);
        assertFalse(gap instanceof EntityMetadata.Entries);
        assertFalse(wide instanceof EntityMetadata.Entries);
        assertInstanceOf(EntityMetadata.Entries.class, contiguous);
        assertEquals(256, wide.get(1).id());
        assertEquals(ByteBuffer.wrap(payload, 3, 2), gap.get(1).bytes());
        assertEquals(ByteBuffer.wrap(payload, 2, 2), contiguous.get(1).bytes());
    }

    private static void assertList(List<EntityMetadata.Entry> expected, List<EntityMetadata.Entry> actual) {
        assertEquals(expected, actual);
        assertEquals(actual, expected);
        assertEquals(expected.hashCode(), actual.hashCode());
        assertEquals(expected.toString(), actual.toString());
        assertEquals(expected.size(), actual.size());
        assertSame(actual.get(0), actual.get(0));
        assertEquals(new ArrayList<>(expected), new ArrayList<>(actual));
        assertEquals(List.copyOf(expected), List.copyOf(actual));
        var first = expected.get(0);
        assertEquals(expected.indexOf(first), actual.indexOf(first));
        assertEquals(expected.lastIndexOf(first), actual.lastIndexOf(first));
        assertEquals(expected.contains(first), actual.contains(first));
        assertEquals(expected.subList(1, expected.size()), actual.subList(1, actual.size()));
    }

    /**
     * Every list operation, including each mutator on the list, its views and its iterators in every state, returns
     * or throws exactly what the {@link List#of} list of the same entries does.
     */
    @Test
    void packedEntriesAnswerEveryListOperationAsListOfDoes() {
        compareEveryListOperation();
    }

    /** As above once every JDK site the {@link List#of} lists throw from has thrown often enough to run compiled. */
    @Test
    void packedEntriesAnswerEveryListOperationAsListOfDoesOnceTheJdkThrowSitesRunHot() {
        Map<String, Runnable> sites = new LinkedHashMap<>();
        for (View view : views(lists())) {
            for (var operation : operations(view.base(), view.absent()).entrySet()) {
                Runnable call = () -> operation.getValue().apply(view.base());
                var thrown = Outcome.of(() -> run(call)).thrown();
                if (thrown == null) continue;
                var trace = thrown.getStackTrace();
                String site = trace.length > 0
                        ? thrown.getClass().getName() + " at " + trace[0]
                        : operation.getKey() + " on " + view.base().getClass().getName();
                sites.putIfAbsent(site, call);
            }
        }
        int hot = 0;
        for (Runnable call : sites.values()) {
            for (int i = 0; i < WARM_THROWS; i++) {
                try {
                    call.run();
                } catch (RuntimeException ignored) {
                }
            }
            var thrown = Outcome.of(() -> run(call)).thrown();
            if (thrown != null && ImplicitThrows.preallocated(thrown)) hot++;
        }
        System.out.println("LIST-THROW-SITES " + sites.size() + " warmed, " + hot + " throw preallocated");
        compareEveryListOperation();
    }

    private record Lists(
            List<List<EntityMetadata.Entry>> bases,
            List<Supplier<List<EntityMetadata.Entry>>> packs,
            List<EntityMetadata.Entry> absents) {}

    /** A {@link List#of} list or view, the same view of a fresh packed list on each call, and an absent entry. */
    private record View(
            String where,
            List<EntityMetadata.Entry> base,
            Supplier<List<EntityMetadata.Entry>> packed,
            EntityMetadata.Entry absent) {}

    private static void compareEveryListOperation() {
        var lists = lists();
        // List.of lists throw out of range from an array access, which the JIT turns message-less once it traps.
        for (int i = 0; i < lists.bases().size(); i++) {
            var base = lists.bases().get(i);
            var packed = lists.packs().get(i).get();
            for (int index : new int[] {-1, base.size()})
                assertSameOutcome(
                        Outcome.of(() -> base.get(index)),
                        Outcome.of(() -> packed.get(index)),
                        "list get(" + index + ")");
        }
        int compared = 0;
        for (View view : views(lists))
            compared += compareOperations(view.where(), view.base(), view.packed().get(), view.absent());
        assertTrue(compared > 1_000, compared + " operations");
    }

    private static List<View> views(Lists lists) {
        var views = new ArrayList<View>();
        for (int i = 0; i < lists.bases().size(); i++) {
            var base = lists.bases().get(i);
            var packed = lists.packs().get(i);
            var absent = lists.absents().get(i);
            int size = base.size();
            views.add(new View("list " + size, base, packed, absent));
            for (int[] range : new int[][] {{0, 2}, {1, 1}, {1, size}, {0, size}}) {
                String where = "list " + size + " subList" + Arrays.toString(range);
                views.add(new View(
                        where,
                        base.subList(range[0], range[1]),
                        () -> packed.get().subList(range[0], range[1]),
                        absent));
                views.add(new View(
                        where + " subList[0, 0]",
                        base.subList(range[0], range[1]).subList(0, 0),
                        () -> packed.get().subList(range[0], range[1]).subList(0, 0),
                        absent));
            }
            views.add(new View(
                    "list " + size + " reversed", base.reversed(), () -> packed.get().reversed(), absent));
        }
        return views;
    }

    private static Lists lists() {
        var random = new Random(17);
        var bases = new ArrayList<List<EntityMetadata.Entry>>();
        var packs = new ArrayList<Supplier<List<EntityMetadata.Entry>>>();
        var absents = new ArrayList<EntityMetadata.Entry>();
        for (int size : new int[] {3, 4, 9, 80}) {
            int[] indices = new int[size], offsets = new int[size], lengths = new int[size];
            Object[] values = new Object[size];
            for (int i = 0; i < size; i++) {
                indices[i] = i % 3 == 2 ? indices[i - 1] : index(random);
                values[i] = i % 4 == 0 ? null : (byte) random.nextInt();
                offsets[i] = 2 * i + 1;
                lengths[i] = 2;
            }
            byte[] payload = new byte[2 * size + 3];
            random.nextBytes(payload);
            var plain = new EntityMetadata.Entry[size];
            for (int i = 0; i < size; i++)
                plain[i] = EntityMetadata.Entry.of(indices[i], values[i], payload, offsets[i], lengths[i]);
            List<EntityMetadata.Entry> base = List.of(plain);
            var absent = EntityMetadata.Entry.of(300, null, payload, 0, 1);
            bases.add(base);
            packs.add(() -> assertInstanceOf(
                    EntityMetadata.Entries.class,
                    EntityMetadata.entries(size, indices, values, offsets, lengths, payload, 0)));
            absents.add(absent);
        }
        return new Lists(bases, packs, absents);
    }

    /** Runs every operation on {@code base} and on {@code packed}, each on a list of its own, and compares them. */
    private static int compareOperations(
            String where,
            List<EntityMetadata.Entry> base,
            List<EntityMetadata.Entry> packed,
            EntityMetadata.Entry absent) {
        var operations = operations(base, absent);
        for (var operation : operations.entrySet())
            assertSameOutcome(
                    Outcome.of(() -> operation.getValue().apply(base)),
                    Outcome.of(() -> operation.getValue().apply(packed)),
                    where + ": " + operation.getKey());
        assertEquals(base, packed, where);
        return operations.size();
    }

    private static Map<String, Function<List<EntityMetadata.Entry>, Object>> operations(
            List<EntityMetadata.Entry> base, EntityMetadata.Entry absent) {
        int size = base.size();
        var any = size == 0 ? absent : base.get(0);
        List<EntityMetadata.Entry> copy = new ArrayList<>(base);
        Map<String, Function<List<EntityMetadata.Entry>, Object>> operations = new LinkedHashMap<>();
        operations.put("add", l -> l.add(any));
        operations.put("add(0)", l -> run(() -> l.add(0, any)));
        operations.put("add(-1)", l -> run(() -> l.add(-1, any)));
        operations.put("addFirst", l -> run(() -> l.addFirst(any)));
        operations.put("addLast", l -> run(() -> l.addLast(any)));
        operations.put("addAll empty", l -> l.addAll(List.of()));
        operations.put("addAll", l -> l.addAll(List.of(any)));
        operations.put("addAll null", l -> l.addAll(null));
        operations.put("addAll(0) empty", l -> l.addAll(0, List.of()));
        operations.put("addAll(9) empty", l -> l.addAll(9, List.of()));
        operations.put("clear", l -> run(l::clear));
        operations.put("remove element", l -> l.remove(any));
        operations.put("remove absent", l -> l.remove(absent));
        operations.put("remove null", l -> l.remove((Object) null));
        operations.put("remove(0)", l -> l.remove(0));
        operations.put("remove(-1)", l -> l.remove(-1));
        operations.put("removeFirst", List::removeFirst);
        operations.put("removeLast", List::removeLast);
        operations.put("removeAll empty", l -> l.removeAll(List.of()));
        operations.put("removeAll absent", l -> l.removeAll(List.of(absent)));
        operations.put("removeAll all", l -> l.removeAll(copy));
        operations.put("removeAll null", l -> l.removeAll(null));
        operations.put("retainAll empty", l -> l.retainAll(List.of()));
        operations.put("retainAll superset", l -> l.retainAll(copy));
        operations.put("retainAll null", l -> l.retainAll(null));
        operations.put("removeIf none", l -> l.removeIf(entry -> false));
        operations.put("removeIf all", l -> l.removeIf(entry -> true));
        operations.put("removeIf null", l -> l.removeIf(null));
        operations.put("replaceAll identity", l -> run(() -> l.replaceAll(entry -> entry)));
        operations.put("replaceAll null", l -> run(() -> l.replaceAll(null)));
        operations.put("set(0)", l -> l.set(0, any));
        operations.put("set(-1)", l -> l.set(-1, any));
        operations.put("sort null", l -> run(() -> l.sort(null)));
        operations.put("sort id", l -> run(() -> l.sort(Comparator.comparingInt(EntityMetadata.Entry::id))));
        operations.put("size", List::size);
        operations.put("isEmpty", List::isEmpty);
        operations.put("get(0)", l -> l.get(0));
        operations.put("get(last)", l -> l.get(size - 1));
        operations.put("getFirst", List::getFirst);
        operations.put("getLast", List::getLast);
        for (Object probe : new Object[] {any, absent, null, "text"}) {
            String name = probe == any ? "element" : probe == absent ? "absent" : String.valueOf(probe);
            operations.put("indexOf " + name, l -> l.indexOf(probe));
            operations.put("lastIndexOf " + name, l -> l.lastIndexOf(probe));
            operations.put("contains " + name, l -> l.contains(probe));
        }
        operations.put("containsAll all", l -> l.containsAll(copy));
        operations.put("containsAll absent", l -> l.containsAll(List.of(absent)));
        operations.put("containsAll with null", l -> l.containsAll(Arrays.asList(any, null)));
        operations.put("containsAll null", l -> l.containsAll(null));
        operations.put("equals ListOf", l -> l.equals(List.copyOf(copy)));
        operations.put("equals ArrayList", l -> l.equals(copy));
        operations.put("ArrayList equals", copy::equals);
        operations.put("equals shorter", l -> l.equals(copy.subList(0, Math.max(0, size - 1))));
        operations.put("equals null", l -> l.equals(null));
        operations.put("equals text", l -> l.equals("text"));
        operations.put("hashCode", List::hashCode);
        operations.put("toString", List::toString);
        operations.put("toArray", l -> describe(l.toArray()));
        operations.put("toArray empty", l -> describe(l.toArray(new EntityMetadata.Entry[0])));
        operations.put("toArray Object", l -> describe(l.toArray(new Object[0])));
        operations.put("toArray larger", l -> {
            var into = new EntityMetadata.Entry[size + 2];
            Arrays.fill(into, absent);
            return describe(l.toArray(into)) + " same " + (l.toArray(into) == into);
        });
        operations.put("toArray exact", l -> {
            var into = new EntityMetadata.Entry[size];
            return describe(l.toArray(into)) + " same " + (l.toArray(into) == into);
        });
        operations.put("toArray String", l -> describe(l.toArray(new String[0])));
        operations.put("toArray String larger", l -> describe(l.toArray(new String[size + 1])));
        operations.put("toArray null", l -> describe(l.toArray((Object[]) null)));
        operations.put("toArray generator", l -> describe(l.toArray(EntityMetadata.Entry[]::new)));
        operations.put("toArray null generator", l -> describe(l.toArray((IntFunction<Object[]>) null)));
        operations.put("forEach", l -> {
            var seen = new ArrayList<EntityMetadata.Entry>();
            l.forEach(seen::add);
            return seen;
        });
        operations.put("forEach null", l -> run(() -> l.forEach(null)));
        operations.put("stream", l -> l.stream().toList());
        operations.put("spliterator", l -> {
            var spliterator = l.spliterator();
            var seen = new ArrayList<EntityMetadata.Entry>();
            var prefix = spliterator.trySplit();
            if (prefix != null) prefix.forEachRemaining(seen::add);
            spliterator.forEachRemaining(seen::add);
            return spliterator.characteristics() + " " + spliterator.estimateSize() + " "
                    + spliterator.getExactSizeIfKnown() + " " + (prefix == null) + " " + seen;
        });
        operations.put("copyOf", l -> List.copyOf(l).equals(copy));
        operations.put("reversed", l -> l.reversed().toString());
        operations.put("reversed removeIf none", l -> l.reversed().removeIf(entry -> false));
        operations.put("reversed add", l -> l.reversed().add(any));
        operations.put("subList(-1, 1)", l -> l.subList(-1, 1));
        operations.put("subList(0, size + 1)", l -> l.subList(0, size + 1));
        operations.put("subList(1, 0)", l -> l.subList(1, 0));
        operations.put("subList(size, size)", l -> l.subList(size, size));
        operations.put("subList clear empty", l -> run(() -> l.subList(0, 0).clear()));
        operations.put("subList removeIf none", l -> l.subList(0, size).removeIf(entry -> false));
        operations.put("subList removeAll empty", l -> l.subList(0, size).removeAll(List.of()));
        operations.put("subList retainAll superset", l -> l.subList(0, size).retainAll(copy));
        operations.put("subList get(-1)", l -> l.subList(0, size).get(-1));
        operations.put("listIterator(-1)", l -> l.listIterator(-1));
        operations.put("listIterator(size + 1)", l -> l.listIterator(size + 1));
        for (int start : new int[] {0, size}) {
            for (String step : new String[] {"", "next", "previous", "next next", "next previous"})
                for (String act : new String[] {
                    "remove", "set", "add", "next", "previous", "hasNext", "hasPrevious", "nextIndex", "previousIndex"
                }) {
                    String name = "listIterator(" + start + ") " + step + " then " + act;
                    operations.put(name, l -> iterate(l.listIterator(start), step + " " + act));
                }
        }
        for (String step : new String[] {"", "next", "all"})
            for (String act : new String[] {
                "remove",
                "next",
                "hasNext",
                "set",
                "add",
                "previous",
                "hasPrevious",
                "nextIndex",
                "previousIndex",
                "forEachRemaining",
                "forEachRemainingNull"
            }) operations.put("iterator " + step + " then " + act, l -> iterate(l.iterator(), step + " " + act));
        return operations;
    }

    /** Takes each step of {@code script} on {@code iterator}, as far as a {@link ListIterator} cast allows. */
    @SuppressWarnings("unchecked")
    private static Object iterate(Iterator<EntityMetadata.Entry> iterator, String script) {
        var trace = new StringBuilder();
        for (String step : script.trim().split(" ")) {
            var list = (ListIterator<EntityMetadata.Entry>) iterator;
            trace.append(step).append('=');
            switch (step) {
                case "next" -> trace.append(iterator.next());
                case "previous" -> trace.append(list.previous());
                case "all" ->
                    iterator.forEachRemaining(entry -> trace.append(entry).append(';'));
                case "remove" -> iterator.remove();
                case "set" -> list.set(null);
                case "add" -> list.add(null);
                case "hasNext" -> trace.append(iterator.hasNext());
                case "hasPrevious" -> trace.append(list.hasPrevious());
                case "nextIndex" -> trace.append(list.nextIndex());
                case "previousIndex" -> trace.append(list.previousIndex());
                case "forEachRemaining" ->
                    iterator.forEachRemaining(entry -> trace.append(entry).append(';'));
                case "forEachRemainingNull" -> iterator.forEachRemaining(null);
                default -> throw new AssertionError(step);
            }
            trace.append(' ');
        }
        return trace;
    }

    private static Object run(Runnable operation) {
        operation.run();
        return "done";
    }

    private static String describe(Object[] array) {
        return array.getClass().getName() + " " + Arrays.toString(array);
    }

    /**
     * The set_entity_data codecs of two catalogs reading for one connection decode each packet as a reader built for
     * that packet alone, while the codec and the connection's registry names change and its reads alternate between
     * contexts with and without world registries.
     */
    @Test
    void theConnectionsHeldReaderDecodesAsAReaderBuiltForEachPacket() {
        OfflineCultTestBootstrap.installConfig();
        var version = ClientPacketCodecs.MODEL_VERSION;
        var serializers = Serializers.probe(version, registries(version));
        var world = new ClientWorldRegistries(ClientWorldRegistries.modelDefaults());
        RegistryNames[] snapshots = {snapshot(false), snapshot(true)};
        RegistryNames[] current = {snapshots[0]};
        List<Function<ProtocolContext, ClientWorldRegistries>> worlds = List.of(
                context -> context.variant() == 1 ? world : null, context -> context.variant() == 0 ? world : null);
        List<PacketType<?>> types = worlds.stream()
                .<PacketType<?>>map(lookup -> ClientPacketCodecs.catalog(context -> current[0], lookup).stream()
                        .filter(candidate -> candidate.wireNames().contains("minecraft:set_entity_data"))
                        .findFirst()
                        .orElseThrow())
                .toList();
        var data = ProtocolData.load(version);
        var channel = new EmbeddedChannel();
        var connection = new CultConnection(
                channel,
                new PacketDispatcher(TestProtocolRuntime.create(data)),
                ignored -> new PacketOwner(channel.eventLoop(), null));
        var contexts = new ProtocolContext[2];
        for (int variant = 0; variant < 2; variant++)
            contexts[variant] = new ProtocolContext(
                    types.getFirst(), data, ConnectionPhase.PLAY, "minecraft:set_entity_data", 0, variant, connection);
        var random = new Random(23);
        int built = 0, resolved = 0, unresolved = 0, failed = 0;
        ClientPacketCodecs.MetadataReader last = null;
        try {
            for (int packet = 0; packet < PACKETS; packet++) {
                if (packet % 50 == 0) current[0] = snapshots[packet / 50 % 2];
                var context = contexts[random.nextInt(2)];
                var snapshot = current[0];
                int codec = packet % 50 < 25 ? 0 : 1;
                var lookup = worlds.get(codec);
                byte[] wire = packet(random, serializers);
                var expected = decode(
                        wire,
                        false,
                        (input, lease) -> new ModelEntityMetadataValues(
                                        ProtocolCodecs.decoder(), version, names(snapshot), name -> {
                                            var registries = lookup.apply(context);
                                            return registries == null ? null : registries.painting(name);
                                        })
                                .readOnce(input, lease));
                var held = decode(wire, false, (input, lease) ->
                        (EntityMetadata) types.get(codec).codec().read(input, context));
                assertEquals(expected, held, "packet " + packet);
                if (connection.metadataReader() != last) {
                    built++;
                    last = connection.metadataReader();
                }
                if (expected.startsWith("throws")) failed++;
                else if (expected.startsWith("null")) unresolved++;
                else if (expected.contains("PaintingSize")) resolved++;
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        assertEquals(PACKETS / 25, built, "one reader per change of codec or registry names");
        assertTrue(
                resolved > 50 && unresolved > 50 && failed > 100,
                resolved + " resolved, " + unresolved + " unresolved, " + failed + " failed");
    }

    /** The model registry names, with a painting variant registry of vanilla and unknown names in either order. */
    private static RegistryNames snapshot(boolean reversed) {
        var names = new HashMap<String, List<String>>();
        ModelRegistryNamesState.defaults()
                .registries()
                .forEach((registry, table) -> names.put(registry, table.names()));
        var paintings = new ArrayList<>(List.of(
                "minecraft:kebab",
                "minecraft:aztec",
                "minecraft:alban",
                "minecraft:aztec2",
                "minecraft:bomb",
                "minecraft:plant",
                "minecraft:wasteland",
                "minecraft:pool",
                "minecraft:courbet",
                "minecraft:sea",
                "minecraft:sunset",
                "minecraft:creebet",
                "minecraft:wanderer",
                "minecraft:graham",
                "minecraft:match",
                "minecraft:bust",
                "minecraft:stage",
                "minecraft:void",
                "minecraft:skull_and_roses",
                "minecraft:wither",
                "minecraft:fighters",
                "minecraft:pointer",
                "minecraft:pigscene",
                "minecraft:burning_skull",
                "minecraft:skeleton",
                "minecraft:donkey_kong"));
        for (int i = paintings.size(); i < 40; i++) paintings.add("minecraft:unknown_" + i);
        if (reversed) Collections.reverse(paintings);
        names.put("minecraft:painting_variant", paintings);
        return new RegistryNames(names);
    }

    private static WireValueDecoder.Registries names(RegistryNames snapshot) {
        return new WireValueDecoder.Registries() {
            @Override
            public String name(String registry, int id) {
                return snapshot.name(registry, id);
            }

            @Override
            public int id(String registry, String name) {
                return snapshot.id(registry, name);
            }
        };
    }

    private interface Reader {
        EntityMetadata read(ByteBuf input, FrameLease lease);
    }

    /** The outcome of decoding {@code wire}, described while any lease is still live. */
    private String decode(byte[] wire, boolean lent, Reader reader) {
        ByteBuf frame = PooledByteBufAllocator.DEFAULT.directBuffer(wire.length + 2);
        frame.writeBytes(new byte[] {9, 9}).writeBytes(wire).readerIndex(2);
        var lender = new FrameLease.Lender();
        var lease = lender.open(frame);
        try {
            EntityMetadata metadata = reader.read(frame, lent ? lender.lease(frame) : null);
            int end = frame.readerIndex();
            String described = describe(metadata);
            frame.setZero(0, frame.capacity());
            lender.close(lease);
            lender.end(lease);
            frame.release();
            frame = null;
            if (metadata != null && !lent)
                assertEquals(described, describe(metadata), "copied bytes outlive the frame");
            return described + " until " + end;
        } catch (RuntimeException failure) {
            return "throws " + failure.getClass().getName() + ": " + failure.getMessage();
        } finally {
            if (frame != null) frame.release();
        }
    }

    private String describe(EntityMetadata metadata) {
        if (metadata == null) return "null";
        var text = new StringBuilder("id ").append(metadata.id()).append(' ');
        List<EntityMetadata.Entry> items = metadata.packedItems();
        if (items instanceof EntityMetadata.Entries) packedSeen++;
        text.append(items.size());
        boolean comparable = true;
        for (var entry : items) {
            text.append(" [").append(entry.id()).append(' ').append(value(entry.value()));
            text.append(' ')
                    .append(ByteBufUtil.hexDump(Unpooled.wrappedBuffer(entry.bytes())))
                    .append(']');
            comparable &= !(entry.value() instanceof SimItemStack);
        }
        if (comparable) {
            var copy = new EntityMetadata(metadata.id(), new ArrayList<>(items));
            assertEquals(copy, metadata);
            assertEquals(metadata, copy);
            assertEquals(copy.hashCode(), metadata.hashCode());
            assertEquals(copy.toString(), metadata.toString());
        }
        for (int index = -1; index < 256; index++) {
            var entry = WatchableIndexUtil.getIndex(items, index);
            Object expected = null;
            for (var candidate : items)
                if (candidate.id() == index) {
                    expected = candidate;
                    break;
                }
            assertSame(expected, entry);
            assertEquals(expected != null, WatchableIndexUtil.has(items, index));
            assertSame(entry == null ? null : entry.value(), WatchableIndexUtil.value(items, index));
        }
        return text.toString();
    }

    /** Values whose class keeps identity equality are compared by their fields. */
    private static String value(Object value) {
        return dump(value, 0, new IdentityHashMap<>());
    }

    private static String dump(Object value, int depth, Map<Object, Boolean> seen) {
        if (value == null) return "null";
        Class<?> type = value.getClass();
        if (type.isRecord()
                || type.isEnum()
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof CharSequence
                || value instanceof Optional<?>
                || value instanceof OptionalInt) return type.getSimpleName() + ":" + value;
        if (depth > 4) return type.getName();
        if (value instanceof Collection<?> collection) {
            if (collection.size() > 32) return type.getName() + " size " + collection.size();
            var text = new StringBuilder("[");
            for (Object element : collection)
                text.append(dump(element, depth + 1, seen)).append(',');
            return text.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 32) return type.getName() + " size " + map.size();
            var text = new StringBuilder("{");
            map.forEach((k, v) -> text.append(dump(k, depth + 1, seen))
                    .append('=')
                    .append(dump(v, depth + 1, seen))
                    .append(','));
            return text.append('}').toString();
        }
        if (type.isArray()) return type.getComponentType() + "[]";
        if (seen.put(value, true) != null || type.getName().startsWith("java.")) return type.getName();
        var text = new StringBuilder(type.getSimpleName()).append('{');
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    text.append(field.getName())
                            .append('=')
                            .append(dump(field.get(value), depth + 1, seen))
                            .append(',');
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    text.append(field.getName()).append("=?,");
                }
            }
        return text.append('}').toString();
    }

    /** The base revision's decode: one entry object per wire value, in wire order. */
    @SuppressWarnings("unchecked")
    private static EntityMetadata legacy(
            ProtocolVersion version, WireValueDecoder.Registries registries, ByteBuf input, FrameLease lease) {
        var decoder = ProtocolCodecs.decoder();
        var items = new ModelItemValues(decoder, version, registries, ITEMS);
        int id = Wire.readVarInt(input);
        int payloadStart = input.readerIndex();
        var decoded = decoder.metadata(version, input, registries, ModelNbtFactory.INSTANCE);
        int payloadLength = decoded.isEmpty()
                ? 0
                : decoded.getLast().offset() + decoded.getLast().length() - payloadStart;
        byte[] payload = lease == null ? ByteBufUtil.getBytes(input, payloadStart, payloadLength) : null;
        var entries = new EntityMetadata.Entry[decoded.size()];
        for (int i = 0; i < entries.length; i++) {
            var decodedValue = decoded.get(i);
            Object value = decodedValue.value();
            String kind = decodedValue.kind();
            Object converted = switch (kind) {
                case "item" -> items.item((WireValueDecoder.DecodedItem<NbtValue>) value);
                case "painting_variant" ->
                    value instanceof int[] dimensions
                            ? new PaintingSize(dimensions[0], dimensions[1])
                            : PAINTINGS.apply((String) value);
                case "pose" -> EntityPose.byId((int) value);
                case "direction" -> Direction.from3DDataValue((int) value);
                case "optional_position" ->
                    value == null
                            ? Optional.empty()
                            : Optional.of(new BlockPos(((int[]) value)[0], ((int[]) value)[1], ((int[]) value)[2]));
                case "optional_int" -> value == null ? OptionalInt.empty() : OptionalInt.of((int) value);
                default -> value;
            };
            if (converted == null && kind.equals("painting_variant")) return null;
            int offset = decodedValue.offset(), length = decodedValue.length();
            entries[i] = lease == null
                    ? EntityMetadata.Entry.of(decodedValue.index(), converted, payload, offset - payloadStart, length)
                    : EntityMetadata.Entry.of(decodedValue.index(), converted, lease, offset, length);
        }
        return new EntityMetadata(id, List.of(entries));
    }

    private static int index(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> 63 + random.nextInt(3);
            case 1 -> random.nextInt(255);
            default -> random.nextInt(24);
        };
    }

    /** Serializer IDs of {@code version} found by decoding probes, so each typed value is framed correctly. */
    private record Serializers(int pose, int optionalInt, int painting) {
        static Serializers probe(ProtocolVersion version, WireValueDecoder.Registries registries) {
            return new Serializers(
                    probe(version, registries, "pose", new byte[] {0}),
                    probe(version, registries, "optional_int", new byte[] {0}),
                    probe(version, registries, "painting_variant", new byte[] {0, 1, 1, 0, 0, 0}));
        }

        private static int probe(
                ProtocolVersion version, WireValueDecoder.Registries registries, String kind, byte[] payload) {
            for (int serializer = 0; serializer < 64; serializer++) {
                ByteBuf input = Unpooled.buffer();
                input.writeByte(0);
                Wire.writeVarInt(input, serializer);
                input.writeBytes(payload).writeByte(255);
                try {
                    var decoded = ProtocolCodecs.decoder().metadata(version, input, registries);
                    if (decoded.size() == 1 && decoded.getFirst().kind().equals(kind) && !input.isReadable())
                        return serializer;
                } catch (RuntimeException ignored) {
                    // Another serializer's framing.
                }
            }
            throw new AssertionError("No " + kind + " serializer in " + version);
        }
    }

    private static byte[] packet(Random random, Serializers serializers) {
        ByteBuf output = Unpooled.buffer();
        Wire.writeVarInt(output, random.nextInt(100_000));
        int entries = switch (random.nextInt(10)) {
            case 0 -> 0;
            case 1 -> 60 + random.nextInt(20);
            default -> 1 + random.nextInt(7);
        };
        for (int i = 0; i < entries; i++) {
            output.writeByte(index(random));
            value(random, output, serializers);
        }
        if (random.nextInt(12) != 0) output.writeByte(255);
        byte[] wire = ByteBufUtil.getBytes(output);
        output.release();
        if (random.nextInt(15) == 0 && wire.length > 1) wire = Arrays.copyOf(wire, random.nextInt(wire.length));
        return wire;
    }

    private static void value(Random random, ByteBuf output, Serializers serializers) {
        switch (random.nextInt(17)) {
            case 0 -> {
                Wire.writeVarInt(output, 0);
                output.writeByte(random.nextInt());
            }
            case 1 -> {
                Wire.writeVarInt(output, 1);
                Wire.writeVarInt(output, random.nextInt(1_000_000));
            }
            case 2 -> {
                Wire.writeVarInt(output, 2);
                Wire.writeVarLong(output, random.nextLong());
            }
            case 3 -> {
                Wire.writeVarInt(output, 3);
                output.writeFloat(random.nextFloat() * 40 - 10);
            }
            case 4 -> {
                Wire.writeVarInt(output, 4);
                byte[] text = ("s" + random.nextInt(1000)).getBytes(StandardCharsets.UTF_8);
                Wire.writeVarInt(output, text.length);
                output.writeBytes(text);
            }
            case 5 -> {
                Wire.writeVarInt(output, 8);
                output.writeBoolean(random.nextBoolean());
            }
            case 6 -> {
                Wire.writeVarInt(output, 12);
                Wire.writeVarInt(output, random.nextInt(7));
            }
            case 7 -> {
                Wire.writeVarInt(output, 11);
                output.writeBoolean(random.nextBoolean());
                if (output.getBoolean(output.writerIndex() - 1)) output.writeLong(random.nextLong());
            }
            case 8 -> {
                Wire.writeVarInt(output, 10);
                output.writeLong(random.nextLong());
            }
            case 9 -> {
                Wire.writeVarInt(output, serializers.pose());
                Wire.writeVarInt(output, random.nextInt(EntityPose.values().length + 2));
            }
            case 10 -> {
                Wire.writeVarInt(output, serializers.optionalInt());
                Wire.writeVarInt(output, random.nextInt(50));
            }
            case 11 -> {
                Wire.writeVarInt(output, serializers.painting());
                if (random.nextBoolean()) Wire.writeVarInt(output, 1 + random.nextInt(30));
                else output.writeBytes(new byte[] {0, (byte) (1 + random.nextInt(4)), 1, 0, 0, 0});
            }
            case 12 -> {
                Wire.writeVarInt(output, 7);
                if (random.nextBoolean()) Wire.writeVarInt(output, 0);
                else {
                    Wire.writeVarInt(output, 1 + random.nextInt(64));
                    Wire.writeVarInt(output, random.nextInt(1500));
                    Wire.writeVarInt(output, 0);
                    Wire.writeVarInt(output, 0);
                }
            }
            case 13 -> {
                Wire.writeVarInt(output, 9);
                output.writeFloat(random.nextFloat());
                output.writeFloat(random.nextFloat());
                output.writeFloat(random.nextFloat());
            }
            case 14 -> {
                Wire.writeVarInt(output, random.nextInt(70));
                byte[] garbage = new byte[random.nextInt(6)];
                random.nextBytes(garbage);
                output.writeBytes(garbage);
            }
            case 15 -> Wire.writeVarInt(output, 1000 + random.nextInt(1000));
            default -> {
                Wire.writeVarInt(output, 0);
                output.writeByte(random.nextInt(256));
            }
        }
    }
}
