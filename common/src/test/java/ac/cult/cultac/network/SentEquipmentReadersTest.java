package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Equipment whose stacks are supplied on read changes the model as eager entries do, reading only what it uses. */
class SentEquipmentReadersTest {
    private static final EquipmentSlot[] ALL = {
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS,
        EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.BODY, EquipmentSlot.SADDLE
    };

    private static SimItemStack item(EquipmentSlot slot) {
        return ItemUtil.modelItems().stack(switch (slot) {
            case MAINHAND -> "minecraft:diamond_sword";
            case OFFHAND -> "minecraft:shield";
            case FEET -> "minecraft:iron_boots";
            case LEGS -> "minecraft:iron_leggings";
            case CHEST -> "minecraft:iron_chestplate";
            case HEAD -> "minecraft:iron_helmet";
            case BODY -> "minecraft:white_harness";
            case SADDLE -> "minecraft:saddle";
        }, 1);
    }

    private record Read(List<EquipmentSlot> slots) {}

    @SuppressWarnings("unchecked")
    private static List<InventoryPackets.EquipmentEntry> sent(SimItemStack[] items, Read read) {
        Supplier<SimItemStack>[] suppliers = new Supplier[ALL.length];
        for (int index = 0; index < ALL.length; index++) {
            int slot = index;
            suppliers[index] = () -> {
                read.slots().add(ALL[slot]);
                return items[slot];
            };
        }
        return new InventoryPackets.SentEquipment(ALL.clone(), suppliers);
    }

    private static List<InventoryPackets.EquipmentEntry> eager(SimItemStack[] items) {
        var entries = new ArrayList<InventoryPackets.EquipmentEntry>();
        for (int index = 0; index < ALL.length; index++)
            entries.add(new InventoryPackets.EquipmentEntry(ALL[index], items[index]));
        return List.copyOf(entries);
    }

    private static SimItemStack[] items() {
        var items = new SimItemStack[ALL.length];
        for (int index = 0; index < ALL.length; index++) items[index] = item(ALL[index]);
        return items;
    }

    private static void spawn(CultPlayer player) {
        player.compensatedEntities.addEntity(42, EntityTypeIds.HORSE, Vec3.ZERO, 0, 0, 0);
        player.compensatedEntities.addEntity(43, EntityTypeIds.HAPPY_GHAST, Vec3.ZERO, 0, 0, 0);
    }

    @Test
    void otherEntitiesReadOnlyTheirSaddleAndAHappyGhastBody() throws Exception {
        try (var eagerFixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
                var sentFixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            spawn(eagerFixture.player);
            spawn(sentFixture.player);
            var items = items();
            var read = new Read(new ArrayList<>());
            for (int entity : new int[] {42, 43, 44}) {
                eagerFixture.player.compensatedEntities.updateEntityEquipment(entity, eager(items));
                read.slots().clear();
                var sent = sent(items, read);
                sentFixture.player.compensatedEntities.updateEntityEquipment(entity, sent);
                if (entity == 44) assertEquals(List.of(), read.slots());
                else if (entity == 42) assertEquals(List.of(EquipmentSlot.SADDLE), read.slots());
                else assertEquals(List.of(EquipmentSlot.BODY, EquipmentSlot.SADDLE), read.slots());
                for (int index = 0; index < ALL.length; index++)
                    assertEquals(ALL[index], InventoryPackets.Equipment.slot(sent, index));
            }
            var eagerHorse = (PacketEntityHorse) eagerFixture.player.compensatedEntities.getEntity(42);
            var sentHorse = (PacketEntityHorse) sentFixture.player.compensatedEntities.getEntity(42);
            assertTrue(sentHorse.hasSaddle);
            assertEquals(eagerHorse.hasSaddle, sentHorse.hasSaddle);
            assertSame(items[7], sentHorse.saddleItem);
            assertSame(eagerHorse.saddleItem, sentHorse.saddleItem);
            var eagerGhast = (PacketEntityHappyGhast) eagerFixture.player.compensatedEntities.getEntity(43);
            var sentGhast = (PacketEntityHappyGhast) sentFixture.player.compensatedEntities.getEntity(43);
            assertTrue(sentGhast.hasBodyArmor);
            assertEquals(eagerGhast.hasBodyArmor, sentGhast.hasBodyArmor);
            for (int slot = 0; slot <= Inventory.SLOT_SADDLE; slot++)
                assertTrue(sentFixture.player.getInventory().inventory.getInventoryStorage().getItem(slot).isEmpty());
        }
    }

    @Test
    void theViewerReadsEveryStackIntoItsInventory() throws Exception {
        try (var eagerFixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
                var sentFixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var items = items();
            var read = new Read(new ArrayList<>());
            int eagerSelf = eagerFixture.player.entityID, sentSelf = sentFixture.player.entityID;
            eagerFixture.player.compensatedEntities.updateEntityEquipment(eagerSelf, eager(items));
            sentFixture.player.compensatedEntities.updateEntityEquipment(sentSelf, sent(items, read));
            assertEquals(List.of(ALL), read.slots());
            var eagerStorage = eagerFixture.player.getInventory().inventory.getInventoryStorage();
            var sentStorage = sentFixture.player.getInventory().inventory.getInventoryStorage();
            int filled = 0;
            for (int slot = 0; slot <= Inventory.SLOT_SADDLE; slot++) {
                var expected = eagerStorage.getItem(slot);
                var actual = sentStorage.getItem(slot);
                assertEquals(expected.itemKey(), actual.itemKey());
                assertEquals(expected.count(), actual.count());
                assertTrue(expected.matches(actual));
                if (!actual.isEmpty()) filled++;
            }
            assertEquals(ALL.length, filled);
            assertFalse(sentStorage.getItem(Inventory.SLOT_HELMET) == items[5]);
        }
    }
}
