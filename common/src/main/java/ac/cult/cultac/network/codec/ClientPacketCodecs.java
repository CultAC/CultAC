/*
 * Equipment framing adapted from PacketEvents WrapperPlayServerEntityEquipment:
 * https://github.com/retrooper/packetevents/tree/5da85d7ad888f69732e0726aad8dde8f0e0ef4c1
 * Copyright (C) 2022 retrooper and contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed without any warranty; see the GNU General Public
 * License for details. A copy is available at https://www.gnu.org/licenses/.
 * Adapted 2026-09-29 to the anticheat packet records and owned item value decoder.
 */
package ac.cult.cultac.network.codec;

import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketCatalog;
import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.protocol.packet.Packets;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Owned value codecs for the anticheat byte dispatch catalog.
 * Click and section-update framing follow PacketEvents' WrapperPlayClientClickWindow and
 * WrapperPlayServerMultiBlockChange (5da85d7ad888f69732e0726aad8dde8f0e0ef4c1),
 * independently verified against native codecs by the per-version conformance tests.
 */
public final class ClientPacketCodecs {
    private static final ProtocolVersion MODEL_VERSION = ProtocolVersion.V26_3;

    private ClientPacketCodecs() {}

    public static List<PacketType<?>> connectionCatalog() {
        return catalog(
                context -> context.state().require(RegistryNames.class),
                context -> context.state().require(ClientWorldRegistries.class));
    }

    public static List<PacketType<?>> catalog(
            Function<ProtocolContext, RegistryNames> names,
            Function<ProtocolContext, ClientWorldRegistries> worldRegistries) {
        PacketCatalog serverbound = PacketCatalog.serverbound();
        PacketCatalog clientbound = PacketCatalog.clientbound();
        PacketCatalog.Scope inventory = serverbound.in(ConnectionPhase.PLAY).since(MODEL_VERSION);
        PacketCatalog.Scope play = clientbound.in(ConnectionPhase.PLAY);
        PacketCatalog.Scope pinned = play.since(MODEL_VERSION);
        play.add(
                "change_difficulty",
                WorldPackets.Difficulty.class,
                (input, context) -> ClientMetadataValues.difficulty(input, context.version()));
        clientbound
                .in(ConnectionPhase.CONFIGURATION)
                .add(
                        "update_enabled_features",
                        WorldPackets.EnabledFeatures.class,
                        (input, context) -> ClientMetadataValues.features(input));
        pinned.add("set_entity_data", EntityMetadata.class, metadataCodec(names, worldRegistries));
        clientbound
                .in(ConnectionPhase.CONFIGURATION)
                .since(MODEL_VERSION)
                .add("registry_data", RegistryData.class, new PacketCodec<>() {
                    @Override
                    public boolean requiresModelValues() {
                        return true;
                    }

                    @Override
                    public RegistryData read(ByteBuf input, ProtocolContext context) {
                        requireModelWire(context);
                        var packet = ac.cult.cultac.protocol.wire.RegistryValueCodec.read(input);
                        return new RegistryData(packet.registry(), packet.entries());
                    }
                });
        clientbound
                .in(ConnectionPhase.CONFIGURATION, ConnectionPhase.PLAY)
                .since(MODEL_VERSION)
                .add("update_tags", ac.cult.cultac.network.packet.RegistryTags.class, new PacketCodec<>() {
                    @Override
                    public boolean requiresModelValues() {
                        return true;
                    }

                    @Override
                    public ac.cult.cultac.network.packet.RegistryTags read(ByteBuf input, ProtocolContext context) {
                        requireModelWire(context);
                        return RegistryTagValues.read(input);
                    }
                });
        inventory.add(
                "container_click", InventoryClick.class, inventoryReader(names, "minecraft:container_click", true));
        // The original frame is forwarded to the backend's creative item validator.
        inventory.add(
                "set_creative_mode_slot",
                InventoryPackets.CreativeSlot.class,
                inventoryReader(names, "minecraft:set_creative_mode_slot", true));
        pinned.add(
                "container_set_content",
                InventoryPackets.Content.class,
                inventoryReader(names, "minecraft:container_set_content", true));
        pinned.add("container_set_slot", InventoryPackets.Slot.class, inventorySlotCodec(names));
        pinned.add(
                "set_player_inventory",
                InventoryPackets.PlayerInventory.class,
                inventoryReader(names, "minecraft:set_player_inventory", true));
        pinned.add(
                "set_cursor_item",
                InventoryPackets.Cursor.class,
                inventoryReader(names, "minecraft:set_cursor_item", true));
        pinned.add(
                "set_equipment",
                InventoryPackets.Equipment.class,
                inventoryReader(names, "minecraft:set_equipment", true));
        pinned.add(
                "merchant_offers",
                InventoryPackets.Offers.class,
                inventoryReader(names, "minecraft:merchant_offers", false));
        play.add("block_update", WorldPackets.BlockUpdate.class, new WritablePacketCodec<>() {
            @Override
            public boolean requiresModelValues() {
                return true;
            }

            @Override
            public WorldPackets.BlockUpdate read(ByteBuf input, ProtocolContext context) {
                requireModelWire(context);
                return ModelWorldValues.blockUpdate(input);
            }

            @Override
            public void write(ByteBuf output, ProtocolContext context, WorldPackets.BlockUpdate update) {
                requireModelWire(context);
                output.writeLong(update.position().asLong());
                Wire.writeVarInt(output, update.state());
            }
        });
        play.add(
                "section_blocks_update",
                WorldPackets.SectionBlocksUpdate.class,
                worldReader(ModelWorldValues::sectionUpdates));
        pinned.add(
                "level_chunk_with_light", WorldPackets.Chunk.class, namedReader(names, ModelWorldValues::chunk, true));
        pinned.add(
                "block_entity_data",
                WorldPackets.BlockEntityUpdate.class,
                namedReader(names, ModelWorldValues::blockEntity, true));
        pinned.add(
                "set_time",
                WorldPackets.TimeUpdate.class,
                namedReader(names, (input, ids) -> ClientMetadataValues.time(input, MODEL_VERSION, ids::name), true));
        pinned.add(
                "update_recipes",
                WorldPackets.RecipeInputs.class,
                namedReader(names, (input, ids) -> ClientMetadataValues.recipes(input, ids::name), false));
        pinned.add("chunks_biomes", WorldPackets.ChunkBiomes.class, worldReader(ModelWorldValues::biomes));
        pinned.add("light_update", WorldPackets.LightUpdate.class, worldReader(ModelWorldValues::light));
        return Stream.of(Packets.all(), serverbound.types(), clientbound.types())
                .flatMap(List::stream)
                .toList();
    }

