package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.crash.CrashG;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderF;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A use the server never receives is never acknowledged by it; the client's prediction ends at the acknowledgement
 * and block states sent in its place, as ClientLevel#handleBlockChangedAck restores the server state.
 */
class CancelledUseCorrectionTest {
    private static final BlockPos LAVA = new BlockPos(0, 65, 2);
    private static final BlockPos CLICKED = new BlockPos(2, JavaTickFixture.FLOOR_Y, 0);
    private static final Vec3 STANDING = new Vec3(0.69, JavaTickFixture.FLOOR_Y, 0.5);
    private static final int AIR = state("air");
    private static final int STONE = state("stone");
    private static final int LAVA_SOURCE = state("lava");

    @Test
    void withheldBucketPickupRestoresTheFluidAtItsAcknowledgement() throws Exception {
        try (var f = lavaAhead()) {
            int sent = f.player.lastTransactionSent.get();
            var event = use(f, -1);
            assertTrue(event.isCancelled());
            assertEquals(1, f.player.checkManager.getCheck(CrashG.class).violations);
            assertTrue(f.player.lastTransactionSent.get() > sent, "the correction carries its own ping");

            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(LAVA_SOURCE, f.player.compensatedWorld.getBlockStateIdAt(LAVA));
            assertTrue(f.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void withheldPickupKeepsTheFluidGoneUntilTheClientAppliesTheCorrection() throws Exception {
        try (var f = lavaAhead()) {
            use(f, -1);
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(LAVA));
            f.pongThrough(f.player.lastTransactionSent.get() - 1);
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(LAVA));
        }
    }

    @Test
    void forwardedPickupSendsNoCorrectionAndWaitsForTheServerAck() throws Exception {
        try (var f = lavaAhead()) {
            int sent = f.player.lastTransactionSent.get();
            int sequence = f.player.compensatedWorld.clientPredictionSequence() + 1;
            var event = use(f, sequence);
            assertFalse(event.isCancelled());
            assertEquals(sent, f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 20; tick++) f.tick(STANDING_LAVA, true);
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(LAVA), "the pickup waits for its ack");

            f.player.sendTransaction();
            f.player.compensatedWorld.handlePredictionConfirmation(sequence);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void withheldPlacementAfterItsPredictionRemovesTheBlockAtItsAcknowledgement() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.setExperimentalChecks(false);
            f.player.compensatedWorld.updateBlock(CLICKED, STONE);
            f.place(STANDING, Vec3.ZERO);
            f.player.xRot = -90;
            f.player.yRot = 36;
            f.tick(STANDING, true);
            hold(f, "minecraft:stone", 64);
            f.player.packetStateData.clientSidePosition = STANDING;
            int sequence = f.player.compensatedWorld.clientPredictionSequence() + 1;
            var packet = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, CLICKED, Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, sequence);
            f.player.compensatedWorld.advanceClientPredictionSequence(sequence);
            var applied = PlaceHandler.handleQueuedUseItemOn(f.player, packet);
            BlockPos placed = CLICKED.relative(Direction.WEST);
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(placed));

