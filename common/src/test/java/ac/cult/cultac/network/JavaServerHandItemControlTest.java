package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ClientPacketListener#handleSetHeldSlot, #handleContainerSetSlot and #handleSetPlayerInventory change the rider's
 * hands as the client handles them, and Strider#getControllingPassenger reads Player#isHolding again for every later
 * entity packet. ClientPacketListener#handleMoveEntity then only moves the position codec of a root the rider controls
 * (Entity#isLocalInstanceAuthoritative), and interpolates one it does not, before the client's next tick reports
 * which it was.
 */
class JavaServerHandItemControlTest {
    private static final int STRIDER = 9500;
    private static final int FUNGUS_SLOT = 3;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    /** Strider#getRiddenSpeed off warm blocks: Strider#tick keeps it suffocating, with its -0.34F base modifier. */
    private static final float COLD_RIDDEN_SPEED = (float) ((0.175F + 0.175F * (double) -0.34F) * 0.35F);
    /**
     * The strider's first ridden tick from rest, facing +Z. Nothing has grounded it since it was added, so
     * LivingEntity#getFlyingSpeed moves it a tenth of its ridden speed, and its unmoved Y meets no floor.
     */
    private static final Vec3 FIRST_CONTROLLED = START.add(0, 0, COLD_RIDDEN_SPEED * 0.1F);
    /** Three blocks along +X, in ClientboundMoveEntity's 1/4096 units. */
    private static final short THREE_BLOCKS = 3 * 4096;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aServerSelectedFungusHandsTheRiderControlBeforeTheNextEntityMove(ClientVersion version) throws Exception {
        try (var f = mounted(version)) {
            hotbar(f, FUNGUS_SLOT, fungus());
            f.rideTick();
            assertFalse(controls(f));

            setHeldSlot(f, FUNGUS_SLOT);
            assertTrue(controls(f));
            moveStrider(f);
            assertFalse(interpolating(f));

            // MultiPlayerGameMode#tick reports the server's slot before the tick's MoveVehicle.
            f.selectHotbarSlot(FUNGUS_SLOT);
            assertVehicleExact(f.rideTick(FIRST_CONTROLLED, false), 0);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aServerSentOffhandFungusHandsTheRiderControlBeforeTheNextEntityMove(ClientVersion version)
            throws Exception {
        try (var f = mounted(version)) {
            f.rideTick();
            assertFalse(controls(f));

            setInventoryMenuSlot(f, Inventory.SLOT_OFFHAND, fungus());
            assertTrue(controls(f));
            moveStrider(f);
            assertFalse(interpolating(f));
            assertVehicleExact(f.rideTick(FIRST_CONTROLLED, false), 0);
        }
    }

    @Test
    void aServerUpdateTakingTheFungusAwayInterpolatesTheNextEntityMove() throws Exception {
        try (var f = mounted(ClientVersion.V_1_21_11)) {
            hotbar(f, 0, fungus());
            assertVehicleExact(f.rideTick(FIRST_CONTROLLED, false), 0);
            assertTrue(controls(f));

            setPlayerInventorySlot(f, 0, SimItemStack.EMPTY);
            assertFalse(controls(f));
            moveStrider(f);
            assertTrue(interpolating(f));
        }
    }

    /**
     * A key press in the tick's Minecraft#handleKeybinds selects the fungus after MultiPlayerGameMode#tick, so the
     * claim trails that tick's MoveVehicle. A server update that leaves both hands as they were cannot end control.
     */
    @Test
    void aServerUpdateLeavingBothHandsAloneKeepsTheTickEndDecision() throws Exception {
        try (var f = mounted(ClientVersion.V_1_21_11)) {
            hotbar(f, FUNGUS_SLOT, fungus());
            assertVehicleExact(f.rideTick(FIRST_CONTROLLED, false), 0);
            assertTrue(controls(f));

            setPlayerInventorySlot(f, FUNGUS_SLOT + 2, ItemUtil.modelItems().stack("minecraft:stone", 1));
            assertTrue(controls(f));
            moveStrider(f);
            assertFalse(interpolating(f));
        }
    }

    private static boolean controls(JavaTickFixture f) {
        return f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(strider(f));
    }

    private static boolean interpolating(JavaTickFixture f) {
        PacketEntity strider = strider(f);
        return strider.newPacketLocation != null && strider.newPacketLocation.hasActiveInterpolationTarget();
    }

    private static PacketEntity strider(JavaTickFixture f) {
        return f.player.compensatedEntities.getEntity(STRIDER);
    }

    private static SimItemStack fungus() {
        return ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1);
    }

    private static void hotbar(JavaTickFixture f, int slot, SimItemStack item) {
        f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET + slot, item);
    }

    /** A set_held_slot, answered before the client's next tick. */
    private static void setHeldSlot(JavaTickFixture f, int slot) {
        var packet = new ClientboundSetHeldSlot(slot);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_HELD_SLOT, packet, false);
        f.player.getInventory().onSetHeldSlot(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A container_set_slot for InventoryMenu's {@code slot}, answered before the client's next tick. */
    private static void setInventoryMenuSlot(JavaTickFixture f, int slot, SimItemStack item) {
        var packet = new InventoryPackets.Slot(0, 1, slot, item);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, type(InventoryPackets.Slot.class), packet, false);
        f.player.getInventory().onContainerSetSlot(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A set_player_inventory for Inventory's {@code slot}, answered before the client's next tick. */
    private static void setPlayerInventorySlot(JavaTickFixture f, int slot, SimItemStack item) {
        var packet = new InventoryPackets.PlayerInventory(slot, item);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, type(InventoryPackets.PlayerInventory.class), packet, false);
        f.player.getInventory().onSetPlayerInventory(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A relative move_entity taking the strider three blocks along +X, answered before the client's next tick. */
    private static void moveStrider(JavaTickFixture f) {
        var packet = new ClientboundMoveEntity(
                STRIDER, new EntityDelta.Linear(THREE_BLOCKS, (short) 0, (short) 0), 0, 0, true, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A saddled strider under the rider, holding nothing, after a second of passive riding. */
    private static JavaTickFixture mounted(ClientVersion version) throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, version);
        f.player.nettyScheduler.removeScheduler();
        f.place(START, Vec3.ZERO);
        f.teleport(START, new RelativeFlag(0));
        var add = new ClientboundAddEntity(STRIDER, "minecraft:strider", new Vec3d(START.x, START.y, START.z), 0, 0, 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, add);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        ((PacketEntityStrider) strider(f)).hasSaddle = true;
        f.setPassengers(STRIDER, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 0; tick < 20; tick++) f.rideTick();
        return f;
    }

    @SuppressWarnings("unchecked")
    private static <T> PacketType<T> type(Class<T> record) {
        return (PacketType<T>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == record)
                .findFirst()
                .orElseThrow();
    }
}
