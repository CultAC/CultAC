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
    }

    WireValueDecoder decoder() {
        return decoder;
    }

    WireRegistryState names() {
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
            case "minecraft:update_tags" -> RegistryTagValues.readProjected(input, version, names);
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
                        packet.entityId(), type, packet.position(), packet.yaw(), packet.pitch(), data, packet.uuid());
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
                    "minecraft:level_chunk_with_light",
                    "minecraft:light_update" -> world.read(input, context.wireName());
            default -> inventory.read(input, context.wireName());
        };
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, Object packet) {
        if (packet instanceof WorldPackets.BlockUpdate block) {
            output.writeLong(block.position().asLong());
            Wire.writeVarInt(output, toWire.blockState(block.state()));
        } else if (packet instanceof EntityMetadata metadata) {
            Wire.writeVarInt(output, metadata.id());
            metadata.packedItems().forEach(entry -> output.writeBytes(entry.bytes()));
            output.writeByte(255);
        } else throw new UnsupportedOnVersionException("No older wire writer for " + context.type());
    }

    private RegistryData registry(ByteBuf input) {
        var packet = decoder.registry(input);
        names.append(packet);
        for (var entry : packet.entries()) {
            if (!packet.registry().equals("minecraft:dimension_type")
                    && names.modelId(packet.registry(), entry.name()) < 0)
                names.unavailable(packet.registry(), entry.name());
        }
        return new RegistryData(packet.registry(), packet.entries());
    }
}
