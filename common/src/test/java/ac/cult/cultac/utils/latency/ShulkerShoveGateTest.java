package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.cultac.protocol.value.BlockPos;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Whenever the gate says no shove can come, shulkerShoves adds none, for both packet orders and tick rates. */
class ShulkerShoveGateTest {
    @Test
    void aClosedGateMeansNoShove() {
        var registry = DataTables.defaults().registry();
        int shulker = registry.block("minecraft:shulker_box").defaultState();
        int chest = registry.block("minecraft:chest").defaultState();
        var entities = new CompensatedBlockEntities(InteractionRegistries::defaults);
        var random = new Random(9);
        int[] opened = new int[2];
        for (int step = 0; step < 20000; step++) {
            var pos = new BlockPos(random.nextInt(8), random.nextInt(4), random.nextInt(8));
            switch (random.nextInt(9)) {
                case 0, 1 -> entities.blockEvent(pos, shulker, 1, 1);
                case 2 -> entities.blockEvent(pos, shulker, 1, 0);
                case 3 -> entities.snapshot(pos, chest);
                case 4, 5 -> entities.tick();
                case 6 -> entities.skipTick();
                case 7 -> entities.positionSet();
                default -> {
                    if (random.nextInt(10) == 0) entities.forgetChunk(0, 0);
                    else if (random.nextInt(50) == 0) entities.clear();
                }
            }
            for (int order = 0; order < 4; order++) {
                boolean next = (order & 1) != 0, runsNormally = (order & 2) != 0;
                var shoves = new ArrayList<CompensatedBlockEntities.ShulkerShove>();
                entities.shulkerShoves(next, runsNormally, shoves);
                if (!entities.mayShove(next, runsNormally)) assertTrue(shoves.isEmpty(), step + " order " + order);
                else if (!shoves.isEmpty()) opened[next ? 1 : 0]++;
            }
        }
        assertTrue(opened[0] > 100 && opened[1] > 100, opened[0] + " " + opened[1]);
    }
}
