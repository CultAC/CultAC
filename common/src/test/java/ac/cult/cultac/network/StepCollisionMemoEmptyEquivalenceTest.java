package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.checks.StepCollisionMemo;
import ac.cult.cultac.checks.impl.prediction.checks.StepCollisionMemoAccess;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The memo's block-only scan answers as Collisions#isEmpty does at every query of an open memo: boxes that differ in
 * one bound, a moved entity bottom over scaffolding and the step memo's own entries on the same boxes never share an
 * answer.
 */
class StepCollisionMemoEmptyEquivalenceTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final String[] PALETTE = {
        "minecraft:air",
        "minecraft:air",
        "minecraft:stone",
        "minecraft:stone_slab",
        "minecraft:scaffolding",
        "minecraft:oak_fence",
        "minecraft:snow",
        "minecraft:soul_sand",
    };

    @Test
    void openMemoAnswersAsTheScan() throws Exception {
        SplittableRandom random = new SplittableRandom(17);
        List<String> mismatches = new ArrayList<>();
        int hits = 0, flips = 0;
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            StepCollisionMemo memo = new StepCollisionMemo();
            for (int world = 0; world < 60; world++) {
                for (int x = -3; x <= 3; x++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
                        for (int z = -3; z <= 3; z++) {
                            String block = PALETTE[random.nextInt(PALETTE.length)];
                            player.compensatedWorld.updateBlock(
                                    new BlockPos(x, y, z), BLOCKS.block(block).defaultState());
                        }
                    }
                }
                memo.open(true);
                List<SimpleCollisionBox> boxes = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    double minX = random.nextDouble(-2.5, 2.0), minY = FLOOR_Y + random.nextDouble(-0.2, 2.0);
                    double minZ = random.nextDouble(-2.5, 2.0);
                    SimpleCollisionBox box = new SimpleCollisionBox(
                            minX,
                            minY,
                            minZ,
                            minX + random.nextDouble(0.1, 1.0),
                            minY + random.nextDouble(0.1, 2.0),
                            minZ + random.nextDouble(0.1, 1.0));
                    boxes.add(box);
                    boxes.add(oneBoundMoved(box, random));
                }
                for (int round = 0; round < 3; round++) {
                    // Scaffolding's shape reads the entity bottom, so the second round moves it.
                    player.y = round == 1 ? FLOOR_Y + random.nextDouble(-1.0, 3.0) : FLOOR_Y;
                    for (SimpleCollisionBox box : boxes) {
                        boolean want = Collisions.isEmpty(player, box, player.y);
                        if (round == 2) {
                            boolean below = StepCollisionMemoAccess.collidesDirectlyBelow(memo, player, box);
                            if (below == want) flips++;
                        }
                        boolean got = StepCollisionMemoAccess.isEmpty(memo, player, box);
                        if (round > 0) hits++;
                        if (want != got) mismatches.add(world + "/" + round + " " + box + ": " + want + " != " + got);
                    }
                }
                memo.close();
                player.y = FLOOR_Y;
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(hits > 0 && flips > 0, hits + " hits, " + flips + " opposite step answers");
    }

    @Test
    void closedMemoScansDirectly() throws Exception {
        try (var f = new JavaTickFixture()) {
            StepCollisionMemo memo = new StepCollisionMemo();
            SimpleCollisionBox box = new SimpleCollisionBox(0.2, FLOOR_Y - 0.5, 0.2, 0.8, FLOOR_Y + 0.5, 0.8);
            assertEquals(Collisions.isEmpty(f.player, box), StepCollisionMemoAccess.isEmpty(memo, f.player, box));
            int air = BLOCKS.block("minecraft:air").defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), air);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), air);
            assertEquals(Collisions.isEmpty(f.player, box), StepCollisionMemoAccess.isEmpty(memo, f.player, box));
        }
    }

    @Test
    void reopenedMemoSeesABlockPlacedWhileClosed() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            StepCollisionMemo memo = new StepCollisionMemo();
            BlockPos pos = new BlockPos(0, FLOOR_Y, 0);
            SimpleCollisionBox box = new SimpleCollisionBox(0.2, FLOOR_Y + 0.1, 0.2, 0.8, FLOOR_Y + 0.9, 0.8);
            player.compensatedWorld.updateBlock(
                    pos, BLOCKS.block("minecraft:air").defaultState());
            memo.open(true);
            assertTrue(StepCollisionMemoAccess.isEmpty(memo, player, box));
            memo.close();
            player.compensatedWorld.updateBlock(
                    pos, BLOCKS.block("minecraft:stone").defaultState());
            memo.open(true);
            boolean got = StepCollisionMemoAccess.isEmpty(memo, player, box);
            memo.close();
            assertEquals(Collisions.isEmpty(player, box, player.y), got);
            assertFalse(got, "the placed stone fills the box");
        }
    }

    private static SimpleCollisionBox oneBoundMoved(SimpleCollisionBox box, SplittableRandom random) {
        SimpleCollisionBox moved = box.copy();
        double by = random.nextDouble(0.05, 0.6);
        switch (random.nextInt(6)) {
            case 0 -> moved.minX -= by;
            case 1 -> moved.minY -= by;
            case 2 -> moved.minZ -= by;
            case 3 -> moved.maxX += by;
            case 4 -> moved.maxY += by;
            default -> moved.maxZ += by;
        }
        return moved;
    }
}
