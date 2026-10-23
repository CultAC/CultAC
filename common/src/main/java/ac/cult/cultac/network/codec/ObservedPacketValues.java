package ac.cult.cultac.network.codec;

import ac.cult.blocksim.entity.PaintingSize;
import ac.cult.cultac.network.packet.*;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.codec.entity.AddEntityCodec;
import ac.cult.cultac.protocol.codec.world.BlockEventCodec;
import ac.cult.cultac.protocol.data.*;
import ac.cult.cultac.protocol.packet.clientbound.*;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Older wire values enter the model as records, never as translated packet frames. */
public final class ObservedPacketValues implements PacketValueAdapter {
    private final WireValueDecoder decoder;
    private final WireRegistryState names;
    private final ProtocolVersion version;
    private final ModelIdMappings toModel, toWire;
    private final ModelInventoryValues inventory;
    private final ObservedWorldValues world;
    private final ModelEntityMetadataValues metadata;
    private final Map<String, RegistryTags.Payload> configurationTags = new LinkedHashMap<>();

    public ObservedPacketValues(
            WireValueDecoder decoder,
            ProtocolVersion version,
            Supplier<RegistryNames> modelNames,
            Function<String, PaintingSize> paintings,
            Supplier<int[]> dimension) {
        this.decoder = decoder;
        this.version = version;
        names = new WireRegistryState(version, modelNames);
        toModel = ModelIdMappings.project(version, ProtocolVersion.V26_3);
        toWire = ModelIdMappings.project(ProtocolVersion.V26_3, version);
        inventory = new ModelInventoryValues(decoder, version, names);
        world = new ObservedWorldValues(this, dimension);
        metadata = new ModelEntityMetadataValues(decoder, version, names, paintings);
    }

    public void beginConfiguration() {
        names.beginConfiguration();
        configurationTags.clear();
    }

    /**
     * RegistryDataCollector binds configuration tags only at finish, against the completed contents; the packet
     * value carries no memberships and {@link #bindConfigurationTags} returns them.
     */
    private RegistryTags collectTags(ByteBuf input) {
        RegistryTagValues.read(input).tags().forEach((registry, payload) -> {
            configurationTags.remove(registry);
            configurationTags.put(registry, payload);
        });
        return new RegistryTags(Map.of());
    }

    /**
     * This configuration's tags in model IDs, bound against the generation being staged; null when none arrived.
     * RegistryDataCollector's contents branch ignores a dynamic registry's whole-empty payload before any lookup.
     */
    public RegistryTags bindConfigurationTags(boolean contentsReceived) {
        if (configurationTags.isEmpty()) return null;
        var supplied = new LinkedHashMap<String, RegistryTags.Payload>();
        configurationTags.forEach((registry, payload) -> {
            if (!contentsReceived || !payload.isEmpty() || RegistryTagValues.STATIC.contains(registry))
                supplied.put(registry, payload);
        });
        return RegistryTagValues.project(new RegistryTags(supplied), version, names);
    }

    public void clearConfigurationTags() {
        configurationTags.clear();
    }

    WireRegistryState names() {
        return names;
    }

    /** Read-only registry lookup for native client observations using this wire schema. */
    public WireValueDecoder.Registries sourceRegistries() {
        return names;
    }

    ProtocolVersion version() {
        return version;
    }

    ModelIdMappings mappings() {
        return toModel;
    }

    @Override
    public Object read(ByteBuf input, ProtocolContext context) {
        return switch (context.wireName()) {
            case "minecraft:registry_data" -> registry(input);
            case "minecraft:update_tags" ->
                context.phase() == ConnectionPhase.CONFIGURATION
                        ? collectTags(input)
                        : RegistryTagValues.readProjected(input, version, names);
            case "minecraft:set_time" -> ClientMetadataValues.time(input, version, names::name);
            case "minecraft:update_recipes" -> ClientMetadataValues.recipes(input, names::name);
            case "minecraft:set_entity_data" -> metadata.read(input);
            case "minecraft:add_entity" -> {
                var packet = new AddEntityCodec().read(input, context);
                var sourceTypes = ModelRegistryData.load(version).registry("minecraft:entity_type");
                String type = ModelRegistryData.load(ProtocolVersion.V26_3)
                        .registry("minecraft:entity_type")
                        .name(toModel.entity(sourceTypes.id(packet.entityType())));
                int data = packet.entityType().equals("minecraft:falling_block")
                        ? toModel.blockState(packet.data())
                        : packet.data();
                yield new ClientboundAddEntity(
                        packet.entityId(),
                        type,
                        packet.position(),
                        packet.yaw(),
                        packet.pitch(),
                        data,
                        packet.uuid(),
                        packet.velocity());
            }
            case "minecraft:block_event" -> {
                var packet = new BlockEventCodec().read(input, context);
                yield new ClientboundBlockEvent(
                        packet.position(), packet.action(), packet.parameter(), toModel.block(packet.blockId()));
            }
            case "minecraft:block_update",
                    "minecraft:block_entity_data",
                    "minecraft:chunks_biomes",
                    "minecraft:section_blocks_update",
                    "minecraft:level_chunk_with_light" -> world.read(input, context.wireName());
            default -> inventory.read(input, context.wireName());
        };
    }

    @Override
    public Object readForObservation(ByteBuf input, ProtocolContext context) {
        if (context.wireName().equals("minecraft:registry_data")) {
            var packet = decoder.registry(input);
            return new RegistryData(packet.registry(), packet.entries());
        }
        if (context.wireName().equals("minecraft:update_tags") && context.phase() == ConnectionPhase.CONFIGURATION) {
            RegistryTagValues.read(input);
            return new RegistryTags(Map.of());
        }
        return read(input, context);
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, Object packet) {
        if (packet instanceof WorldPackets.BlockUpdate block) {
            output.writeLong(block.position().asLong());
            Wire.writeVarInt(output, toWire.blockState(block.state()));
        } else if (packet instanceof InventoryPackets.Slot slot) {
            inventory.writeSlot(output, slot);
        } else if (packet instanceof InventoryPackets.Cursor cursor) {
            inventory.writeCursor(output, cursor);
        } else if (packet instanceof EntityMetadata metadata) {
            Wire.writeVarInt(output, metadata.id());
            metadata.packedItems().forEach(entry -> output.writeBytes(entry.bytes()));
            output.writeByte(255);
        } else throw new UnsupportedOnVersionException("No older wire writer for " + context.type());
    }

    private RegistryData registry(ByteBuf input) {
        var packet = decoder.registry(input);
        names.append(packet);
        return new RegistryData(packet.registry(), packet.entries());
    }
}
