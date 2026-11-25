package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The coordinate and BlockPos forms of a cell scan visit the same cells in the same order and stop at the same one. */
class CollisionsCellScanTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final String[] PALETTE = {
        "minecraft:air",
        "minecraft:stone",
        "minecraft:water",
        "minecraft:lava",
        "minecraft:stone_slab",
        "minecraft:oak_fence",
        "minecraft:moving_piston",
        "minecraft:kelp",
        "minecraft:soul_sand",
    };

    private record Cell(int state, int x, int y, int z, ClientFluidQueries.Sample fluid) {}

    @Test
    void bothFormsVisitTheSameCells() throws Exception {
        Random random = new Random(7);
        try (var f = new JavaTickFixture()) {
            for (int x = -3; x <= 3; x++) {
                for (int y = FLOOR_Y - 2; y <= FLOOR_Y + 3; y++) {
                    for (int z = -3; z <= 3; z++) {
                        String block = PALETTE[random.nextInt(PALETTE.length)];
                        f.player.compensatedWorld.updateBlock(
                                new BlockPos(x, y, z), BLOCKS.block(block).defaultState());
                    }
                }
            }
            for (int i = 0; i < 500; i++) {
                double minX = random.nextDouble() * 6 - 3, minY = FLOOR_Y - 2 + random.nextDouble() * 5;
                double minZ = random.nextDouble() * 6 - 3;
                SimpleCollisionBox box = new SimpleCollisionBox(
                        minX,
                        minY,
                        minZ,
                        minX + random.nextDouble() * 2,
                        minY + random.nextDouble() * 2,
                        minZ + random.nextDouble() * 2);
                int stopAt = random.nextInt(30) - 5;

                List<Cell> positioned = new ArrayList<>();
                boolean positionedResult = Collisions.hasState(f.player, box, (state, pos) -> {
                    positioned.add(new Cell(
                            state,
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            ClientFluidQueries.sample(f.player, state, pos)));
                    return positioned.size() == stopAt;
                });
                List<Cell> at = new ArrayList<>();
                boolean atResult = Collisions.hasState(f.player, box, (state, x, y, z) -> {
                    at.add(new Cell(state, x, y, z, ClientFluidQueries.sample(f.player, state, x, y, z)));
                    return at.size() == stopAt;
                });

                assertEquals(positioned, at, box.toString());
                assertEquals(positionedResult, atResult, box.toString());
            }
        }
    }
}
