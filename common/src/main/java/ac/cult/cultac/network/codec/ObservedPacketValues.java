package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.*;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.codec.entity.AddEntityCodec;
import ac.cult.cultac.protocol.codec.world.BlockEventCodec;
import ac.cult.cultac.protocol.data.*;
import ac.cult.cultac.protocol.packet.clientbound.*;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.minecraft.*;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Older wire values enter the model as records, never as translated packet frames. */
public final class ObservedPacketValues implements PacketValueAdapter {
    private final WireValueDecoder decoder;
    private final WireRegistryState names;
    private final MinecraftRegistries registries;
    private final ProtocolVersion version;
    private final ModelIdMappings toModel, toWire;
    private final ObservedInventoryValues inventory;
    private final ObservedWorldValues world;

    public ObservedPacketValues(
            WireValueDecoder decoder,
            ProtocolVersion version,
            MinecraftRegistries registries,
            java.util.function.Supplier<int[]> dimension) {
        this.decoder = decoder;
        this.version = version;
        this.registries = registries;
        names = new WireRegistryState(version, registries);
        toModel = ModelIdMappings.project(version, ProtocolVersion.V26_3);
        toWire = ModelIdMappings.project(ProtocolVersion.V26_3, version);
        inventory = new ObservedInventoryValues(this);
        world = new ObservedWorldValues(this, dimension);
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
            case "minecraft:update_tags" -> ObservedTags.read(input, version, names);
            case "minecraft:set_entity_data" -> metadata(input);
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
                        packet.entityId(), type, packet.position(), packet.yaw(), packet.pitch(), data);
            }
            case "minecraft:block_event" -> {
                var packet = new BlockEventCodec().read(input, context);
                yield new ClientboundBlockEvent(
                        packet.position(), packet.action(), packet.parameter(), toModel.block(packet.blockId()));
            }
            case "minecraft:block_update",
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
            Wire.writeVarInt(output, toWire.blockState(Block.getId(block.state())));
        } else if (packet instanceof EntityMetadata metadata) {
            Wire.writeVarInt(output, metadata.id());
            metadata.packedItems().forEach(entry -> output.writeBytes(entry.bytes()));
            output.writeByte(255);
        } else throw new UnsupportedOnVersionException("No older wire writer for " + context.type());
    }

    ItemStack item(ByteBuf input, boolean creative) {
        return item(decoder.item(version, input, creative, names));
    }

    ItemStack item(WireValueDecoder.ItemValue value) {
        if (value.count() <= 0) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.byId(toModel.item(value.id()));
        if (value.components() == null) return new ItemStack(item, value.count());
        var patch = ObservedComponents.decode(registries, (CompoundTag) nbt(value.components()));
        return new ItemStack(item.builtInRegistryHolder(), value.count(), patch);
    }

    private EntityMetadata metadata(ByteBuf input) {
        int id = Wire.readVarInt(input);
        var entries = new ArrayList<EntityMetadata.Entry>();
        for (var value : decoder.metadata(version, input, names)) {
            Object converted = switch (value.kind()) {
                case "item" -> item((WireValueDecoder.ItemValue) value.value());
                case "pose" -> Pose.values()[(int) value.value()];
                case "direction" -> net.minecraft.core.Direction.from3DDataValue((int) value.value());
                case "optional_position" ->
                    value.value() == null ? Optional.empty() : Optional.of(position((int[]) value.value()));
                case "optional_int" ->
                    value.value() == null ? OptionalInt.empty() : OptionalInt.of((int) value.value());
                default -> value.value();
            };
            entries.add(new EntityMetadata.Entry(value.index(), converted, ByteBuffer.wrap(value.bytes())));
        }
        return new EntityMetadata(id, entries);
    }

    private static BlockPos position(int[] values) {
        return new BlockPos(values[0], values[1], values[2]);
    }

    private RegistryData registry(ByteBuf input) {
        var packet = decoder.registry(input);
        names.append(packet);
        var key = NmsIdentifierUtil.registryKey(packet.registry());
        var values = new ArrayList<RegistrySynchronization.PackedRegistryEntry>();
        for (var entry : packet.entries()) {
            if (packet.registry().equals("minecraft:dimension_type")) {
                Optional<Tag> data = Optional.empty();
                if (entry.data() != null) {
                    var json = NbtOps.INSTANCE
                            .convertTo(JsonOps.INSTANCE, nbt(entry.data()))
                            .toString();
                    var projected = ModelDimensions.project(entry.name(), json, version, ProtocolVersion.V26_3);
                    data = Optional.of(JsonOps.INSTANCE.convertTo(
                            NbtOps.INSTANCE, com.google.gson.JsonParser.parseString(projected)));
                }
                values.add(new RegistrySynchronization.PackedRegistryEntry(
                        NmsIdentifierUtil.resourceKey((net.minecraft.resources.ResourceKey) key, entry.name())
                                .identifier(),
                        data));
            } else if (names.modelId(packet.registry(), entry.name()) < 0)
                names.unavailable(packet.registry(), entry.name());
        }
        return new RegistryData(key, values);
    }

    static CompoundTag nbt(byte[] bytes) {
        var input = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return Objects.requireNonNull(input.readNbt());
        } finally {
            input.release();
        }
    }
}
