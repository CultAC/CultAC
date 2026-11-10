package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.checks.Phase;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase leaves blocks a piston is moving to Simulation and keeps every other block. */
class PhaseMovingBlockTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();

    @Test
    void onlyTheMovingBlocksCollisionIsLeftToSimulation() throws Exception {
        try (var f = new JavaTickFixture()) {
            BlockPos piston = new BlockPos(3, FLOOR_Y, 3);
            int sticky = REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "up");
            f.player.compensatedWorld.updateBlock(piston, sticky);
            f.player.compensatedWorld.updateBlock(piston.above(), BlockIds.SLIME_BLOCK.defaultState());
            // A static block beside the slime's path.
            BlockPos stone = new BlockPos(4, FLOOR_Y + 2, 3);
            f.player.compensatedWorld.updateBlock(stone, BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var packet = new ClientboundBlockEvent(piston, 0, 1, REGISTRY.blockIndex(sticky));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
            new PacketBlockAction().onBlockEvent(event, f.player, packet);
            new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();

            List<SimpleCollisionBox> boxes = new ArrayList<>();
            Collisions.getCollisionBoxes(
                    f.player, new SimpleCollisionBox(3, FLOOR_Y + 1, 3, 5, FLOOR_Y + 3, 4), boxes, false, FLOOR_Y);
            boolean moving = false, staticStone = false;
            for (SimpleCollisionBox box : boxes) {
                if (!(box instanceof VoxelShapeCollisionBox collider)) continue;
                boolean fromStone = collider.offsetX() == stone.getX()
                        && collider.offsetY() == stone.getY()
                        && collider.offsetZ() == stone.getZ();
                if (fromStone) {
                    staticStone = true;
                    assertTrue(!Phase.isMovingBlock(f.player, box), "the stone stays Phase's: " + box);
                } else if (Phase.isMovingBlock(f.player, box)) {
                    moving = true;
                }
            }
            assertTrue(staticStone, "the scan found the stone: " + boxes);
            assertTrue(moving, "the scan found the moving slime: " + boxes);
        }
    }
}
