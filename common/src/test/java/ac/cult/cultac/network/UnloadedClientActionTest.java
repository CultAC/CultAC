package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * ServerGamePacketListenerImpl#hasClientLoaded: while the player is dead, and after a login or respawn until
 * ServerboundPlayerLoadedPacket or 60 server ticks, the server drops a placement without acknowledging its sequence.
 */
class UnloadedClientActionTest {
    private static final BlockPos CLICKED = new BlockPos(2, JavaTickFixture.FLOOR_Y, 0);
    private static final BlockPos PLACED = CLICKED.relative(Direction.WEST);
    private static final Vec3 STANDING = new Vec3(0.69, JavaTickFixture.FLOOR_Y, 0.5);
    private static final int AIR = state("air");
    private static final int STONE = state("stone");

    @Test
    void placementWhileDeadIsWithheldAndRemovedAtItsAcknowledgement() throws Exception {
        try (var f = facingStone()) {
            f.player.blockActionReconciler.deathSent();
            var event = place(f, 1_000_000);
            assertTrue(event.isCancelled());

            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(PLACED));
            assertTrue(f.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void placementBeforeTheClientLoadsIsWithheldAndLaterCorrectionsStillSend() throws Exception {
        try (var f = facingStone()) {
            f.player.blockActionReconciler.deathSent();
            f.player.blockActionReconciler.serverRespawned(true);
            assertTrue(place(f, 1_000_000).isCancelled());
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(PLACED));

            for (int tick = 0; tick < 60; tick++) f.player.onEndOfTickEvent();
            assertTrue(f.player.blockActionReconciler.serverDropsActions(), "one tick past the server's timeout");
            f.player.onEndOfTickEvent();
            assertFalse(f.player.blockActionReconciler.serverDropsActions());

            assertFalse(place(f, 0).isCancelled());
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(PLACED));
            f.player.sendTransaction();
            f.player.compensatedWorld.handlePredictionConfirmation(0);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.blockActionReconciler.confirmServerAck(0);

            int sent = f.player.lastTransactionSent.get();
            var rejected = packet(0);
            f.player.compensatedWorld.advanceClientPredictionSequence(0);
            f.player.blockActionReconciler.rejectPlace(rejected);
            assertTrue(f.player.lastTransactionSent.get() > sent, "the correction is not held behind a dropped action");
        }
    }

    @Test
    void placementAfterTheLoadedPacketIsForwardedAndKeptUntilItsAcknowledgement() throws Exception {
        try (var f = facingStone()) {
            f.player.blockActionReconciler.deathSent();
            f.player.blockActionReconciler.serverRespawned(true);
            f.player.blockActionReconciler.loadReachedServer();
            int sent = f.player.lastTransactionSent.get();
            int sequence = f.player.compensatedWorld.clientPredictionSequence() + 1;

            assertFalse(place(f, sequence).isCancelled());
            assertEquals(sent, f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 20; tick++) f.tick(STANDING, true);
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(PLACED), "the placement waits for its ack");

            f.player.sendTransaction();
            f.player.compensatedWorld.handlePredictionConfirmation(sequence);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertTrue(f.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void placementAfterLoginWithTheLoadedPacketAheadIsForwarded() throws Exception {
        try (var f = facingStone()) {
            f.player.blockActionReconciler.loginSent();
            assertTrue(f.player.blockActionReconciler.serverDropsActions());
            f.player.blockActionReconciler.loadReachedServer();
            assertFalse(place(f, f.player.compensatedWorld.clientPredictionSequence() + 1).isCancelled());
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(PLACED));
        }
    }

    @Test
    void placementInFlightAtDeathIsRemovedWhenThePlayerRespawns() throws Exception {
        try (var f = facingStone()) {
            assertFalse(place(f, f.player.compensatedWorld.clientPredictionSequence() + 1).isCancelled());
            f.player.blockActionReconciler.deathSent();
            assertEquals(STONE, f.player.compensatedWorld.getBlockStateIdAt(PLACED));

            int sent = f.player.lastTransactionSent.get();
            f.player.blockActionReconciler.serverRespawned(true);
            assertTrue(f.player.lastTransactionSent.get() > sent, "the dropped placement is corrected");
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(AIR, f.player.compensatedWorld.getBlockStateIdAt(PLACED));
            assertTrue(f.player.compensatedWorld.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void placementAcknowledgedBeforeTheRespawnSendsNoCorrection() throws Exception {
        try (var f = facingStone()) {
            int sequence = f.player.compensatedWorld.clientPredictionSequence() + 1;
            assertFalse(place(f, sequence).isCancelled());
            f.player.blockActionReconciler.deathSent();
            f.player.sendTransaction();
            f.player.compensatedWorld.handlePredictionConfirmation(sequence);

            int sent = f.player.lastTransactionSent.get();
            f.player.blockActionReconciler.serverRespawned(true);
            assertEquals(sent, f.player.lastTransactionSent.get());
        }
    }

    @Test
    void respawnIntoAnotherLevelSendsNoBlockCorrection() throws Exception {
        try (var f = facingStone()) {
            assertFalse(place(f, f.player.compensatedWorld.clientPredictionSequence() + 1).isCancelled());
            f.player.blockActionReconciler.deathSent();
            int sent = f.player.lastTransactionSent.get();
            f.player.blockActionReconciler.serverRespawned(false);
            assertEquals(sent, f.player.lastTransactionSent.get());
        }
    }

    private static JavaTickFixture facingStone() throws Exception {
        var f = new JavaTickFixture();
        f.player.setExperimentalChecks(false);
        f.player.compensatedWorld.updateBlock(CLICKED, STONE);
        f.place(STANDING, Vec3.ZERO);
        f.player.xRot = -90;
        f.player.yRot = 36;
        f.tick(STANDING, true);
        f.player
                .getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack("minecraft:stone", 64));
        f.player.getInventory().selectHotbarSlot(0);
        f.player.packetStateData.clientSidePosition = STANDING;
        return f;
    }

    private static ServerboundUseItemOn packet(int wireSequence) {
        return new ServerboundUseItemOn(
                Hand.MAIN_HAND, CLICKED, Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, wireSequence);
    }

    private static PacketReceiveEvent<ServerboundUseItemOn> place(JavaTickFixture f, int wireSequence) {
        var packet = packet(wireSequence);
        var event = f.event(ServerboundPackets.USE_ITEM_ON, packet);
        f.listener.onUseItemOn(event, f.player, packet);
        return event;
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }
}
