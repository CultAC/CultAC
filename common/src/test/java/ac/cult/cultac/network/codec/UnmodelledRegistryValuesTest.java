package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class UnmodelledRegistryValuesTest {
    private static final ProtocolVersion BACKEND = ProtocolVersion.V1_21_11;
    private static final ProtocolRuntime RUNTIME = TestProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));

    @Test
    void cosmeticEntriesAbsentFromTheModelDecodeByReceivedNameWithoutLogging() {
        var logged = new ArrayList<LogRecord>();
        var logger = Logger.getLogger("ac.cult.cultac.network.codec");
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        var level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            var world = new ClientWorldRegistries(ClientWorldRegistries.modelDefaults());
            var values = new ObservedPacketValues(
                    ProtocolCodecs.decoder(), BACKEND, ModelRegistryNamesState::defaults, world::painting, () ->
                            new int[] {-64, 384});
            values.beginConfiguration();
            read(
                    values,
                    ConnectionPhase.CONFIGURATION,
                    "registry_data",
                    input -> registry(input, "minecraft:trim_material", "minecraft:diamond", "example:glow"));
            read(
                    values,
                    ConnectionPhase.CONFIGURATION,
                    "registry_data",
                    input -> registry(input, "minecraft:trim_pattern", "minecraft:sentry", "example:custom_trim"));
            read(
                    values,
                    ConnectionPhase.CONFIGURATION,
                    "registry_data",
                    input -> registry(input, "minecraft:chat_type", "minecraft:chat", "example:raw"));

            var names = values.names();
            assertEquals("example:custom_trim", names.name("minecraft:trim_pattern", 1));
            assertEquals(-1, names.modelId("minecraft:trim_pattern", "example:custom_trim"));
            assertEquals(-1, names.modelId("minecraft:trim_material", "example:glow"));
            assertEquals(-1, names.modelId("minecraft:chat_type", "example:raw"));

            var tags = (RegistryTags) read(values, ConnectionPhase.PLAY, "update_tags", input -> {
                Wire.writeVarInt(input, 2);
                Wire.writeString(input, "minecraft:trim_pattern", Wire.MAX_STRING_LENGTH);
                Wire.writeVarInt(input, 1);
                Wire.writeString(input, "example:patterns", Wire.MAX_STRING_LENGTH);
                Wire.writeVarInt(input, 2);
                Wire.writeVarInt(input, 0);
                Wire.writeVarInt(input, 1);
                Wire.writeString(input, "example:registry", Wire.MAX_STRING_LENGTH);
                Wire.writeVarInt(input, 0);
            });
            assertFalse(tags.tags().containsKey("example:registry"));
            assertEquals(
                    List.of(ModelRegistryNamesState.defaults().id("minecraft:trim_pattern", "minecraft:sentry")),
                    tags.tags().get("minecraft:trim_pattern").entries().get("example:patterns"));

            var source = ModelRegistryData.load(BACKEND);
            var slot = (InventoryPackets.Slot) read(values, ConnectionPhase.PLAY, "container_set_slot", input -> {
                Wire.writeVarInt(input, 0);
                Wire.writeVarInt(input, 3);
                input.writeShort(6);
                Wire.writeVarInt(input, 1);
                Wire.writeVarInt(input, source.registry("minecraft:item").id("minecraft:diamond_chestplate"));
                Wire.writeVarInt(input, 1);
                Wire.writeVarInt(input, 0);
                Wire.writeVarInt(
                        input, source.registry("minecraft:data_component_type").id("minecraft:trim"));
                Wire.writeVarInt(input, 2); // example:glow
                Wire.writeVarInt(input, 2); // example:custom_trim
            });
            var item = slot.item();
            assertEquals("minecraft:diamond_chestplate", item.itemKey());
            assertEquals(1, item.count());
            var trim = item.components().get("minecraft:trim").getAsJsonObject();
            assertEquals("example:glow", trim.get("material").getAsString());
            assertEquals("example:custom_trim", trim.get("pattern").getAsString());

            assertEquals(List.of(), logged.stream().map(LogRecord::getMessage).toList());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }

    private static Object read(
            ObservedPacketValues values, ConnectionPhase phase, String packet, Consumer<ByteBuf> body) {
        var input = Unpooled.buffer();
        try {
            body.accept(input);
            var context = new ProtocolContext(
                    RUNTIME.typeForKey("clientbound." + packet),
                    ProtocolData.load(BACKEND),
                    phase,
                    "minecraft:" + packet,
                    32767,
                    0,
                    CodecState.EMPTY);
            var result = values.read(input, context);
            assertFalse(input.isReadable(), packet);
            return result;
        } finally {
            input.release();
        }
    }

    /** A known-pack entry, then a custom entry carrying its own definition. */
    private static void registry(ByteBuf input, String registry, String known, String custom) {
        Wire.writeString(input, registry, 32767);
        Wire.writeVarInt(input, 2);
        Wire.writeString(input, known, 32767);
        input.writeBoolean(false);
        Wire.writeString(input, custom, 32767);
        input.writeBoolean(true);
        input.writeBytes(BinaryNbt.write(new NbtValue.Compound(Map.of("asset_id", new NbtValue.Text(custom)))));
    }
}
