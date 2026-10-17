package ac.cult.cultac.network;

import static ac.cult.cultac.network.ItemUseTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsH;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** BlockStatePredictionHandler numbers every USE_ITEM, USE_ITEM_ON and dig of one ClientLevel from 1. */
class BadPacketsHSequenceTest {
    private RecordConsumerServices services;
    private RecordReceiveFixture fixture;
    private CultPlayer player;

    @BeforeEach
    void connect() throws Exception {
        services = new RecordConsumerServices();
        fixture = ItemUseTestSupport.fixture();
        player = fixture.player;
        player.gamemode = GameMode.SURVIVAL;
        player.food = 20;
        player.entityID = 7;
        player.setExperimentalChecks(true);
        check().setEnabled(true);
    }

    @AfterEach
    void disconnect() throws Exception {
        fixture.close();
        services.close();
    }

    @Test
    void aLoginStartsANewClientLevelAtSequenceOne() {
        useThrough(41);
        login(player, GameMode.SURVIVAL);
        assertFalse(use(Hand.MAIN_HAND, 1));
        assertEquals(0, check().violations);
        assertTrue(use(Hand.MAIN_HAND, 3), "a skipped id still cancels");
    }

    @Test
    void useItemOnFromEveryHandFacingABlockAdvancesTheSequence() {
        setOffhand(player, ItemUtil.modelItems().stack("minecraft:bread", 4));
        useThrough(12);
        // Minecraft.startUseItem: an empty main hand and an offhand food both use the block, then the food is used.
        assertFalse(useOn(Hand.MAIN_HAND, 13));
        assertFalse(useOn(Hand.OFF_HAND, 14));
        assertFalse(use(Hand.OFF_HAND, 15));
        assertEquals(0, check().violations);
        assertTrue(use(Hand.OFF_HAND, 17), "a skipped id still cancels");
    }

    @Test
    void aDimensionRespawnAcceptsTheNewLevelBeforeItsPostPing() {
        useThrough(5);
        int post = respawn("minecraft:the_nether");
        assertFalse(use(Hand.MAIN_HAND, 6), "sent before the client handled the Respawn");
        assertFalse(use(Hand.MAIN_HAND, 1), "the new ClientLevel, before the post ping");
        acknowledge(player, post);
        assertFalse(use(Hand.MAIN_HAND, 2));
        assertTrue(use(Hand.MAIN_HAND, 7));
    }

    @Test
    void aPlacementIsCountedOnce() {
        int stone = holdStoneFacingABlock();
        useThrough(4);
        assertFalse(useOn(Hand.MAIN_HAND, 5));
        assertEquals(stone, player.compensatedWorld.getBlockStateIdAt(new BlockPos(1, 64, 0)), "placed");
        assertEquals(1, player.getInventory().getHeldItem().getCount());
        assertFalse(useOn(Hand.OFF_HAND, 6));
        assertFalse(use(Hand.MAIN_HAND, 7));
        assertEquals(0, check().violations);
    }

    @Test
    void aPlacementIdIsNotSkippedInTheNextLevel() {
        holdStoneFacingABlock();
        useThrough(4);
        assertFalse(useOn(Hand.MAIN_HAND, 5));
        login(player, GameMode.SURVIVAL);
        useThrough(4);
        assertFalse(useOn(Hand.OFF_HAND, 5));
        assertFalse(use(Hand.MAIN_HAND, 6));
        assertEquals(0, check().violations);
    }

    @Test
    void dimensionChangeBeforePredictionKeepsNewIds() {
        int post = respawn("minecraft:the_nether");
        assertFalse(use(Hand.MAIN_HAND, 1), "the old and the new level both expect 1");
        assertFalse(use(Hand.MAIN_HAND, 2));
        acknowledge(player, post);
        assertFalse(use(Hand.MAIN_HAND, 3));
        assertEquals(0, check().violations);
        assertTrue(use(Hand.MAIN_HAND, 5), "a skipped id after the proof still cancels");
    }

    @Test
    void anOldLevelIdBeforeTheProofLeavesTheNewLevelUnused() {
        int post = respawn("minecraft:the_nether");
        assertFalse(use(Hand.MAIN_HAND, 1));
        acknowledge(player, post);
        assertFalse(use(Hand.MAIN_HAND, 1), "the first id of the new level");
        assertFalse(use(Hand.MAIN_HAND, 2));
        assertEquals(0, check().violations);
    }

    @Test
    void anOldAndANewLevelIdOneBeforeTheProof() {
        int post = respawn("minecraft:the_nether");
        assertFalse(use(Hand.MAIN_HAND, 1), "old level");
        assertFalse(use(Hand.MAIN_HAND, 1), "new level");
        acknowledge(player, post);
        assertFalse(use(Hand.MAIN_HAND, 2));
        assertEquals(0, check().violations);
    }

    @Test
    void twoPendingLevelsKeepEveryHistory() {
        int nether = respawn("minecraft:the_nether");
        int end = respawn("minecraft:the_end");
        useThrough(2);
        acknowledge(player, nether);
        assertFalse(use(Hand.MAIN_HAND, 1), "the end's first id");
        acknowledge(player, end);
        assertFalse(use(Hand.MAIN_HAND, 2));
        assertEquals(0, check().violations);
        assertTrue(use(Hand.MAIN_HAND, 4));
    }

    @Test
    void ambiguousLevelsFlagWithoutCancelling() {
        respawn("minecraft:the_nether");
        assertFalse(use(Hand.MAIN_HAND, 1));
        assertFalse(use(Hand.MAIN_HAND, 5));
        assertEquals(1, check().violations);
    }

    /** Holds two stone facing the west side of a stone block at (2, 64, 0); returns its state. */
    private int holdStoneFacingABlock() {
        var stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
        player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
        player.compensatedWorld.updateBlock(2, 64, 0, stone);
        player.x = 0.5;
        player.y = 64;
        player.z = 0.5;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, 0.5, 64, 0.5);
        player.xRot = -90;
        player.yRot = 36;
        hold("minecraft:stone");
        player.getInventory().getHeldItem().setCount(2);
        return stone;
    }

    private void hold(String item) {
        player.getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack(item, 1));
        player.getInventory().selectHotbarSlot(0);
    }

    private int respawn(String dimension) {
        return ItemUseTestSupport.respawn(player, dimension, GameMode.SURVIVAL);
    }

    private BadPacketsH check() {
        return player.checkManager.getCheck(BadPacketsH.class);
    }

    /** Main-hand uses 1 to {@code last}, none of them cancelled. */
    private void useThrough(int last) {
        for (int sequence = 1; sequence <= last; sequence++) assertFalse(use(Hand.MAIN_HAND, sequence));
    }

    /** Returns whether the USE was cancelled. */
    private boolean use(Hand hand, int sequence) {
        player.packetStateData.clientSidePosition = new Vec3(player.x, player.y, player.z);
        var packet = new ServerboundUseItem(hand, sequence, player.xRot, player.yRot);
        var event = new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.USE_ITEM, packet);
        new CheckManagerListener().onUseItem(event, player, packet);
        return event.isCancelled();
    }

    private boolean useOn(Hand hand, int sequence) {
        player.packetStateData.clientSidePosition = new Vec3(player.x, player.y, player.z);
        var packet = new ServerboundUseItemOn(
                hand, new BlockPos(2, 64, 0), Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, sequence);
        var event =
                new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.USE_ITEM_ON, packet);
        new CheckManagerListener().onUseItemOn(event, player, packet);
        return event.isCancelled();
    }
}
