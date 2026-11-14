package ac.cult.cultac.codec;

import ac.cult.shaded.vialib.api.minecraft.data.StructuredData;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredDataKey;
import ac.cult.shaded.vialib.api.minecraft.item.Item;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.api.type.types.item.ItemType1_20_5;
import ac.cult.shaded.vialib.api.type.types.item.StructuredDataTypeBase;
import io.netty.buffer.ByteBuf;

/**
 * Serves the top-level component values {@link ItemStructureGuard} read from one stack, each at the reader index the
 * guard read it from. The guard reads them through the same {@link Type} the decoder would, from the same bytes, so a
 * served value is the value the decoder would build; every other read goes to the schema's reader.
 */
final class ScannedComponents implements StructuredDataTypeBase {
    private final StructuredDataTypeBase reader;
    private int[] bounds;
    private StructuredData<?>[] values;
    private int size, next;

    ScannedComponents(StructuredDataTypeBase reader) {
        this.reader = reader;
    }

    /** An empty store for one stack, or null where the schema's stack reader is not the plain 1.20.5 one. */
    static ScannedComponents of(Type<Item> item, StructuredDataTypeBase reader) {
        return item.getClass() == ItemType1_20_5.class ? new ScannedComponents(reader) : null;
    }

    Type<Item> item() {
        return new ItemType1_20_5(this);
    }

    void expect(int count) {
        bounds = new int[count * 2];
        values = new StructuredData<?>[count];
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void add(int start, int end, StructuredDataKey key, Object value, int id) {
        bounds[size * 2] = start;
        bounds[size * 2 + 1] = end;
        values[size++] = StructuredData.of(key, value, id);
    }

    @Override
    public StructuredData<?> read(ByteBuf buffer) {
        if (next < size && buffer.readerIndex() == bounds[next * 2]) {
            buffer.readerIndex(bounds[next * 2 + 1]);
            return values[next++];
        }
        return reader.read(buffer);
    }

    @Override
    public void write(ByteBuf buffer, StructuredData<?> value) {
        reader.write(buffer, value);
    }

    @Override
    public StructuredDataKey<?> key(int id) {
        return reader.key(id);
    }
}
