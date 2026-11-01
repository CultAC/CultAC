package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.breaking.AirLiquidBreak;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.inventory.Inventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#startAttack stabs instead of digging while a piercing weapon is in hand, and
 * MultiPlayerGameMode#startDestroyBlock sends START without first sending the carried slot. A START
 * with a spear in the server-side hand proves a spear only once the client's slot is known.
 */
class AirLiquidBreakCarriedSlotTest {
    private static final int SPEAR_SLOT = 0;
    private static final int PICKAXE_SLOT = 1;
    private static final int SECOND_SPEAR_SLOT = 2;
    private static final BlockPos STONE = new BlockPos(0, 63, 0);

    @BeforeAll
    static void registerVerboseTags() {
        VerboseCodecs.ensureRegistered();
    }

    @Test
    void aSlotPacketLaterInTheStartTickCarriesTheSlotTheClientDugWith() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.selectSlot(PICKAXE_SLOT);
            f.swing();
            f.tickEnd();
            f.swing();
            f.tickEnd();
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void aQuickTapSettlesOnTheNextTicksSlotPacketPastOutOfTickPackets() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.dig(PlayerAction.ABORT_DESTROY_BLOCK);
            f.tickEnd();
            f.receive(ServerboundPackets.PONG, new ServerboundPong(7));
            f.selectSlot(PICKAXE_SLOT);
            f.swing();
            f.tickEnd();
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void aStartWithTheSpearStillCarriedFlagsOnTheNextTicksFirstPacket() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.dig(PlayerAction.ABORT_DESTROY_BLOCK);
            f.tickEnd();
            f.receive(ServerboundPackets.PONG, new ServerboundPong(7));
            assertEquals(0, f.check.getViolations());
            f.swing();
            assertEquals(1, f.check.getViolations());
            f.tickEnd();
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aStartWithTheSpearStillCarriedFlagsAtTheNextTickEndWhenNothingElseArrives() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.tickEnd();
            assertEquals(0, f.check.getViolations());
            f.tickEnd();
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aPacketSentAfterTheCarriedSlotSettlesTheStartTickAtOnce() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.receive(ServerboundPackets.USE_ITEM, new ServerboundUseItem(Hand.MAIN_HAND, 2, 0, 0));
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void severalStartsInOneTickAreDecidedTogether() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.tickEnd();
            assertEquals(0, f.check.getViolations());
            f.swing();
            assertEquals(2, f.check.getViolations());
        }
    }

    @Test
    void startsWithTheirTickEndsWithheldEachFlagOnceTheSlotIsKnown() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            for (int i = 0; i < 3; i++) {
                f.dig(PlayerAction.START_DESTROY_BLOCK);
                f.swing();
            }
            assertEquals(0, f.check.getViolations());
            f.receive(ServerboundPackets.USE_ITEM, new ServerboundUseItem(Hand.MAIN_HAND, 9, 0, 0));
            assertEquals(3, f.check.getViolations());
        }
    }

    @Test
    void aSlotPacketForAnotherSpearKeepsTheStartASpearDig() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.selectSlot(SECOND_SPEAR_SLOT);
            assertEquals(1, f.check.getViolations());
            f.tickEnd();
            f.tickEnd();
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void severalStartsInOneTickSettleOnOneSlotPacket() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.selectSlot(PICKAXE_SLOT);
            f.tickEnd();
            f.tickEnd();
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void aStopWithTheSpearCarriedFlagsAtOnce() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.STOP_DESTROY_BLOCK);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aStopWithTheSpearCarriedFlagsOnceForThePendingStartItCompletes() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.tickEnd();
            f.dig(PlayerAction.STOP_DESTROY_BLOCK);
            assertEquals(1, f.check.getViolations());
            f.swing();
            f.tickEnd();
            f.tickEnd();
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aStopWithTheSpearCarriedFlagsTheOtherPendingStarts() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.dig(PlayerAction.STOP_DESTROY_BLOCK);
            assertEquals(2, f.check.getViolations());
        }
    }

    /** The new MultiPlayerGameMode starts carriedIndex at 0, so the pickaxe slot picked before the login is never sent. */
    @Test
    void aLoginDropsAStartWhoseSlotTheOldGameModeNeverSent() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture()) {
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.tickEnd();
            f.login();
            f.tickEnd();
            f.tickEnd();
            assertEquals(0, f.check.getViolations());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordReceiveFixture record = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final CultPlayer player = record.player;
        final AirLiquidBreak check;
        int sequence;

        Fixture() {
            record.user.bind(mock(PlatformPlayer.class));
            holdTheSpear();
            check = player.checkManager.getCheck(AirLiquidBreak.class);
        }

        private void holdTheSpear() {
            SimItemStack spear = OfflineCultTestBootstrap.item("minecraft:iron_spear");
            assertTrue(spear.components().has("minecraft:piercing_weapon"));
            var storage = player.getInventory().inventory.getInventoryStorage();
            storage.setItem(Inventory.HOTBAR_OFFSET + SPEAR_SLOT, spear);
            storage.setItem(
                    Inventory.HOTBAR_OFFSET + SECOND_SPEAR_SLOT, OfflineCultTestBootstrap.item("minecraft:iron_spear"));
            storage.setItem(
                    Inventory.HOTBAR_OFFSET + PICKAXE_SLOT, OfflineCultTestBootstrap.item("minecraft:iron_pickaxe"));
            player.getInventory().selectHotbarSlot(SPEAR_SLOT);
        }

        /** A reconfiguration login, applied once its ping returns; the server then resends the hotbar and slot. */
        void login() {
            int ping = ItemUseTestSupport.login(player, GameMode.SURVIVAL);
            for (int id = player.lastTransactionReceived.get() + 1; id <= ping; id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
            holdTheSpear();
        }

        /** The early stage runs before PacketPlayerDigging hands the action to block-break listeners. */
        void dig(PlayerAction action) {
            var packet = new ServerboundPlayerAction(action, STONE, Direction.UP, ++sequence);
            receive(ServerboundPackets.PLAYER_ACTION, packet);
            player.checkManager.onBlockBreak(new BlockBreak(
                    player,
                    STONE,
                    Direction.UP,
                    Direction.UP.ordinal(),
                    action,
                    sequence,
                    BlockIds.STONE.defaultState()));
        }

        void swing() {
            receive(ServerboundPackets.SWING, ServerboundSwing.PUNCH);
        }

        /** CompensatedInventory applies the slot after the early stage. */
        void selectSlot(int slot) {
            receive(ServerboundPackets.SET_CARRIED_ITEM, new ServerboundSetCarriedItem(slot));
            player.getInventory().selectHotbarSlot(slot);
        }

        void tickEnd() {
            receive(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue());
        }

        <T extends ServerboundPacket> void receive(PacketType<T> type, T packet) {
            player.checkManager.dispatchEarlyReceive(
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, type, packet));
        }

        @Override
        public void close() {
            record.close();
        }
    }
}
