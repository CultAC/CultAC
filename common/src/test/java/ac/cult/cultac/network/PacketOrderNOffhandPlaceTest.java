package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderN;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Minecraft#startUseItem facing a block tries the main hand, then the off hand: each hand sends use_item_on, then
 * use_item when it holds an item, and the loop stops at the first Success (or a use_item_on Fail).
 */
class PacketOrderNOffhandPlaceTest {
    private static final Vec3 STANDING = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final BlockPos CLICKED = new BlockPos(2, JavaTickFixture.FLOOR_Y, 0);
    private static final BlockPos PLACED = new BlockPos(1, JavaTickFixture.FLOOR_Y, 0);

    private JavaTickFixture fixture;
    private int stone;
    private int sequence;

    @BeforeEach
    void connect() throws Exception {
        fixture = new JavaTickFixture();
        fixture.player.setExperimentalChecks(true);
        fixture.player.food = 20;
        stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
        fixture.player.compensatedWorld.updateBlock(CLICKED.getX(), CLICKED.getY(), CLICKED.getZ(), stone);
        fixture.place(STANDING, Vec3.ZERO);
        fixture.player.xRot = -90;
        fixture.player.yRot = 36;
        fixture.tick(STANDING, true);
    }

    @AfterEach
    void disconnect() throws Exception {
        fixture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:diamond_sword", "minecraft:diamond_pickaxe", "minecraft:bread"})
    void aMainHandItemThatPassesFallsThroughToTheOffhandBlock(String mainHand) {
        // Item#useOn passes without a block transformer; Item#use passes for a sword or tool and fails for food
        // when Player#canEat is false. Neither is a Success, so the loop reaches the off hand.
        hold(mainHand, "minecraft:stone");
        useOn(Hand.MAIN_HAND);
        use(Hand.MAIN_HAND);
        useOn(Hand.OFF_HAND);
        fixture.tick(STANDING, true);

        assertEquals(stone, fixture.player.compensatedWorld.getBlockStateIdAt(PLACED), "the off hand placed");
        assertEquals(0, violations());
    }

    @Test
    void anEmptyMainHandSendsNoUseItem() {
        hold(null, "minecraft:stone");
        assertTrue(fixture.player.getInventory().getHeldItem().isEmpty());
        useOn(Hand.MAIN_HAND);
        useOn(Hand.OFF_HAND);
        fixture.tick(STANDING, true);

        assertEquals(0, violations());
    }

    @Test
    void aMainHandBlockPlacesAndEndsTheLoop() {
        hold("minecraft:stone", "minecraft:stone");
        useOn(Hand.MAIN_HAND);
        fixture.tick(STANDING, true);

        assertEquals(0, violations());
    }

    @Test
    void aUseItemWithNoUseItemOnBeforeItFlagsTheLaterPlacement() {
        // A use_item without its use_item_on means the hit result was not a block, so no hand can place this tick.
        hold("minecraft:diamond_sword", "minecraft:stone");
        use(Hand.MAIN_HAND);
        useOn(Hand.OFF_HAND);
        fixture.tick(STANDING, true);

        assertEquals(1, violations());
    }

    @Test
    void theMainHandUseItemOnDoesNotCoverTheOffhandUseItem() {
        hold("minecraft:diamond_sword", "minecraft:stone");
        useOn(Hand.MAIN_HAND);
        use(Hand.MAIN_HAND);
        use(Hand.OFF_HAND);
        useOn(Hand.OFF_HAND);
        fixture.tick(STANDING, true);

        assertEquals(1, violations());
    }

    /** Holds {@code mainHand} in the selected hotbar slot and {@code offHand} in the off hand; null is empty. */
    private void hold(String mainHand, String offHand) {
        var storage = fixture.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(Inventory.HOTBAR_OFFSET, stack(mainHand));
        storage.setItem(Inventory.SLOT_OFFHAND, stack(offHand));
        fixture.player.getInventory().selectHotbarSlot(0);
    }

    private static SimItemStack stack(String item) {
        return item == null ? SimItemStack.EMPTY : ItemUtil.modelItems().stack(item, 16);
    }

    private double violations() {
        return fixture.player.checkManager.getCheck(PacketOrderN.class).violations;
    }

    private void use(Hand hand) {
        fixture.player.packetStateData.clientSidePosition = STANDING;
        var packet = new ServerboundUseItem(hand, ++sequence, fixture.player.xRot, fixture.player.yRot);
        fixture.listener.onUseItem(fixture.event(ServerboundPackets.USE_ITEM, packet), fixture.player, packet);
    }

    private void useOn(Hand hand) {
        fixture.player.packetStateData.clientSidePosition = STANDING;
        var packet = new ServerboundUseItemOn(
                hand, CLICKED, Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, ++sequence);
        fixture.listener.onUseItemOn(fixture.event(ServerboundPackets.USE_ITEM_ON, packet), fixture.player, packet);
    }
}
