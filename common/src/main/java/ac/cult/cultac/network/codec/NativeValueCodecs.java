package ac.cult.cultac.network.codec;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization.NetworkPayload;

/** Native value API differences. Every selected implementation still uses this host's original codecs. */
final class NativeValueCodecs {
    static final StreamCodec<ByteBuf, ResourceKey<? extends Registry<?>>> KEY = key();
    static final StreamCodec<ByteBuf, NetworkPayload> TAGS = NativeTagOrder.preserve(tags());
    static final StreamCodec<ByteBuf, ? extends Enum<?>> CLICK_TYPE = clickType();
    private static final LightReader LIGHT = lightReader();

    private NativeValueCodecs() {}

    @SuppressWarnings("unchecked")
    private static StreamCodec<ByteBuf, ResourceKey<? extends Registry<?>>> key() {
        try {
            Class<?> identifier;
            try {
                identifier = Class.forName("net.minecraft.resources.Identifier");
            } catch (ClassNotFoundException earlier) {
                identifier = Class.forName("net.minecraft.resources.ResourceLocation");
            }
            var codec = (StreamCodec<ByteBuf, Object>)
                    identifier.getField("STREAM_CODEC").get(null);
            var create = ResourceKey.class.getMethod("createRegistryKey", identifier);
            java.lang.reflect.Method name;
            try {
                name = ResourceKey.class.getMethod("identifier");
            } catch (NoSuchMethodException earlier) {
                name = ResourceKey.class.getMethod("location");
            }
            var accessor = name;
            return codec.map(
                    value -> {
                        try {
                            return (ResourceKey<? extends Registry<?>>) create.invoke(null, value);
                        } catch (ReflectiveOperationException failure) {
                            throw failure(failure);
                        }
                    },
                    value -> {
                        try {
                            return accessor.invoke(value);
                        } catch (ReflectiveOperationException failure) {
                            throw failure(failure);
                        }
                    });
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static StreamCodec<ByteBuf, ? extends Enum<?>> clickType() {
        try {
            Class<?> type;
            try {
                type = Class.forName("net.minecraft.world.inventory.ContainerInput");
            } catch (ClassNotFoundException earlier) {
                type = Class.forName("net.minecraft.world.inventory.ClickType");
            }
            try {
                return (StreamCodec<ByteBuf, ? extends Enum<?>>)
                        type.getField("STREAM_CODEC").get(null);
            } catch (NoSuchFieldException earlier) {
                var values = type.getEnumConstants();
                return StreamCodec.of(
                        (buffer, value) -> ac.cult.cultac.protocol.wire.Wire.writeVarInt(buffer, value.ordinal()),
                        buffer -> (Enum<?>) values[ac.cult.cultac.protocol.wire.Wire.readVarInt(buffer)]);
            }
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static ClientboundLightUpdatePacketData light(FriendlyByteBuf input, int x, int z) {
        return LIGHT.read(input, x, z);
    }

    @SuppressWarnings("unchecked")
    private static LightReader lightReader() {
        try {
            try {
                var codec =
                        (StreamCodec<ByteBuf, ClientboundLightUpdatePacketData>) ClientboundLightUpdatePacketData.class
                                .getField("STREAM_CODEC")
                                .get(null);
                return (buffer, x, z) -> codec.decode(buffer);
            } catch (NoSuchFieldException earlier) {
                var constructor = ClientboundLightUpdatePacketData.class.getConstructor(
                        FriendlyByteBuf.class, int.class, int.class);
                return (buffer, x, z) -> {
                    try {
                        return constructor.newInstance(buffer, x, z);
                    } catch (ReflectiveOperationException failure) {
                        throw failure(failure);
                    }
                };
            }
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private interface LightReader {
        ClientboundLightUpdatePacketData read(FriendlyByteBuf input, int x, int z);
    }

    @SuppressWarnings("unchecked")
    private static StreamCodec<ByteBuf, NetworkPayload> tags() {
        try {
            try {
                return (StreamCodec<ByteBuf, NetworkPayload>)
                        NetworkPayload.class.getField("STREAM_CODEC").get(null);
            } catch (NoSuchFieldException earlier) {
                var read = NetworkPayload.class.getMethod("read", FriendlyByteBuf.class);
                var write = NetworkPayload.class.getMethod("write", FriendlyByteBuf.class);
                return StreamCodec.of(
                        (buffer, value) -> {
                            try {
                                write.invoke(value, new FriendlyByteBuf(buffer));
                            } catch (ReflectiveOperationException failure) {
                                throw failure(failure);
                            }
                        },
                        buffer -> {
                            try {
                                return (NetworkPayload) read.invoke(null, new FriendlyByteBuf(buffer));
                            } catch (ReflectiveOperationException failure) {
                                throw failure(failure);
                            }
                        });
            }
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static RuntimeException failure(ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new io.netty.handler.codec.DecoderException(cause);
    }
}
