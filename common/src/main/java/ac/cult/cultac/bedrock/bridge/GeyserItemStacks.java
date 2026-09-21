package ac.cult.cultac.bedrock.bridge;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.geysermc.geyser.session.GeyserSession;

import java.util.List;
import java.util.Map;

/** Converts item values by registry names; connection-specific wire IDs never enter NMS codecs. */
final class GeyserItemStacks {
    private GeyserItemStacks() { }

    static ItemStack toServerItem(GeyserSession session, org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack source) {
        if (source == null || source.getAmount() <= 0) return ItemStack.EMPTY;
        var mapping = session.getItemMappings().getMapping(source.getId()).getJavaItem();
        if (mapping.javaId() != source.getId()) throw new IllegalArgumentException("Unknown item " + source.getId());
        var item = NmsIdentifierUtil.registryOptional(BuiltInRegistries.ITEM, mapping.javaIdentifier()).orElseThrow();
        ItemStack result = new ItemStack(item, source.getAmount());
        if (source.getDataComponentsPatch() == null) return result;
        var registries = MinecraftServer.getServer().registryAccess();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        source.getDataComponentsPatch().getDataComponents().forEach((sourceType, component) -> {
            var targetType = component(sourceType);
            if (component.getValue() == null) {
                result.remove(targetType);
            } else if (targetType == DataComponents.CREATIVE_SLOT_LOCK) {
                result.set(DataComponents.CREATIVE_SLOT_LOCK, net.minecraft.util.Unit.INSTANCE);
            } else if (targetType == DataComponents.ADDITIONAL_TRADE_COST) {
                result.set(DataComponents.ADDITIONAL_TRADE_COST, (Integer) component.getValue());
            } else if (targetType == DataComponents.MAP_POST_PROCESSING) {
                result.set(DataComponents.MAP_POST_PROCESSING,
                        net.minecraft.world.item.component.MapPostProcessing.ID_MAP.apply((Integer) component.getValue()));
            } else {
                Object value = GeyserComponentValues.serialize(session.getRegistryCache(), component);
                set(result, targetType, ops, tag(value));
            }
        });
        return result;
    }

    private static <T> void set(ItemStack stack, DataComponentType<T> type,
                                com.mojang.serialization.DynamicOps<Tag> ops, Tag value) {
        var codec = type.codec();
        if (codec == null) throw new IllegalArgumentException("Component has no named codec: " + type);
        stack.set(type, codec.parse(ops, value).getOrThrow());
    }

    static Tag tag(Object value) {
        return switch (value) {
            case String v -> StringTag.valueOf(v);
            case Boolean v -> ByteTag.valueOf(v);
            case Byte v -> ByteTag.valueOf(v);
            case Short v -> ShortTag.valueOf(v);
            case Integer v -> IntTag.valueOf(v);
            case Long v -> LongTag.valueOf(v);
            case Float v -> FloatTag.valueOf(v);
            case Double v -> DoubleTag.valueOf(v);
            case byte[] v -> new ByteArrayTag(v.clone());
            case int[] v -> new IntArrayTag(v.clone());
            case long[] v -> new LongArrayTag(v.clone());
            case Map<?, ?> v -> {
                CompoundTag result = new CompoundTag();
                v.forEach((key, item) -> result.put((String) key, tag(item)));
                yield result;
            }
            case List<?> v -> {
                ListTag result = new ListTag();
                for (Object element : v) result.add(tag(element));
                yield result;
            }
            case null -> EndTag.INSTANCE;
            default -> throw new IllegalArgumentException("Unknown component value " + value.getClass());
        };
    }
    static net.minecraft.core.component.DataComponentType<?> component(org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType<?> type) {
        try {
            // Geyser-Spigot relocates Adventure; only the identifier string crosses that boundary.
            String key = type.getClass().getMethod("getKey").invoke(type).toString();
            var result = NmsIdentifierUtil.registryValue(BuiltInRegistries.DATA_COMPONENT_TYPE, key);
            if (result == null) throw new IllegalArgumentException("Unknown item component " + key);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read component identifier", failure);
        }
    }
}
