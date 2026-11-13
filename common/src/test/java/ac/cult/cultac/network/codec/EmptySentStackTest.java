package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.utils.inventory.ItemUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Empty sent stacks equal the decoded empty stack and leave the input and the held stacks as decoding does. */
class EmptySentStackTest {
    private static final List<byte[]> INPUTS = List.of(
            new byte[] {0},
            new byte[] {0, 0},
            new byte[] {0, 1, 5, 0},
            new byte[] {(byte) 0x80, 0},
            new byte[] {(byte) 0x80, (byte) 0x80, 0, 7});

    /** The stack the decoder alone yields, with no held stack offered. */
    private static final class Decoded implements WireValueDecoder.ItemReuse<NbtValue, SimItemStack> {
        private final ModelItemValues values;

        Decoded(ModelItemValues values) {
            this.values = values;
        }

        @Override
        public SimItemStack reuse(ByteBuf input) {
            return null;
        }

        @Override
        public SimItemStack decoded(WireValueDecoder.DecodedItem<NbtValue> value, ByteBuf input, int start, int end) {
            return values.item(value);
        }
    }

    private static ByteBuf input(byte[] bytes, int offset) {
        var input = Unpooled.buffer();
        input.writeZero(offset);
        input.writeBytes(bytes);
        input.readerIndex(offset);
        return input;
    }

    @Test
    void emptyStacksMatchTheDecoderInEverySchema() {
        for (var version : ProtocolVersion.values()) {
            var names = new WireRegistryState(version, () -> null);
            var plain = new ModelItemValues(ProtocolCodecs.decoder(), version, names, ItemUtil.modelItems());
            var cache = new ItemStackCache();
            var values = new ModelItemValues(
                    ProtocolCodecs.decoder(), version, names, ItemUtil.modelItems(), cache, names::generation);
            for (byte[] bytes : INPUTS)
                for (int offset : new int[] {0, 3}) {
                    String label = version + "/" + bytes.length + "/" + offset;
                    var expectedInput = input(bytes, offset);
                    var expected = ProtocolCodecs.decoder()
                            .item(version, expectedInput, names, ModelNbtFactory.INSTANCE, new Decoded(plain));
                    var sentInput = input(bytes, offset);
                    var sent = values.sentItem(sentInput);
                    var laterInput = input(bytes, offset);
                    Supplier<SimItemStack> later = values.sentLater(laterInput);
                    assertSame(expected, sent, label);
                    assertSame(SimItemStack.empty(), sent, label);
                    assertEquals(expectedInput.readerIndex(), sentInput.readerIndex(), label);
                    assertEquals(expectedInput.readerIndex(), laterInput.readerIndex(), label);
                    assertInstanceOf(ModelItemValues.Pending.class, later, label);
                    assertSame(expected, later.get(), label);
                    assertSame(later.get(), later.get(), label);
                    assertEquals(0, cache.held(), label);
                }
        }
    }
}
