package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockEntityNbtTest {
    @Test
    void discardedBlockEntityDataKeepsTheFollowingPacketAligned() {
        var unused = new NbtValue.Compound(Map.of(
                "CustomName",
                new NbtValue.Text("unused".repeat(1000)),
                "components",
                new NbtValue.Compound(Map.of(
                        "arbitrary",
                        new NbtValue.Sequence(
                                List.of(new NbtValue.Text("text"), new NbtValue.Numeric(NbtValue.Kind.INT, 7)))))));
        for (String type :
                List.of("minecraft:beacon", "minecraft:brushable_block", "minecraft:campfire", "minecraft:jigsaw")) {
            var input = Unpooled.buffer();
            try {
                input.writeBytes(BinaryNbt.write(unused));
                input.writeInt(123456);
                assertTrue(NbtValueCodec.readBlockEntity(input, type).values().isEmpty());
                assertEquals(123456, input.readInt());
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }
    }

    @Test
    void retainedPredictionFieldsSurviveUnknownNestedFieldsAndNullableTags() {
        var expected = new NbtValue.Numeric(NbtValue.Kind.INT, 12);
        var input = Unpooled.buffer();
        try {
            input.writeBytes(BinaryNbt.write(new NbtValue.Compound(Map.of(
                    "OutputSignal",
                    expected,
                    "components",
                    new NbtValue.Compound(Map.of("unused", new NbtValue.Text("discard")))))));
            input.writeByte(0);
            input.writeInt(987654);
            assertEquals(
                    Map.of("OutputSignal", expected),
                    NbtValueCodec.readBlockEntity(input, "minecraft:comparator").values());
            assertNull(NbtValueCodec.readBlockEntity(input, "minecraft:beacon"));
            assertEquals(987654, input.readInt());
        } finally {
            input.release();
        }
    }

    @Test
    void containerPacketsRetainStackLimitsWithoutDecodingBookAndCosmeticPayloads() {
        var item = new NbtValue.Compound(Map.of(
                "id",
                new NbtValue.Text("minecraft:stone"),
                "count",
                new NbtValue.Numeric(NbtValue.Kind.INT, 4),
                "Slot",
                new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 3),
                "components",
                new NbtValue.Compound(Map.of(
                        "max_stack_size", new NbtValue.Numeric(NbtValue.Kind.INT, 8),
                        "minecraft:written_book_content",
                                new NbtValue.Compound(Map.of(
                                        "pages",
                                        new NbtValue.Sequence(List.of(new NbtValue.Text("unused text".repeat(2000)))))),
                        "minecraft:custom_data",
                                new NbtValue.Compound(Map.of("unused", new NbtValue.Text("discard")))))));
        var input = Unpooled.buffer();
        try {
            // A heterogeneous list also contains a scalar that the client container ignores.
            input.writeBytes(BinaryNbt.write(new NbtValue.Compound(Map.of(
                    "Items", new NbtValue.Sequence(List.of(item, new NbtValue.Numeric(NbtValue.Kind.INT, 7)))))));
            input.writeInt(123456);
            var tag = NbtValueCodec.readBlockEntity(input, "minecraft:chest");
            var stack = (NbtValue.Compound)
                    ((NbtValue.Sequence) tag.values().get("Items")).values().getFirst();
            assertEquals(item.values().get("id"), stack.values().get("id"));
            assertEquals(item.values().get("count"), stack.values().get("count"));
            assertEquals(item.values().get("Slot"), stack.values().get("Slot"));
            assertEquals(
                    Map.of("minecraft:max_stack_size", new NbtValue.Numeric(NbtValue.Kind.INT, 8)),
                    ((NbtValue.Compound) stack.values().get("components")).values());
            assertEquals(123456, input.readInt());
            assertFalse(input.isReadable());

            input.clear();
            input.writeBytes(BinaryNbt.write(new NbtValue.Compound(Map.of("item", item))));
            var pot = NbtValueCodec.readBlockEntity(input, "minecraft:decorated_pot");
            assertEquals(stack, pot.values().get("item"));
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }

    @Test
    void lecternPacketsRetainPageCountWithoutBookText() {
        var pages = new NbtValue.Sequence(List.of(
                new NbtValue.Text("unused text".repeat(2000)),
                new NbtValue.Compound(
                        Map.of("text", new NbtValue.Text("discard"), "click_event", new NbtValue.Text("discard")))));
        var item = new NbtValue.Compound(Map.of(
                "id", new NbtValue.Text("minecraft:written_book"),
                "count", new NbtValue.Numeric(NbtValue.Kind.INT, 1),
                "components",
                        new NbtValue.Compound(Map.of(
                                "written_book_content",
                                        new NbtValue.Compound(Map.of(
                                                "pages",
                                                pages,
                                                "title",
                                                new NbtValue.Text("unused"),
                                                "author",
                                                new NbtValue.Text("unused"))),
                                "custom_data",
                                        new NbtValue.Compound(Map.of("unused", new NbtValue.Text("discard")))))));
        var input = Unpooled.buffer();
        try {
            input.writeBytes(BinaryNbt.write(
                    new NbtValue.Compound(Map.of("Book", item, "Page", new NbtValue.Numeric(NbtValue.Kind.INT, 1)))));
            input.writeInt(123456);
            var tag = NbtValueCodec.readBlockEntity(input, "minecraft:lectern");
            var book = (NbtValue.Compound) tag.values().get("Book");
            var components = (NbtValue.Compound) book.values().get("components");
            assertEquals(
                    java.util.Set.of("minecraft:written_book_content"),
                    components.values().keySet());
            var content = (NbtValue.Compound) components.values().get("minecraft:written_book_content");
            assertEquals(java.util.Set.of("pages"), content.values().keySet());
            var retained = (NbtValue.Sequence) content.values().get("pages");
            assertEquals(pages.values().size(), retained.values().size());
            assertTrue(retained.values().stream()
                    .allMatch(value -> value instanceof NbtValue.Compound empty
                            && empty.values().isEmpty()));
            assertEquals(123456, input.readInt());
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
    }
}
