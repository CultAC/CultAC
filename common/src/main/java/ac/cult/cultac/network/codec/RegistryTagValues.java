/*
 * Framing adapted from PacketEvents WrapperPlayServerTags
 * at 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022-2026 retrooper and contributors.
 * Licensed under GPL-3.0-or-later; see https://www.gnu.org/licenses/.
 */
package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Registry identifiers and varint tag members; no native objects or shared tag rebinding. */
public final class RegistryTagValues {
    private static final java.util.Set<String> STATIC =
            java.util.Set.of("minecraft:block", "minecraft:item", "minecraft:entity_type", "minecraft:fluid");

    private RegistryTagValues() {}

    public static RegistryTags read(ByteBuf input) {
        int count = Wire.readLength(input, input.readableBytes());
        var registries = new LinkedHashMap<String, RegistryTags.Payload>();
        for (int index = 0; index < count; index++) {
            String name = Wire.readIdentifier(input);
            registries.remove(name);
            registries.put(name, readPayload(input));
        }
        return new RegistryTags(registries);
    }

    public static RegistryTags.Payload readPayload(ByteBuf input) {
        int count = Wire.readLength(input, input.readableBytes());
        var tags = new LinkedHashMap<String, java.util.List<Integer>>();
        for (int index = 0; index < count; index++) {
            String name = Wire.readIdentifier(input);
            int size = Wire.readLength(input, input.readableBytes());
            var members = new ArrayList<Integer>(size);
            for (int member = 0; member < size; member++) members.add(Wire.readVarInt(input));
            tags.remove(name);
            tags.put(name, members);
        }
        return new RegistryTags.Payload(tags);
    }

    /** Project received member IDs through their source names using the same raw tag reader. */
    static RegistryTags readProjected(ByteBuf input, ProtocolVersion version, WireRegistryState names) {
        int count = Wire.readLength(input, input.readableBytes());
        var registries = new LinkedHashMap<String, RegistryTags.Payload>();
        for (int index = 0; index < count; index++) {
            String registry = Wire.readIdentifier(input);
            var payload = readPayload(input);
            if (!names.hasModelRegistry(registry)) {
                names.unavailable(registry, "<registry>");
                continue;
            }
            var tags = new LinkedHashMap<String, java.util.List<String>>();
            int registrySize = names.size(registry);
            for (var entry : payload.entries().entrySet())
                tags.put(
                        entry.getKey(),
                        entry.getValue().stream()
                                // Vanilla TagNetworkSerialization resolves members with Registry.get
                                // and flatMap(Optional::stream), retaining tags but omitting unknown IDs.
                                .filter(id -> id >= 0 && id < registrySize)
                                .map(id -> names.name(registry, id))
                                .toList());
            if (STATIC.contains(registry))
                tags = new LinkedHashMap<>(
                        ProtocolCodecs.projectTags(registry, tags, version, version, ProtocolVersion.V26_3));
            var projected = new LinkedHashMap<String, java.util.List<Integer>>();
            for (var entry : tags.entrySet()) {
                var ids = new ArrayList<Integer>();
                for (String member : entry.getValue()) {
                    int id = registry.equals("minecraft:dimension_type")
                            ? names.id(registry, member)
                            : names.modelId(registry, member);
                    if (id >= 0) ids.add(id);
                    else names.unavailable(registry, member);
                }
                projected.put(entry.getKey(), ids);
            }
            registries.put(registry, new RegistryTags.Payload(projected));
        }
        return new RegistryTags(registries);
    }

    public static void writePayload(ByteBuf output, RegistryTags.Payload payload) {
        Wire.writeVarInt(output, payload.entries().size());
        payload.entries().forEach((name, members) -> {
            Wire.writeString(output, name, Wire.MAX_STRING_LENGTH);
            Wire.writeVarInt(output, members.size());
            members.forEach(id -> Wire.writeVarInt(output, id));
        });
    }
}
