package ac.cult.cultac.network.codec;

import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.TagNetworkSerialization.NetworkPayload;

/** Preserves native tag wire order before Via's valid rename collisions are evaluated. */
public final class NativeTagOrder {
    private static final Constructor<NetworkPayload> CONSTRUCTOR = constructor();
    private static final Field TAGS = tagsField();
    private static final StreamCodec<ByteBuf, Object> IDENTIFIER = identifierCodec();

    private NativeTagOrder() {}

    static StreamCodec<ByteBuf, NetworkPayload> preserve(StreamCodec<ByteBuf, NetworkPayload> original) {
        return StreamCodec.of(original::encode, input -> {
            int start = input.readerIndex();
            // The original host codec remains responsible for framing, limits and
            // malformed input. Only the order of its successfully decoded map changes.
            original.decode(input);
            var orderedInput = input.slice(start, input.readerIndex() - start);
            int count = Wire.readVarInt(orderedInput);
            var ordered = new LinkedHashMap<Object, IntList>();
            for (int index = 0; index < count; index++) {
                Object name = IDENTIFIER.decode(orderedInput);
                var ids = new IntArrayList();
                int members = Wire.readVarInt(orderedInput);
                for (int member = 0; member < members; member++) ids.add(Wire.readVarInt(orderedInput));
                // Native maps keep the last value. Keep that occurrence's wire
                // position as well, so two names renamed to one retain the last value.
                ordered.remove(name);
                ordered.put(name, ids);
            }
            if (orderedInput.isReadable()) throw new IllegalStateException("Native tag framing changed");
            try {
                return CONSTRUCTOR.newInstance(ordered);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native ordered tags cannot be constructed", failure);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static Map<Object, IntList> entries(NetworkPayload payload) {
        try {
            return (Map<Object, IntList>) TAGS.get(payload);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native tag entries are unavailable", failure);
        }
    }

    private static Constructor<NetworkPayload> constructor() {
        try {
            var constructor = NetworkPayload.class.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Field tagsField() {
        try {
            var field = NetworkPayload.class.getDeclaredField("tags");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static StreamCodec<ByteBuf, Object> identifierCodec() {
        try {
            Class<?> identifier;
            try {
                identifier = Class.forName("net.minecraft.resources.Identifier");
            } catch (ClassNotFoundException earlier) {
                identifier = Class.forName("net.minecraft.resources.ResourceLocation");
            }
            return (StreamCodec<ByteBuf, Object>)
                    identifier.getField("STREAM_CODEC").get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
