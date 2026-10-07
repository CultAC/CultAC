package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.codec.ObservedPacketValues;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import io.netty.buffer.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivedRegistryDataTest {
    private static void finish(ClientComponentRegistries state) {
        state.finishConfiguration(ProtocolVersion.V26_3, ProtocolVersion.V26_3, ProtocolVersion.V26_3);
    }

    @Test
    void configurationPublishesSplitRegistryPacketsAndRetainsContentsForTagsOnlyReloads() {
        var state = new ClientComponentRegistries();
        byte[] data = BinaryNbt.write(new NbtValue.Numeric(NbtValue.Kind.INT, 42));
        var custom = new WireValueDecoder.RegistryEntry("test:custom", data);
        data[0] = 0;
        state.appendConfigurationRegistryData(new RegistryData("test:registry", List.of(custom)));
        state.appendConfigurationRegistryData(new RegistryData(
                "test:registry", List.of(new WireValueDecoder.RegistryEntry("minecraft:known_pack", null))));
        state.appendConfigurationRegistryData(new RegistryData("test:empty", List.of()));
        assertTrue(state.receivedRegistries().isEmpty());
        finish(state);
        var snapshot = state.receivedRegistries();
        var entries = snapshot.get("test:registry");
        assertEquals(
                List.of("test:custom", "minecraft:known_pack"),
                entries.stream().map(WireValueDecoder.RegistryEntry::name).toList());
        assertEquals(
                new NbtValue.Numeric(NbtValue.Kind.INT, 42),
                BinaryNbt.read(entries.getFirst().data()));
        assertNull(entries.get(1).data());
        assertTrue(snapshot.get("test:empty").isEmpty());
        var copy = entries.getFirst().data();
        copy[0] = 0;
        assertEquals(3, entries.getFirst().data()[0]);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
        state.beginConfiguration();
        finish(state);
        assertSame(snapshot, state.receivedRegistries());
        state.beginConfiguration();
        state.appendConfigurationRegistryData(new RegistryData("test:replacement", List.of()));
        finish(state);
        assertEquals(Map.of("test:replacement", List.of()), state.receivedRegistries());
        assertEquals(2, snapshot.get("test:registry").size(), "An already-published generation remains immutable");
        var names = new ac.cult.cultac.network.codec.WireRegistryState(
                ProtocolVersion.V1_21_3, ac.cult.cultac.network.codec.ModelRegistryNamesState::defaults);
        names.append(new WireValueDecoder.RegistryValues("minecraft:enchantment", List.of(custom)));
        names.beginConfiguration();
        assertEquals(
                "test:custom", names.name("minecraft:enchantment", 0), "Tags-only reloads preserve received wire IDs");
        names.append(new WireValueDecoder.RegistryValues("minecraft:enchantment", List.of()));
        assertEquals(
                -1,
                names.id("minecraft:enchantment", "test:custom"),
                "New registry contents replace the previous dynamic ID snapshot");
        assertThrows(MalformedPacketException.class, () -> names.name("minecraft:enchantment", 0));
    }

    @Test
    void nativeAndOlderPacketPathsRetainCustomRawNbtAndKnownPackReferences() {
        var world = new ac.cult.cultac.utils.latency.ClientWorldRegistries(
                ac.cult.cultac.utils.latency.ClientWorldRegistries.modelDefaults());
        var runtime = TestProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
        var type = runtime.typeForKey("clientbound.registry_data");
        byte[] raw = BinaryNbt.write(new NbtValue.Compound(Map.of(
                "opaque", new NbtValue.PrimitiveArray(NbtValue.Kind.LONG_ARRAY, List.of(Long.MIN_VALUE, 1L << 54)),
                "text", new NbtValue.Text("zero\u0000and\uD83D\uDE00"))));
        for (var version : ProtocolVersion.values()) {
            var input = Unpooled.buffer();
            try {
                Wire.writeString(input, "minecraft:enchantment", 32767);
                Wire.writeVarInt(input, 2);
                Wire.writeString(input, "test:custom", 32767);
                input.writeBoolean(true).writeBytes(raw);
                Wire.writeString(input, "minecraft:efficiency", 32767);
                input.writeBoolean(false);
                var codecContext = new ProtocolContext(
                        type,
                        ProtocolData.load(version),
                        ConnectionPhase.CONFIGURATION,
                        "minecraft:registry_data",
                        32767,
                        0,
                        CodecState.EMPTY);
                RegistryData packet = version == ProtocolVersion.V26_3
                        ? (RegistryData) type.codec().read(input, codecContext)
                        : (RegistryData) new ObservedPacketValues(
                                        ProtocolCodecs.decoder(),
                                        version,
                                        ac.cult.cultac.network.codec.ModelRegistryNamesState::defaults,
                                        world::painting,
                                        () -> new int[] {0, 256})
                                .read(input, codecContext);
                assertEquals("minecraft:enchantment", packet.registry());
                assertEquals(2, packet.entries().size());
                assertEquals("test:custom", packet.entries().getFirst().name());
                assertArrayEquals(raw, packet.entries().getFirst().data(), version.toString());
                assertNull(packet.entries().get(1).data());
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }
    }

    @Test
    void receivedEnchantmentEffectsReplaceHostEffectsAndInvalidateActionBindings() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.registryState = new ClientComponentRegistries();
            var helmet = OfflineCultTestBootstrap.item("minecraft:iron_helmet");
            helmet.setComponent(
                    "minecraft:enchantments",
                    ac.cult.blocksim.data.nbt.CanonicalSnbt.parse("{'minecraft:binding_curse':1}"));
            var storage = player.getInventory().inventory.getInventoryStorage();
            storage.setItem(Inventory.SLOT_HELMET, helmet);
            storage.setItem(Inventory.HOTBAR_OFFSET, OfflineCultTestBootstrap.item("minecraft:diamond_helmet"));
            ClientBlockActions.use(player, Hand.MAIN_HAND);
            assertSame(
                    ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:iron_helmet"),
                    storage.getItem(Inventory.SLOT_HELMET).getItem());
            var oldBindings = player.registryState.blockSimulatorRegistries(DataTables.defaults());
            var received = ac.cult.blocksim.data.InteractionRegistries.defaults()
                    .resolve("enchantment", new com.google.gson.JsonPrimitive("minecraft:binding_curse"))
                    .getAsJsonObject();
            received.remove("effects"); // An omitted effects field defaults to EMPTY.
            player.registryState.beginConfiguration();
            new ac.cult.cultac.events.packets.PacketServerRegistries()
                    .onRegistryData(
                            null,
                            player,
                            new RegistryData(
                                    "minecraft:enchantment",
                                    List.of(new WireValueDecoder.RegistryEntry(
                                            "minecraft:binding_curse",
                                            BinaryNbt.write(ac.cult.blocksim.data.nbt.NbtJson.literal(received))))));
            assertSame(oldBindings, player.registryState.blockSimulatorRegistries(DataTables.defaults()));
            finish(player.registryState);
            assertNotSame(oldBindings, player.registryState.blockSimulatorRegistries(DataTables.defaults()));
            ClientBlockActions.use(player, Hand.MAIN_HAND);
            assertSame(
                    ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:diamond_helmet"),
                    storage.getItem(Inventory.SLOT_HELMET).getItem());
            assertSame(
                    ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:iron_helmet"),
                    storage.getItem(Inventory.HOTBAR_OFFSET).getItem());
            assertTrue(
                    new ClientComponentRegistries()
                            .blockSimulatorRegistries(DataTables.defaults())
                            .interactions()
                            .resolve("enchantment", new com.google.gson.JsonPrimitive("minecraft:binding_curse"))
                            .getAsJsonObject()
                            .getAsJsonObject("effects")
                            .has("minecraft:prevent_armor_change"),
                    "The other connection retains its own binding");
        }
    }

    @Test
    void modelNbtPreservesNumericKindsArraysAndHeterogeneousListElements() throws java.io.IOException {
        var input = Unpooled.buffer();
        NbtValue.Compound parsed;
        try {
            input.writeByte(10);
            field(input, 9, "list");
            input.writeByte(10).writeInt(3);
            field(input, 3, "");
            input.writeInt(42).writeByte(0);
            field(input, 8, "");
            new ByteBufOutputStream(input).writeUTF("value");
            input.writeByte(0);
            field(input, 10, "");
            field(input, 2, "");
            input.writeShort(7).writeByte(0).writeByte(0);
            field(input, 7, "bytes");
            input.writeInt(3).writeBytes(new byte[] {-1, 0, 1});
            field(input, 11, "ints");
            input.writeInt(2).writeInt(Integer.MIN_VALUE).writeInt(Integer.MAX_VALUE);
            field(input, 12, "longs");
            input.writeInt(2).writeLong(Long.MIN_VALUE).writeLong(Long.MAX_VALUE);
            field(input, 5, "float");
            input.writeFloat(-.0F);
            field(input, 6, "double");
            input.writeDouble(-.0D);
            input.writeByte(0);
            parsed = (NbtValue.Compound) BinaryNbt.read(ByteBufUtil.getBytes(input));
        } finally {
            input.release();
        }
        var values = ((NbtValue.Sequence) parsed.values().get("list")).values();
        assertEquals(new NbtValue.Numeric(NbtValue.Kind.INT, 42), values.get(0));
        assertEquals(new NbtValue.Text("value"), values.get(1));
        assertEquals(
                new NbtValue.Compound(Map.of("", new NbtValue.Numeric(NbtValue.Kind.SHORT, (short) 7))), values.get(2));
        assertEquals(
                new NbtValue.PrimitiveArray(NbtValue.Kind.BYTE_ARRAY, List.of(-1L, 0L, 1L)),
                parsed.values().get("bytes"));
        assertEquals(
                new NbtValue.PrimitiveArray(
                        NbtValue.Kind.INT_ARRAY, List.of((long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE)),
                parsed.values().get("ints"));
        assertEquals(
                new NbtValue.PrimitiveArray(NbtValue.Kind.LONG_ARRAY, List.of(Long.MIN_VALUE, Long.MAX_VALUE)),
                parsed.values().get("longs"));
        assertEquals(
                new NbtValue.Numeric(NbtValue.Kind.FLOAT, .0F), parsed.values().get("float"));
        assertEquals(
                new NbtValue.Numeric(NbtValue.Kind.DOUBLE, .0D), parsed.values().get("double"));
    }

    @Test
    void receivedTransformerBooleanFieldsControlConsumptionWithoutHostRebinding() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -2;
            player.boundingBox = new ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox(
                    .2, 64, -2.3, .8, 65.8, -1.7, false);
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            var pos = new ac.cult.cultac.protocol.value.BlockPos(1, 63, 1);
            player.registryState = new ClientComponentRegistries();
            for (boolean consume : List.of(false, true)) {
                var rule = new NbtValue.Compound(Map.of(
                        "block_state_provider",
                                new NbtValue.Compound(Map.of("id", new NbtValue.Text("minecraft:stone"))),
                        "consume_on_use", new NbtValue.Numeric(NbtValue.Kind.INT, consume ? 256 : 0),
                        "update_from_neighbors", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 1)));
                var transforms = new NbtValue.Sequence(List.of(rule));
                player.registryState.beginConfiguration();
                player.registryState.appendConfigurationRegistryData(new RegistryData(
                        "minecraft:block_transformer",
                        List.of(new WireValueDecoder.RegistryEntry("minecraft:shovel", BinaryNbt.write(transforms)))));
                finish(player.registryState);
                player.compensatedWorld.updateBlock(
                        pos,
                        DataTables.defaults().registry().block("minecraft:dirt").defaultState());
                var ownedItem = OfflineCultTestBootstrap.item("minecraft:stick", 3);
                ownedItem.setComponent("minecraft:block_transformer", new NbtValue.Text("minecraft:shovel"));
                player.getInventory().inventory.setHeldItem(ownedItem);
                var place = new ac.cult.cultac.utils.anticheat.update.BlockPlace(
                        player, Hand.MAIN_HAND, pos, ac.cult.cultac.protocol.value.Direction.UP, ownedItem, null);
                place.setCursor(new ac.cult.cultac.utils.math.Vec3(.5, 1, .5));
                ClientBlockActions.useOn(player, place);
                assertEquals(
                        DataTables.defaults().registry().block("minecraft:stone"),
                        DataTables.defaults().registry().block(player.compensatedWorld.getBlockStateIdAt(pos)));
                assertEquals(
                        consume ? 2 : 3, player.getInventory().getHeldItem().getCount());
            }
        }
    }

    private static void field(ByteBuf output, int type, String name) throws java.io.IOException {
        output.writeByte(type);
        new ByteBufOutputStream(output).writeUTF(name);
    }
}
