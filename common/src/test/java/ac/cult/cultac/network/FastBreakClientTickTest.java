package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.breaking.FastBreak;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Packets arrive with no wall time between them, as after a client stall, so every expectation rests on
 * the client_tick_end count. MultiPlayerGameMode#continueDestroyBlock advances the destroy progress once
 * per client tick, and only after the destroyDelay of five ticks set by its STOP has run out.
 */
class FastBreakClientTickTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static final BlockPos ELSEWHERE = new BlockPos(1, 64, 2);
    // A diamond pickaxe on obsidian needs 14 ticks at mining efficiency 100 and 2 ticks at 1024.
    private static final double EFFICIENCY_FOR_14_TICKS = 100.0;
    private static final double EFFICIENCY_FOR_2_TICKS = 1024.0;
    private static final int TICKS_14 = 14;
    private static final int TICKS_2 = 2;
    private static final int DESTROY_DELAY = 5;
    // Efficiency V: 5 * 5 + 1
    private static final double EFFICIENCY_V = 26.0;

    @BeforeAll
    static void registerVerboseTags() {
        VerboseCodecs.ensureRegistered();
    }

    @Test
    void heldBreaksDeliveredInOneBurstMatchTheClientTickCount() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_FOR_14_TICKS)) {
            f.freshBreak(0, TICKS_14);
            for (int i = 0; i < 3; i++) {
                f.heldBreak(TICKS_14);
            }
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void aFreshClickProgressesInItsStartTick() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_FOR_14_TICKS)) {
            for (int i = 0; i < 25; i++) {
                f.freshBreak(0, TICKS_14);
                f.idleTicks(DESTROY_DELAY);
            }
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void aFreshClickOneTickAfterAStopWaitsOutTheDestroyDelayBeforeItsStop() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_FOR_2_TICKS)) {
            f.freshBreak(0, TICKS_2);
            for (int i = 0; i < 4; i++) {
                f.freshBreak(DESTROY_DELAY, TICKS_2);
            }
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void stopsThatSkipTheDestroyDelayAccumulateUntilTheyFlag() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_FOR_14_TICKS)) {
            f.freshBreak(0, TICKS_14);
            for (int i = 0; i < 4; i++) {
                f.freshBreak(0, TICKS_14);
            }
            assertEquals(0, f.check.getViolations());
            f.freshBreak(0, TICKS_14);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void heldStopsOneTickEarlyAccumulateUntilTheyFlag() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_FOR_2_TICKS)) {
            f.freshBreak(0, TICKS_2);
            for (int i = 0; i < 20; i++) {
                f.heldBreak(TICKS_2 - 1);
            }
            assertEquals(0, f.check.getViolations());
            f.heldBreak(TICKS_2 - 1);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aLoginStartsTheNextBreakWithoutTheOldDestroyDelay() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_FOR_14_TICKS)) {
            f.freshBreak(0, TICKS_14);
            for (int i = 0; i < 5; i++) {
                f.login();
                f.freshBreak(0, TICKS_14);
            }
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void aSecondSwingInAHeldStartTickGainsAtMostOneTick() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_FOR_14_TICKS)) {
            f.freshBreak(0, TICKS_14);
            for (int i = 0; i < 20; i++) {
                f.heldBreakSwingingTwice(TICKS_14 - 2);
            }
            assertEquals(0, f.check.getViolations());
            f.heldBreakSwingingTwice(TICKS_14 - 2);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void theStopTickProgressesWithTheBlockTheClientReadsInThatTick() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_V)) {
            f.breakPredictedBarrierReplacedBy(f.mushroomStem);
            assertEquals(0, f.check.getViolations());
        }
    }

    @Test
    void aStopOnABarrierStillFlags() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_V)) {
            f.breakPredictedBarrierReplacedBy(f.barrier);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aStopAwayFromTheTargetDoesNotCountItsBlock() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_V)) {
            f.player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:netherite_axe"));
            f.player.compensatedWorld.updateBlock(ELSEWHERE, f.mushroomStem);
            f.dig(PlayerAction.START_DESTROY_BLOCK);
            f.swing();
            f.swing();
            f.tickEnd();
            f.dig(PlayerAction.STOP_DESTROY_BLOCK, ELSEWHERE);
            f.swing();
            f.tickEnd();
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aServerBlockTheClientHasNotReceivedDoesNotProgressTheStop() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_V)) {
            f.breakPredictedBarrierReplacedLateBy(f.mushroomStem, false);
            assertEquals(1, f.check.getViolations());
        }
    }

    @Test
    void aServerBlockTheClientReceivedBeforeTheStopProgressesIt() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new Fixture(EFFICIENCY_V)) {
            f.breakPredictedBarrierReplacedLateBy(f.mushroomStem, true);
            assertEquals(0, f.check.getViolations());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordReceiveFixture record = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final CultPlayer player = record.player;
        final FastBreak check;
        final int obsidian = BlockIds.OBSIDIAN.defaultState();
        final int barrier = state("minecraft:barrier");
        final int mushroomStem = state("minecraft:mushroom_stem");
        final double miningEfficiency;

        Fixture(double miningEfficiency) {
            this.miningEfficiency = miningEfficiency;
            record.user.bind(mock(PlatformPlayer.class));
            standAtTheBlock();
            check = player.checkManager.getCheck(FastBreak.class);
        }

        private void standAtTheBlock() {
            player.packetStateData.packetPlayerOnGround = true;
            player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:diamond_pickaxe"));
            player.compensatedEntities.updateAttributes(
                    player.entityID,
                    List.of(new AttributeSnapshot("minecraft:mining_efficiency", miningEfficiency, List.of())));
            player.compensatedWorld.ensureValidationChunkLoaded(TARGET.getX() >> 4, TARGET.getZ() >> 4);
            player.compensatedWorld.updateBlock(TARGET, obsidian);
        }

        private static int state(String name) {
            return DataTables.defaults().registry().block(name).defaultState();
        }

        void setBlock(int state) {
            player.compensatedWorld.updateBlock(TARGET, state);
        }

        /**
         * An Efficiency V netherite axe starts on the barrier the client predicted from its own placement
         * (hardness -1, no progress); the server's block arrives before the next tick, whose
         * continueDestroyBlock adds that block's progress and sends STOP.
         */
        void breakPredictedBarrierReplacedBy(int serverBlock) {
            player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:netherite_axe"));
            setBlock(barrier);
            dig(PlayerAction.START_DESTROY_BLOCK);
            swing();
            swing();
            tickEnd();
            setBlock(serverBlock);
            dig(PlayerAction.STOP_DESTROY_BLOCK);
            swing();
            tickEnd();
        }

        /**
         * The server sends its block behind a transaction; the client reads it in the STOP tick only when it
         * answered that transaction before the tick.
         */
        void breakPredictedBarrierReplacedLateBy(int serverBlock, boolean answeredBeforeStop) {
            player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:netherite_axe"));
            setBlock(barrier);
            dig(PlayerAction.START_DESTROY_BLOCK);
            swing();
            swing();
            tickEnd();
            int transaction = player.lastTransactionSent.incrementAndGet();
            player.compensatedWorld.queueServerBlockUpdate(
                    TARGET.getX(), TARGET.getY(), TARGET.getZ(), serverBlock, transaction, true);
            if (answeredBeforeStop) answer(transaction);
            dig(PlayerAction.STOP_DESTROY_BLOCK);
            swing();
            tickEnd();
            answer(transaction);
        }

        void answer(int transaction) {
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        /** A LOGIN the client processes before its next tick, into a level with the same block in reach. */
        void login() {
            int ping = ItemUseTestSupport.login(player, GameMode.SURVIVAL);
            for (int id = player.lastTransactionReceived.get() + 1; id <= ping; id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
            standAtTheBlock();
        }

        /**
         * Minecraft#startAttack sends START and swings, then continueAttack swings again after a tick of
         * progress or of destroyDelay. The STOP tick swings once more after the STOP.
         */
        void freshBreak(int destroyDelay, int stopAfterTicks) {
            dig(PlayerAction.START_DESTROY_BLOCK);
            swing();
            swing();
            tickEnd();
            idleTicks(destroyDelay + stopAfterTicks - 2);
            dig(PlayerAction.STOP_DESTROY_BLOCK);
            swing();
            tickEnd();
        }

        /**
         * The attack key stays down: five ticks of destroyDelay, then continueDestroyBlock sends START
         * for the next block in its own tick and progresses from the tick after.
         */
        void heldBreak(int stopAfterTicks) {
            idleTicks(DESTROY_DELAY);
            dig(PlayerAction.START_DESTROY_BLOCK);
            swing();
            tickEnd();
            idleTicks(stopAfterTicks - 1);
            dig(PlayerAction.STOP_DESTROY_BLOCK);
            swing();
            tickEnd();
        }

        /** A held break whose START tick carries a second swing that Minecraft#continueAttack did not send. */
        void heldBreakSwingingTwice(int stopAfterTicks) {
            idleTicks(DESTROY_DELAY);
            dig(PlayerAction.START_DESTROY_BLOCK);
            swing();
            swing();
            tickEnd();
            idleTicks(stopAfterTicks - 1);
            dig(PlayerAction.STOP_DESTROY_BLOCK);
            swing();
            tickEnd();
        }

        void idleTicks(int ticks) {
            for (int i = 0; i < ticks; i++) {
                swing();
                tickEnd();
            }
        }

        void dig(PlayerAction action) {
            dig(action, TARGET);
        }

        /** PacketPlayerDigging reads the block from the client's compensated world at the packet's position. */
        void dig(PlayerAction action, BlockPos position) {
            int block = player.compensatedWorld.getBlockStateIdAt(position);
            check.onBlockBreak(new BlockBreak(
                    player, position, Direction.DOWN, Direction.DOWN.get3DDataValue(), action, 1, block));
        }

        void swing() {
            receive(ServerboundPackets.SWING, ServerboundSwing.PUNCH);
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
