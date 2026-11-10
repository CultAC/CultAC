package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ShulkerData;
import org.junit.Test;

/** ShulkerBoxBlockEntity#triggerEvent: the event type is 1 and its parameter is the number of viewers. */
public class BedrockShulkerBoxEventTest {
    private static final BlockPos BOX = new BlockPos(3, 64, 5);
    private static final int SHULKER_BOX =
            DataTables.defaults().registry().block("minecraft:shulker_box").defaultState();

    @Test
    public void theFirstViewerOpensTheBoxAndTheLastOneClosesIt() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(BOX.getX() >> 4, BOX.getZ() >> 4);
            player.compensatedWorld.updateBlock(BOX.getX(), BOX.getY(), BOX.getZ(), SHULKER_BOX);

            blockEvent(player, 1, 1);
            assertFalse("one viewer opens the lid", onlyBox(player).isClosing());

            blockEvent(player, 1, 2);
            assertFalse("a second viewer leaves the lid opening", onlyBox(player).isClosing());

            blockEvent(player, 1, 1);
            assertFalse("the first of two viewers leaving keeps the lid open", onlyBox(player).isClosing());

            blockEvent(player, 1, 0);
            assertTrue("no viewer closes the lid", onlyBox(player).isClosing());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void anotherEventTypeLeavesTheLidAlone() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(BOX.getX() >> 4, BOX.getZ() >> 4);
            player.compensatedWorld.updateBlock(BOX.getX(), BOX.getY(), BOX.getZ(), SHULKER_BOX);

            blockEvent(player, 0, 1);
            assertTrue(player.compensatedWorld.openShulkerBoxes.isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static ShulkerData onlyBox(CultPlayer player) {
        assertEquals(1, player.compensatedWorld.openShulkerBoxes.size());
        return player.compensatedWorld.openShulkerBoxes.iterator().next();
    }

    private static void blockEvent(CultPlayer player, int action, int parameter) {
        var packet = new ClientboundBlockEvent(
                BOX, action, parameter, DataTables.defaults().registry().blockIndex(SHULKER_BOX));
        new PacketBlockAction().onBlockEvent(null, player, packet);
    }
}
