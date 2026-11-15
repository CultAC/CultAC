package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HappyGhast#getControllingPassenger gives its first Player passenger control only while Mob#isWearingBodyArmor: the
 * BODY item's EQUIPPABLE component targets BODY and admits a happy ghast (LivingEntity#isEquippableInSlot), and
 * HappyGhast#canUseSlot opens BODY only to a live adult. Any other item in the slot leaves the rider a passenger.
 */
class JavaHappyGhastHarnessTest {
    private static final int GHAST = 100;

    @Test
    void aHarnessGivesTheRiderControl() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertTrue(controlled(f, ridden(f), item("minecraft:white_harness")));
        }
    }

    @Test
    void anotherBodyItemLeavesTheRiderAPassenger() throws Exception {
        // Horse and wolf armor target BODY but admit only their own entity; a block has no EQUIPPABLE at all.
        for (String key : List.of("minecraft:diamond_horse_armor", "minecraft:wolf_armor", "minecraft:stone")) {
            try (var f = new JavaTickFixture()) {
                assertFalse(controlled(f, ridden(f), item(key)), key);
            }
        }
    }

    @Test
    void theItemsComponentsDecideNotItsType() throws Exception {
        SimItemStack harness = item("minecraft:white_harness");
        harness.components(harness.components().with("minecraft:equippable", null));
        try (var f = new JavaTickFixture()) {
            assertFalse(controlled(f, ridden(f), harness), "a harness without EQUIPPABLE");
        }
        SimItemStack stone = item("minecraft:stone");
        stone.components(stone.components()
                .with(
                        "minecraft:equippable",
                        JsonParser.parseString("{\"slot\":\"body\",\"allowed_entities\":\"minecraft:happy_ghast\"}")));
        try (var f = new JavaTickFixture()) {
            assertTrue(controlled(f, ridden(f), stone), "a stone equippable as a happy ghast's BODY");
        }
    }

    @Test
    void aHarnessedBabyOrDeadGhastLeavesTheRiderAPassenger() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity ghast = ridden(f);
            assertTrue(controlled(f, ghast, item("minecraft:white_harness")));
            metadata(f, WatchableIndexUtil.AGEABLE_BABY, true);
            assertFalse(f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(ghast), "baby");
        }
        try (var f = new JavaTickFixture()) {
            PacketEntity ghast = ridden(f);
            assertTrue(controlled(f, ghast, item("minecraft:white_harness")));
            // LivingEntity#isAlive reads DATA_HEALTH_ID, which can reach zero before the death entity event.
            metadata(f, WatchableIndexUtil.LIVING_HEALTH, 0.0F);
            assertFalse(f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(ghast), "dead");
        }
    }

    /** An adult happy ghast that the local player rides as its first passenger. */
    private static PacketEntity ridden(JavaTickFixture f) {
        Vec3 at = new Vec3(0.5, JavaTickFixture.FLOOR_Y + 2, 0.5);
        f.place(at, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(GHAST, EntityTypeIds.HAPPY_GHAST, at, 0, 0, 0);
        f.setPassengers(GHAST, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        PacketEntity ghast = f.player.compensatedEntities.getEntity(GHAST);
        assertFalse(f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(ghast), "unequipped");
        return ghast;
    }

    /** Whether the local player controls {@code ghast} once its BODY slot holds {@code body}. */
    private static boolean controlled(JavaTickFixture f, PacketEntity ghast, SimItemStack body) {
        f.equip(GHAST, EquipmentSlot.BODY, body);
        return f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(ghast);
    }

    private static void metadata(JavaTickFixture f, int index, Object value) {
        f.player.compensatedEntities.updateEntityMetadata(
                GHAST, List.of(new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0))));
    }
}
