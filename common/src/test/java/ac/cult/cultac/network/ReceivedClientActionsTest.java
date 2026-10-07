package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.inventory.Inventory;
import org.junit.jupiter.api.Test;

class ReceivedClientActionsTest {
    @Test
    void placementAndResolvedBreakingApplyWorldWritesAndConsumptionThroughTheExistingBoundary() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -2;
            player.boundingBox = new ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox(
                    .2, 64, -2.3, .8, 65.8, -1.7, false);
            var support = new ac.cult.cultac.protocol.value.BlockPos(1, 63, 1);
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            player.compensatedWorld.updateBlock(
                    support,
                    DataTables.defaults().registry().block("minecraft:dirt").defaultState());
            var held = OfflineCultTestBootstrap.item("minecraft:snow_block", 3);
            // Count changes must preserve the captured typed patch.
            held.setComponent("minecraft:can_break", new NbtValue.Sequence(java.util.List.of()));
            held.setComponent(
                    "minecraft:tool",
                    CanonicalSnbt.parse(
                            "{rules:[],default_mining_speed:-0.0f,damage_per_block:1,can_destroy_blocks_in_creative:1b}"));
            var ownedHeld = held.copy();
            player.getInventory().inventory.setHeldItem(ownedHeld);
            player.packetStateData.clientSidePosition = new ac.cult.cultac.utils.math.Vec3(.5, 64, -2);
            player.compensatedWorld.advanceClientPredictionSequence();
            ac.cult.cultac.events.packets.blockplace.PlaceHandler.handleQueuedUseItemOn(
                    player,
                    new ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn(
                            Hand.MAIN_HAND,
                            support,
                            ac.cult.cultac.protocol.value.Direction.UP,
                            new ac.cult.cultac.protocol.value.Vec3d(.5, 1, .5),
                            false,
                            false,
                            1));
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:snow_block"),
                    DataTables.defaults().registry().block(player.compensatedWorld.getBlockStateIdAt(support.above())));
            assertEquals(2, player.getInventory().getHeldItem().getCount());
            assertEquals(3, held.getCount(), "Prediction preserves the received stack");
            assertEquals(ownedHeld.patch(), player.getInventory().getHeldItem().patch());
            player.compensatedWorld.advanceClientPredictionSequence();
            player.compensatedWorld.startPredicting();
            try {
                assertTrue(ClientBlockActions.breakBlock(player, support.above()));
            } finally {
                player.compensatedWorld.stopPredicting(2);
            }
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(player.compensatedWorld.getBlockStateIdAt(support.above()))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            assertFalse(ClientBlockActions.breakBlock(player, support.above()));
            assertEquals(2, player.getInventory().getHeldItem().getCount());

            var edge = new ac.cult.cultac.protocol.value.BlockPos(1, 63, 0);
            player.compensatedWorld.updateBlock(
                    edge,
                    DataTables.defaults().registry().block("minecraft:dirt").defaultState());
            var missingPlace = new ac.cult.cultac.utils.anticheat.update.BlockPlace(
                    player,
                    Hand.MAIN_HAND,
                    edge,
                    ac.cult.cultac.protocol.value.Direction.NORTH,
                    player.getInventory().getHeldItem(),
                    null);
            missingPlace.setCursor(new ac.cult.cultac.utils.math.Vec3(.5, .5, 0));
            ClientBlockActions.useOn(player, missingPlace);
            assertEquals(
                    2,
                    player.getInventory().getHeldItem().getCount(),
                    "An empty client chunk rejects placement without consumption");
            assertFalse(player.compensatedWorld.isChunkLoaded(0, -1));
            assertTrue(
                    ClientBlockActions.breakBlock(player, edge),
                    "Missing neighbors do not discard a loaded block's destruction");

            player.getInventory().inventory.selected = 4;
            player.getInventory().inventory.setHeldItem(ownedHeld);
            player.gamemode = GameMode.CREATIVE;
            player.canInstabuild = true;
            var sword = OfflineCultTestBootstrap.item("minecraft:diamond_sword");
            player.getInventory().inventory.setHeldItem(sword);
            player.compensatedWorld.updateBlock(
                    edge,
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            assertFalse(
                    ClientBlockActions.breakBlock(player, edge),
                    "Creative destruction uses the selected hand's tool rules");
            assertSame(sword, player.getInventory().getHeldItem());
            assertEquals(
                    2,
                    player.getInventory()
                            .inventory
                            .getInventoryStorage()
                            .getItem(Inventory.HOTBAR_OFFSET)
                            .getCount());
        }
    }

    @Test
    void buildingObstructionUsesMarkerFlagsAndCachedReceivedSpectatorModes() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            var view = new ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView(
                    player.compensatedWorld,
                    player.checkManager.getListener(ac.cult.cultac.events.packets.PacketWorldBorder.class),
                    ac.cult.cultac.protocol.data.ModelBlockStates.project(
                            ProtocolVersion.V26_3, ProtocolVersion.V26_3));
            var shape = ac.cult.blocksim.engine.shapes.Shapes.create(1, 64, 1, 2, 65, 2);
            var entities = player.compensatedEntities;
            var pos = new ac.cult.cultac.utils.math.Vec3(1.5, 64, 1.5);
            entities.addEntity(42, ac.cult.blocksim.entity.EntityTypeIds.ARMOR_STAND, pos, 0, 0, 0);
            assertFalse(view.isUnobstructed(shape));
            entities.updateEntityMetadata(
                    42,
                    java.util.List.of(new ac.cult.cultac.network.packet.EntityMetadata.Entry(
                            15, (byte) 16, java.nio.ByteBuffer.wrap(new byte[] {15, 0, 16}))));
            assertTrue(view.isUnobstructed(shape));
            entities.updateEntityMetadata(
                    42,
                    java.util.List.of(new ac.cult.cultac.network.packet.EntityMetadata.Entry(
                            15, (byte) 1, java.nio.ByteBuffer.wrap(new byte[] {15, 0, 1}))));
            assertTrue(view.isUnobstructed(ac.cult.blocksim.engine.shapes.Shapes.create(1, 65.2, 1, 2, 65.8, 2)));
            entities.removeEntity(42);

            var profile = java.util.UUID.randomUUID();
            var actions = java.util.EnumSet.of(
                    ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Action.ADD_PLAYER,
                    ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE);
            var spectator = new ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate(
                    actions,
                    java.util.List.of(new ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Entry(
                            profile, GameMode.SPECTATOR, java.util.Map.of())));
            entities.clientPlayerModes.update(spectator, java.util.Set.of(profile), entities.entityMap.values());
            entities.addEntity(43, ac.cult.blocksim.entity.EntityTypeIds.PLAYER, pos, 0, 0, 0);
            var remote = (ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer) entities.getEntity(43);
            remote.profile = profile;
            assertTrue(view.isUnobstructed(shape));
            entities.clientPlayerModes.remove(java.util.List.of(profile));
            assertTrue(view.isUnobstructed(shape), "The client remote player keeps its cached PlayerInfo");
            var replacement = new ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate(
                    actions,
                    java.util.List.of(new ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Entry(
                            profile, GameMode.SURVIVAL, java.util.Map.of())));
            entities.clientPlayerModes.update(replacement, java.util.Set.of(profile), entities.entityMap.values());
            assertTrue(
                    view.isUnobstructed(shape),
                    "A new profile entry does not replace the old entity's cached identity");
            entities.addEntity(44, ac.cult.blocksim.entity.EntityTypeIds.PLAYER, pos, 0, 0, 0);
            ((ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer) entities.getEntity(44)).profile = profile;
            assertFalse(view.isUnobstructed(shape));
        }
    }

    @Test
    void equipmentSwapPreservesComponentsAndAppliesItsCooldownBeforeTheNextUse() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            var storage = player.getInventory().inventory.getInventoryStorage();
            player.packetStateData.clientSidePosition =
                    new ac.cult.cultac.utils.math.Vec3(player.x, player.y, player.z);
            var helmet = OfflineCultTestBootstrap.item("minecraft:diamond_helmet");
            helmet.setComponent("minecraft:custom_data", CanonicalSnbt.parse("{received_marker:123}"));
            var group = "test:equipment";
            helmet.setComponent(
                    "minecraft:use_cooldown", CanonicalSnbt.parse("{seconds:2.0f,cooldown_group:'test:equipment'}"));
            storage.setItem(Inventory.HOTBAR_OFFSET, helmet.copy());
            storage.setItem(Inventory.SLOT_HELMET, OfflineCultTestBootstrap.item("minecraft:iron_helmet"));
            queuedUse(player, Hand.MAIN_HAND, 1);
            assertSame(
                    ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:iron_helmet"),
                    storage.getItem(Inventory.HOTBAR_OFFSET).getItem());
            var worn = storage.getItem(Inventory.SLOT_HELMET);
            assertSame(
                    ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:diamond_helmet"),
                    worn.getItem());
            assertEquals(
                    123,
                    ((ac.cult.blocksim.data.nbt.NbtValue.Numeric)
                                    ac.cult.blocksim.data.ItemComponents.customData(worn.components())
                                            .values()
                                            .get("received_marker"))
                            .value()
                            .intValue());
            assertEquals(
                    group.toString(),
                    ac.cult.blocksim.data.ItemComponents.useCooldown(worn.components())
                            .group());
            assertTrue(player.checkManager.getCompensatedCooldown().hasItem(worn));
            assertFalse(player.packetStateData.isSlowedByUsingItem());

            // A newly received held stack shares the active cooldown group.
            storage.setItem(Inventory.HOTBAR_OFFSET, helmet.copy());
            storage.setItem(Inventory.SLOT_HELMET, OfflineCultTestBootstrap.item("minecraft:iron_helmet"));
            queuedUse(player, Hand.MAIN_HAND, 2);
            assertSame(
                    ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:diamond_helmet"),
                    storage.getItem(Inventory.HOTBAR_OFFSET).getItem());
            assertSame(
                    ac.cult.cultac.utils.inventory.ItemUtil.modelItems().item("minecraft:iron_helmet"),
                    storage.getItem(Inventory.SLOT_HELMET).getItem());

            // Player.canEat reads the received invulnerability ability even in survival.
            player.food = 20;
            storage.setItem(Inventory.SLOT_OFFHAND, OfflineCultTestBootstrap.item("minecraft:apple", 2));
            queuedUse(player, Hand.OFF_HAND, 3);
            assertFalse(player.packetStateData.isSlowedByUsingItem());
            player.isInvulnerable = true;
            queuedUse(player, Hand.OFF_HAND, 4);
            assertTrue(player.packetStateData.isSlowedByUsingItem());
            assertEquals(Hand.OFF_HAND, player.packetStateData.itemInUseHand);
            assertEquals(2, storage.getItem(Inventory.SLOT_OFFHAND).getCount());
        }
    }

    @Test
    void tridentChargingKeepsPacketUseInferenceWithoutPredictingInventoryEffects() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            // A trident's item override ignores these generic use/equipment components.
            fixture.registrar.register(routes -> routes.registerReceiveListener(
                    ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                    new ac.cult.cultac.events.packets.listeners.CheckManagerListener()));
            var received = OfflineCultTestBootstrap.item("minecraft:trident");
            received.setComponent(
                    "minecraft:use_cooldown", CanonicalSnbt.parse("{seconds:2.0f,cooldown_group:'test:trident'}"));
            received.setComponent(
                    "minecraft:consumable",
                    OfflineCultTestBootstrap.item("minecraft:apple")
                            .components()
                            .encodedNbt("minecraft:consumable"));
            received.setComponent(
                    "minecraft:equippable",
                    OfflineCultTestBootstrap.item("minecraft:diamond_helmet")
                            .components()
                            .encodedNbt("minecraft:equippable"));
            var held = received.copy();
            player.getInventory().inventory.setHeldItem(held);
            forwardUse(fixture, 1);
            assertSame(held, player.getInventory().getHeldItem());
            assertTrue(player.getInventory().getHelmet().isEmpty());
            assertFalse(player.checkManager.getCompensatedCooldown().hasItem(held));
            assertTrue(player.packetStateData.isSlowedByUsingItem());

            received.damage(received.maxDamage() - 1);
            var damaged = received.copy();
            player.getInventory().inventory.setHeldItem(damaged);
            forwardUse(fixture, 2);
            assertSame(damaged, player.getInventory().getHeldItem());
            assertFalse(player.packetStateData.isSlowedByUsingItem());
        }
    }

    private static void queuedUse(ac.cult.cultac.player.CultPlayer player, Hand hand, int sequence) {
        player.compensatedWorld.advanceClientPredictionSequence();
        ac.cult.cultac.events.packets.blockplace.PlaceHandler.handleQueuedUseItem(
                player,
                new ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem(
                        hand, sequence, player.xRot, player.yRot));
    }

    private static void forwardUse(RecordReceiveFixture fixture, int sequence) {
        var frame = fixture.id(ac.cult.cultac.protocol.packet.ServerboundPackets.USE_ITEM, "use_item");
        ac.cult.cultac.protocol.wire.Wire.writeVarInt(frame, 0);
        ac.cult.cultac.protocol.wire.Wire.writeVarInt(frame, sequence);
        frame.writeFloat(0).writeFloat(0);
        fixture.forward(frame);
    }
}
