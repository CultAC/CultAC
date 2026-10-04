package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import io.netty.buffer.*;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization.NetworkPayload;

/** Ordered name projection prevents both ID guessing and duplicate tag rename policies. */
final class ObservedTags {
    private static final Set<String> STATIC =
            Set.of("minecraft:block", "minecraft:item", "minecraft:entity_type", "minecraft:fluid");

    static RegistryTags read(ByteBuf input, ProtocolVersion version, WireRegistryState names) {
        int count = Wire.readLength(input, input.readableBytes());
        var registries = new LinkedHashMap<ResourceKey<? extends Registry<?>>, NetworkPayload>();
        for (int index = 0; index < count; index++) {
            String registry = Wire.readIdentifier(input);
            boolean available = names.hasModelRegistry(registry);
            int size = Wire.readLength(input, input.readableBytes());
            var tags = new LinkedHashMap<String, List<String>>();
            for (int tag = 0; tag < size; tag++) {
                String key = Wire.readIdentifier(input);
                int members = Wire.readLength(input, input.readableBytes());
                var entries = new ArrayList<String>(members);
                for (int member = 0; member < members; member++) {
                    int id = Wire.readVarInt(input);
                    if (available) entries.add(names.name(registry, id));
                }
                tags.remove(key);
                tags.put(key, List.copyOf(entries));
            }
            if (!available) {
                names.unavailable(registry, "<registry>");
                continue;
            }
            if (STATIC.contains(registry))
                tags = new LinkedHashMap<>(
                        ProtocolCodecs.projectTags(registry, tags, version, version, ProtocolVersion.V26_3));
            ByteBuf output = Unpooled.buffer();
            try {
                Wire.writeVarInt(output, tags.size());
                for (var entry : tags.entrySet()) {
                    Wire.writeString(output, entry.getKey(), 32767);
                    var ids = new ArrayList<Integer>();
                    for (String member : entry.getValue()) {
                        int id = registry.equals("minecraft:dimension_type")
                                ? names.id(registry, member)
                                : names.modelId(registry, member);
                        if (id >= 0) ids.add(id);
                        else names.unavailable(registry, member);
                    }
                    Wire.writeVarInt(output, ids.size());
                    ids.forEach(id -> Wire.writeVarInt(output, id));
                }
                registries.put(NmsIdentifierUtil.registryKey(registry), NativeValueCodecs.TAGS.decode(output));
            } finally {
                output.release();
            }
        }
        return new RegistryTags(registries);
    }
}
