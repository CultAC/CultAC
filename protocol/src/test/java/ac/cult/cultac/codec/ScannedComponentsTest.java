package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredData;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredDataContainer;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredDataKey;
import ac.cult.shaded.vialib.api.minecraft.item.Item;
import ac.cult.shaded.vialib.api.minecraft.item.StructuredItem;
import ac.cult.shaded.vialib.api.type.types.version.VersionedTypesHolder;
import ac.cult.shaded.vialib.nbt.tag.StringTag;
import ac.cult.shaded.vialib.nbt.tag.Tag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScannedComponentsTest {
    private static PrivateCodecService decoder;

    @BeforeAll
    static void open() {
        decoder = new PrivateCodecService();
    }

    @AfterAll
    static void close() {
        if (decoder != null) decoder.close();
    }

    @Test
    void scannedValuesAreServedAtTheirOwnOffsetsOnly() {
        for (var version : ProtocolVersion.values()) {
            var types = PrivateCodecService.types(version);
            for (byte[] stack : stacks(version)) {
                var scanned = ScannedComponents.of(types.item(), types.structuredData());
                assertNotNull(scanned, version.toString());
                ItemStructureGuard.scanItemStack(types, version, Unpooled.wrappedBuffer(stack), scanned);
                var input = Unpooled.wrappedBuffer(stack);
                Item served = scanned.item().read(input);
                assertFalse(input.isReadable(), version.toString());
                Item read = types.item().read(Unpooled.wrappedBuffer(stack));
                assertArrayEquals(written(types, read), written(types, served), version.toString());
            }
        }
    }

    @Test
    void handedOffDecodesMatchTheEagerDecodeOnEveryTruncationAndCorruption() {
        for (var version : ProtocolVersion.values()) {
            var names = names(version);
            for (byte[] stack : stacks(version)) {
                assertEquals(eager(version, stack, names), handedOff(version, stack, names), version.toString());
                for (int length = 0; length < stack.length; length++) {
                    byte[] cut = Arrays.copyOf(stack, length);
                    assertEquals(
                            eager(version, cut, names), handedOff(version, cut, names), version + " cut " + length);
                }
                for (int at = 0; at < stack.length; at++)
                    for (int flip : new int[] {0x00, 0x7F, 0x80, 0xFF}) {
                        byte[] bad = stack.clone();
                        bad[at] = (byte) (bad[at] == (byte) flip ? flip ^ 1 : flip);
                        assertEquals(
                                eager(version, bad, names),
                                handedOff(version, bad, names),
                                version + " byte " + at + " = " + flip);
                    }
            }
        }
    }

    /** The outcome of one decode: the decoded value with the bytes consumed, or the exception thrown. */
    private record Outcome(Object components, Object layouts, String rest) {}

    private static Outcome thrown(RuntimeException e) {
        return new Outcome(null, null, e.getClass().getName() + ": " + e.getMessage());
    }

    private static Outcome eager(ProtocolVersion version, byte[] stack, WireValueDecoder.Registries names) {
        var input = Unpooled.wrappedBuffer(stack);
        try {
            return outcome(decoder.item(version, input, false, names, PortableNbtTest.MODEL), input.readerIndex());
        } catch (RuntimeException e) {
            return thrown(e);
        }
    }

    private static Outcome handedOff(ProtocolVersion version, byte[] stack, WireValueDecoder.Registries names) {
        var input = Unpooled.wrappedBuffer(stack);
        try {
            var item = decoder.item(
                    version,
                    input,
                    names,
                    PortableNbtTest.MODEL,
                    new WireValueDecoder.ItemReuse<NbtValue, WireValueDecoder.DecodedItem<NbtValue>>() {
                        public WireValueDecoder.DecodedItem<NbtValue> reuse(ByteBuf in) {
                            return null;
                        }

                        public WireValueDecoder.DecodedItem<NbtValue> decoded(
                                WireValueDecoder.DecodedItem<NbtValue> value, ByteBuf in, int start, int end) {
                            assertEquals(in.readerIndex(), end);
                            return value;
                        }
                    });
            return outcome(item, input.readerIndex());
        } catch (RuntimeException e) {
            return thrown(e);
        }
    }

    private static Outcome outcome(WireValueDecoder.DecodedItem<NbtValue> item, int end) {
        var out = new StringBuilder()
                .append(item.id())
                .append('x')
                .append(item.count())
                .append(" @")
                .append(end);
        encodings(out, item.componentEncodings());
        if (item.wirePatch() != null) encoding(out.append(" patch="), item.wirePatch());
        return new Outcome(item.components(), item.componentLayouts(), out.toString());
    }

    private static void encodings(StringBuilder out, Map<String, WireValueDecoder.ComponentEncoding> encodings) {
        for (var entry : new TreeMap<>(encodings).entrySet())
            encoding(out.append(' ').append(entry.getKey()).append('='), entry.getValue());
    }

    private static void encoding(StringBuilder out, WireValueDecoder.ComponentEncoding encoding) {
        out.append(encoding.protocol())
                .append(':')
                .append(ByteBufUtil.hexDump(encoding.bytes()))
                .append(encoding.references());
        for (var item : new TreeMap<>(encoding.items()).entrySet()) {
            out.append(" [").append(item.getKey());
            encodings(out, item.getValue());
            out.append(']');
        }
    }

    /** Top-level leaves, removals, nested stacks and an empty stack in the schema's stack framing. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<byte[]> stacks(ProtocolVersion version) {
        var types = PrivateCodecService.types(version);
        var ids = ModelRegistryData.load(version).registry("minecraft:data_component_type");
        var names = names(version);
        int stone = names.id("minecraft:item", "minecraft:stone");
        var leaves = new ArrayList<StructuredData<?>>();
        for (String name :
                List.of("repair_cost", "custom_name", "lore", "enchantment_glint_override", "max_stack_size")) {
            int id = ids.id("minecraft:" + name);
            StructuredDataKey key = types.structuredData().key(id);
            Object value = switch (name) {
                case "repair_cost" -> 7;
                case "max_stack_size" -> 16;
                case "enchantment_glint_override" -> true;
                case "lore" -> new Tag[] {new StringTag("first"), new StringTag("second")};
                default -> new StringTag("name");
            };
            leaves.add(StructuredData.of(key, value, id));
        }
        int damage = ids.id("minecraft:damage");
        leaves.add(
                StructuredData.empty((StructuredDataKey) types.structuredData().key(damage), damage));
        var child = new StructuredItem(stone, 2, new StructuredDataContainer(leaves.toArray(StructuredData[]::new)));
        var mixed = new ArrayList<>(leaves);
        int container = ids.id("minecraft:container");
        mixed.add(
                1,
                StructuredData.of(
                        (StructuredDataKey) types.structuredData().key(container),
                        version.atLeast(ProtocolVersion.V26_1)
                                ? new Item[] {null, child}
                                : new Item[] {StructuredItem.empty(), child},
                        container));
        var out = new ArrayList<byte[]>();
        for (var patch : List.of(List.<StructuredData<?>>of(), leaves, mixed))
            out.add(written(
                    types,
                    new StructuredItem(stone, 3, new StructuredDataContainer(patch.toArray(StructuredData[]::new)))));
        out.add(written(types, StructuredItem.empty()));
        return out;
    }

    private static byte[] written(VersionedTypesHolder types, Item item) {
        var buffer = Unpooled.buffer();
        try {
            types.item().write(buffer, item);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static WireValueDecoder.Registries names(ProtocolVersion version) {
        var data = ModelRegistryData.load(version);
        return new WireValueDecoder.Registries() {
            public String name(String registry, int id) {
                return data.registry(registry).name(id);
            }

            public int id(String registry, String name) {
                return Objects.requireNonNull(data.registry(registry)).id(name);
            }
        };
    }
}