    private static WritablePacketCodec<InventoryPackets.Slot> inventorySlotCodec(
            Function<ProtocolContext, RegistryNames> snapshots) {
        var reader = ClientPacketCodecs.<InventoryPackets.Slot>inventoryReader(
                snapshots, "minecraft:container_set_slot", true);
        return new WritablePacketCodec<>() {
            @Override
            public boolean requiresModelValues() {
                return true;
            }

            @Override
            public boolean readsEntirePayload() {
                return true;
            }

            @Override
            public InventoryPackets.Slot read(ByteBuf input, ProtocolContext context) {
                return reader.read(input, context);
            }

            @Override
            public void write(ByteBuf output, ProtocolContext context, InventoryPackets.Slot packet) {
                requireModelWire(context);
                var snapshot = snapshots.apply(context);
                var names = new WireValueDecoder.Registries() {
                    @Override
                    public String name(String registry, int id) {
                        return snapshot.name(registry, id);
                    }

                    @Override
                    public int id(String registry, String name) {
                        return snapshot.id(registry, name);
                    }
                };
                Wire.writeVarInt(output, packet.windowId());
                Wire.writeVarInt(output, packet.stateId());
                output.writeShort(packet.slot());
                new ModelItemValues(
                                ProtocolCodecs.decoder(),
                                MODEL_VERSION,
                                names,
                                ac.cult.cultac.utils.inventory.ItemUtil.modelItems())
                        .write(output, packet.item());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <R> PacketCodec<R> inventoryReader(
            Function<ProtocolContext, RegistryNames> snapshots, String name, boolean entirePayload) {
        return namedReader(
                snapshots,
                (input, snapshot) -> {
                    var names = new WireValueDecoder.Registries() {
                        @Override
                        public String name(String registry, int id) {
                            return snapshot.name(registry, id);
                        }

                        @Override
                        public int id(String registry, String name) {
                            return snapshot.id(registry, name);
                        }
                    };
                    return (R)
                            new ModelInventoryValues(ProtocolCodecs.decoder(), MODEL_VERSION, names).read(input, name);
                },
                entirePayload);
    }

    private static <R> PacketCodec<R> namedReader(
            Function<ProtocolContext, ac.cult.cultac.protocol.data.RegistryNames> names,
            java.util.function.BiFunction<ByteBuf, ac.cult.cultac.protocol.data.RegistryNames, R> reader,
            boolean entirePayload) {
        return new PacketCodec<>() {
            @Override
            public boolean requiresModelValues() {
                return true;
            }

            @Override
            public boolean readsEntirePayload() {
                return entirePayload;
            }

            @Override
            public R read(ByteBuf input, ProtocolContext context) {
                requireModelWire(context);
                return reader.apply(input, names.apply(context));
            }
        };
    }

    private static void requireModelWire(ProtocolContext context) {
        if (context.version() != MODEL_VERSION)
            throw new ac.cult.cultac.protocol.ProtocolResolutionException(
                    "Model value codec needs " + MODEL_VERSION + " wire bytes, received " + context.version());
    }

    private static WritablePacketCodec<EntityMetadata> metadataCodec(
            Function<ProtocolContext, RegistryNames> snapshots,
            Function<ProtocolContext, ClientWorldRegistries> worldRegistries) {
        return new WritablePacketCodec<>() {
            @Override
            public boolean requiresModelValues() {
                return true;
            }

            @Override
            public EntityMetadata read(ByteBuf input, ProtocolContext context) {
                requireModelWire(context);
                var snapshot = snapshots.apply(context);
                var names = new WireValueDecoder.Registries() {
                    @Override
                    public String name(String registry, int id) {
                        return snapshot.name(registry, id);
                    }

                    @Override
                    public int id(String registry, String name) {
                        return snapshot.id(registry, name);
                    }
                };
                var values = new ModelEntityMetadataValues(
                        ProtocolCodecs.decoder(),
                        MODEL_VERSION,
                        names,
                        name -> worldRegistries.apply(context).painting(name));
                return values.read(input);
            }

            @Override
            public void write(ByteBuf output, ProtocolContext context, EntityMetadata packet) {
                requireModelWire(context);
                Wire.writeVarInt(output, packet.id());
                for (var entry : packet.packedItems()) output.writeBytes(entry.bytes());
                output.writeByte(255);
            }
        };
    }

    private static <R> PacketCodec<R> worldReader(Function<ByteBuf, R> reader) {
        return new PacketCodec<>() {
            @Override
            public boolean requiresModelValues() {
                return true;
            }

            @Override
            public R read(ByteBuf input, ProtocolContext context) {
                requireModelWire(context);
                return reader.apply(input);
            }
        };
    }
}