            f.player.blockActionReconciler.rejectPlace(packet, applied);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(placed));
            assertTrue(f.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void placementWithheldByAReceiveCheckAfterItsPredictionRemovesTheBlockAtItsAcknowledgement() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(CLICKED, STONE);
            f.place(STANDING, Vec3.ZERO);
            f.player.xRot = -90;
            f.player.yRot = 36;
            f.tick(STANDING, true);
            hold(f, "minecraft:stone", 64);
            f.player.packetStateData.clientSidePosition = STANDING;
            f.player.setExperimentalChecks(true);
            PacketOrderF order = f.player.checkManager.getCheck(PacketOrderF.class);
            order.setEnabled(true);
            order.reload();
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            var sprintEvent = f.event(ServerboundPackets.PLAYER_COMMAND, sprint);
            new PacketEntityAction().onPlayerCommand(sprintEvent, f.player, sprint);
            f.listener.onPlayerCommand(sprintEvent, f.player, sprint);
            int sent = f.player.lastTransactionSent.get();
            int sequence = f.player.compensatedWorld.clientPredictionSequence() + 100;
            var packet = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, CLICKED, Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, sequence);
            var event = f.event(ServerboundPackets.USE_ITEM_ON, packet);
            f.listener.onUseItemOn(event, f.player, packet);
            assertEquals(1, order.violations);
            assertTrue(event.isCancelled());
            assertTrue(f.player.lastTransactionSent.get() > sent, "the correction carries its own ping");

            BlockPos placed = CLICKED.relative(Direction.WEST);
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(placed), "the client's placement stands");
            f.pongThrough(f.player.lastTransactionSent.get() - 1);
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(placed));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(placed));
            assertTrue(f.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void withheldPickupGivesBackTheBucketTheServerHolds() throws Exception {
        try (var f = lavaAhead()) {
            serverSlot(f, Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack("minecraft:bucket", 1));
            drainOutput(f);
            use(f, -1);
            assertTrue(drainOutput(f).stream().anyMatch(InventoryPackets.Slot.class::isInstance));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals("minecraft:bucket", f.player.getInventory().getHandItem(Hand.MAIN_HAND).itemKey());
        }
    }

    @Test
    void withheldPickupSendsNoSlotTheServerHasNotSent() throws Exception {
        try (var f = lavaAhead()) {
            assertNull(f.player.getInventory().serverViewSlot(Inventory.HOTBAR_OFFSET));
            drainOutput(f);
            use(f, -1);
            assertTrue(drainOutput(f).stream().noneMatch(InventoryPackets.Slot.class::isInstance));
        }
    }

    @Test
    void forwardedPlacementSendsNoCorrection() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.setExperimentalChecks(false);
            f.player.compensatedWorld.updateBlock(CLICKED, STONE);
            f.place(STANDING, Vec3.ZERO);
            f.player.xRot = -90;
            f.player.yRot = 36;
            f.tick(STANDING, true);
            hold(f, "minecraft:stone", 64);
            f.player.packetStateData.clientSidePosition = STANDING;
            int sent = f.player.lastTransactionSent.get();
            int sequence = f.player.compensatedWorld.clientPredictionSequence() + 1;
            var packet = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, CLICKED, Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, sequence);
            var event = f.event(ServerboundPackets.USE_ITEM_ON, packet);
            f.listener.onUseItemOn(event, f.player, packet);
            assertFalse(event.isCancelled());
            assertEquals(sent, f.player.lastTransactionSent.get());
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(CLICKED.relative(Direction.WEST)));
        }
    }

    @Test
    void withheldUseThatPredictsNothingSendsNoCorrection() throws Exception {
        try (var f = lavaAhead()) {
            hold(f, "minecraft:stick", 1);
            int sent = f.player.lastTransactionSent.get();
            assertTrue(use(f, -1).isCancelled());
            assertEquals(sent, f.player.lastTransactionSent.get());
        }
    }

    private static final Vec3 STANDING_LAVA = new Vec3(0.5, 64, 0.5);

    private static JavaTickFixture lavaAhead() throws Exception {
        var f = new JavaTickFixture();
        f.player.setExperimentalChecks(false);
        f.player.compensatedWorld.updateBlock(LAVA, LAVA_SOURCE);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(STANDING_LAVA, Vec3.ZERO);
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.tick(STANDING_LAVA, true);
        f.player.packetStateData.clientSidePosition = STANDING_LAVA;
        hold(f, "minecraft:bucket", 1);
        return f;
    }

    private static PacketReceiveEvent<ServerboundUseItem> use(JavaTickFixture f, int wireSequence) {
        var packet = new ServerboundUseItem(Hand.MAIN_HAND, wireSequence, 0, 0);
        var event = f.event(ServerboundPackets.USE_ITEM, packet);
        f.listener.onUseItem(event, f.player, packet);
        return event;
    }

    private static void serverSlot(JavaTickFixture f, int storageSlot, SimItemStack item) {
        int menuSlot = f.player.getInventory().inventory.menuSlotForStorage(storageSlot);
        var packet = new InventoryPackets.Slot(0, 1, menuSlot, item);
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, slotType(), packet, false);
        f.player.getInventory().onContainerSetSlot(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<InventoryPackets.Slot> slotType() {
        return (PacketType<InventoryPackets.Slot>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == InventoryPackets.Slot.class)
                .findFirst()
                .orElseThrow();
    }

    private static List<Object> drainOutput(JavaTickFixture f) {
        List<Object> packets = new ArrayList<>();
        Object written;
        while ((written = f.fixture.channel.readOutbound()) != null) {
            if (!(written instanceof ByteBuf frame)) continue;
            try {
                int id = Wire.readVarInt(frame);
                Object packet = f.fixture.codec.read(PacketDirection.CLIENTBOUND, id, frame);
                if (packet != null) packets.add(packet);
            } finally {
                frame.release();
            }
        }
        return packets;
    }

    private static void hold(JavaTickFixture f, String item, int count) {
        f.player
                .getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack(item, count));
        f.player.getInventory().selectHotbarSlot(0);
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }
}
