package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.lang.reflect.Field;
import java.util.SplittableRandom;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * WorldStageBuilder#findMainSupportingBlockPos against a reference search that builds fresh search boxes for every
 * search: same block, same ground flag, same exception, over random worlds, poses and movements.
 */
class SupportSearchEquivalenceTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final String[] PALETTE = {
        "minecraft:air",
        "minecraft:air",
        "minecraft:stone",
        "minecraft:ice",
        "minecraft:oak_fence",
        "minecraft:cobblestone_wall",
        "minecraft:stone_slab",
        "minecraft:snow",
        "minecraft:white_carpet",
        "minecraft:soul_sand",
        "minecraft:moving_piston",
        "minecraft:oak_trapdoor",
    };

    @Test
    void searchesMatchTheFreshBoxSearch() throws Exception {
        SplittableRandom random = new SplittableRandom(0x5EA2C4L);
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            CompensatedWorld world = player.compensatedWorld;
            WorldStageBuilder subject = new WorldStageBuilder();
            Base base = new Base();
            Field scanField = WorldStageBuilder.class.getDeclaredField("supportScan");
            scanField.setAccessible(true);
            Object sharedScan = scanField.get(subject);
            Field inUse = sharedScan.getClass().getDeclaredField("inUse");
            inUse.setAccessible(true);

            int secondSearches = 0, secondFound = 0, nested = 0, thrown = 0, found = 0;
            for (int sample = 0; sample < 20_000; sample++) {
                if (sample % 50 == 0) {
                    for (int x = -3; x <= 4; x++) {
                        for (int y = FLOOR_Y - 2; y <= FLOOR_Y + 1; y++) {
                            for (int z = -3; z <= 4; z++) {
                                String block = PALETTE[random.nextInt(PALETTE.length)];
                                world.updateBlock(
                                        new BlockPos(x, y, z),
                                        BLOCKS.block(block).defaultState());
                            }
                        }
                    }
                    for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff(0);
                }

                player.x = random.nextDouble(-3.0, 5.0);
                player.y = FLOOR_Y + random.nextDouble(-1.5, 1.5);
                player.z = random.nextDouble(-3.0, 5.0);
                SimpleCollisionBox pose = pose(random);
                MainSupportingBlockData last = lastSupport(random);
                double lastX = movement(random);
                double lastZ = movement(random);
                boolean onGround = random.nextInt(8) != 0;
                boolean nest = random.nextInt(10) == 0;
                boolean breakWorld = random.nextInt(40) == 0;

                SimpleCollisionBox subjectPose = copy(pose);
                String expected, actual;
                // A world-less player makes the block scan throw after the search boxes are written.
                if (breakWorld) player.compensatedWorld = null;
                try {
                    expected = outcome(() ->
                            base.findMainSupportingBlockPos(player, copy(last), lastX, lastZ, copy(pose), onGround));
                    if (nest) inUse.setBoolean(sharedScan, true);
                    actual = outcome(() -> subject.findMainSupportingBlockPos(
                            player, copy(last), lastX, lastZ, subjectPose, onGround));
                } finally {
                    player.compensatedWorld = world;
                }
                assertEquals(inUse.getBoolean(sharedScan), nest, "shared scan state after sample " + sample);
                inUse.setBoolean(sharedScan, false);
                assertEquals(expected, actual, "sample " + sample);
                assertEquals(boxText(pose), boxText(subjectPose), "pose untouched, sample " + sample);

                if (actual.startsWith("threw")) thrown++;
                if (actual.startsWith("pos=") && !actual.startsWith("pos=null")) found++;
                if (nest) nested++;
                if (onGround && last != null && !last.lastOnGroundAndNoBlock()) {
                    String first = outcome(
                            () -> base.findMainSupportingBlockPos(player, null, lastX, lastZ, copy(pose), onGround));
                    if (first.startsWith("pos=null")) {
                        secondSearches++;
                        if (!actual.startsWith("pos=null")) secondFound++;
                    }
                }
            }
            assertTrue(found > 4_000, "found " + found);
            assertTrue(secondSearches > 1_000, "second searches " + secondSearches);
            assertTrue(secondFound > 100, "second searches that find a block " + secondFound);
            assertTrue(nested > 1_000, "nested " + nested);
            assertTrue(thrown > 200, "thrown " + thrown);
            assertFalse(inUse.getBoolean(sharedScan));
        }
    }

    private static SimpleCollisionBox pose(SplittableRandom random) {
        double minX = random.nextDouble(-3.0, 5.0), minZ = random.nextDouble(-3.0, 5.0);
        double minY = switch (random.nextInt(5)) {
            case 0 -> FLOOR_Y;
            case 1 -> FLOOR_Y + random.nextInt(-1, 2) * 0.5;
            case 2 -> FLOOR_Y + random.nextInt(-1, 3) * 0.0625 + random.nextInt(-1, 2) * 5.0E-7;
            case 3 -> FLOOR_Y + random.nextInt(-2, 2) + 0.875;
            default -> FLOOR_Y + random.nextDouble(-1.5, 1.5);
        };
        double width = switch (random.nextInt(4)) {
            case 0 -> 0.6;
            case 1 -> random.nextDouble(0.0, 2.0);
            case 2 -> -random.nextDouble(0.0, 0.6);
            default -> random.nextInt(3);
        };
        if (random.nextInt(200) == 0) minX = Double.NaN;
        return new SimpleCollisionBox(minX, minY, minZ, minX + width, minY + 1.8, minZ + width);
    }

    private static @Nullable MainSupportingBlockData lastSupport(SplittableRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> null;
            case 1 -> new MainSupportingBlockData(null, true);
            case 2 -> new MainSupportingBlockData(null, false);
            default -> new MainSupportingBlockData(new BlockPos(random.nextInt(-3, 5), FLOOR_Y - 1, 0), true);
        };
    }

    private static double movement(SplittableRandom random) {
        return switch (random.nextInt(8)) {
            case 0 -> 0.0;
            case 1 -> -0.0;
            case 2 -> random.nextDouble(-3.0, 3.0);
            case 3 -> random.nextInt(200) == 0 ? Double.NaN : 0.5;
            default -> random.nextDouble(-0.6, 0.6);
        };
    }

    private static @Nullable MainSupportingBlockData copy(@Nullable MainSupportingBlockData data) {
        return data == null ? null : new MainSupportingBlockData(data.getBlockPos(), data.isOnGround());
    }

    private static SimpleCollisionBox copy(SimpleCollisionBox box) {
        return new SimpleCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, box.isFullBlock());
    }

    private static String boxText(SimpleCollisionBox box) {
        return Double.doubleToRawLongBits(box.minX) + "," + Double.doubleToRawLongBits(box.minY) + ","
                + Double.doubleToRawLongBits(box.minZ) + "," + Double.doubleToRawLongBits(box.maxX) + ","
                + Double.doubleToRawLongBits(box.maxY) + "," + Double.doubleToRawLongBits(box.maxZ) + ","
                + box.isFullBlock();
    }

    private static String outcome(Search search) {
        try {
            MainSupportingBlockData data = search.run();
            return "pos=" + data.getBlockPos() + " onGround=" + data.isOnGround();
        } catch (RuntimeException e) {
            return "threw " + e.getClass().getName();
        }
    }

    private interface Search {
        MainSupportingBlockData run();
    }

    /** Reference search on fresh boxes. */
    private static final class Base {
        private final SupportScan supportScan = new SupportScan();

        MainSupportingBlockData findMainSupportingBlockPos(
                CultPlayer player,
                @Nullable MainSupportingBlockData lastSupport,
                double lastMovementX,
                double lastMovementZ,
                SimpleCollisionBox maxPose,
                boolean isOnGround) {
            if (!isOnGround) {
                return new MainSupportingBlockData(null, false);
            }

            SimpleCollisionBox slightlyBelowPlayer = new SimpleCollisionBox(
                    maxPose.minX, maxPose.minY - 1.0E-6D, maxPose.minZ, maxPose.maxX, maxPose.minY, maxPose.maxZ);

            BlockPos supportingBlock = findSupportingBlock(player, slightlyBelowPlayer);
            if (supportingBlock == null && lastSupport != null && !lastSupport.lastOnGroundAndNoBlock()) {
                SimpleCollisionBox aabb2 = slightlyBelowPlayer.offset(-lastMovementX, 0.0D, -lastMovementZ);
                return new MainSupportingBlockData(findSupportingBlock(player, aabb2), true);
            }
            return new MainSupportingBlockData(supportingBlock, true);
        }

        private BlockPos findSupportingBlock(CultPlayer player, SimpleCollisionBox searchBox) {
            SupportScan scan = supportScan.inUse ? new SupportScan() : supportScan;
            try {
                SimpleCollisionBox cells = new SimpleCollisionBox(
                        Math.floor(searchBox.minX - 1.0E-7) - 1,
                        Math.floor(searchBox.minY - 1.0E-7) - 1,
                        Math.floor(searchBox.minZ - 1.0E-7) - 1,
                        Math.floor(searchBox.maxX + 1.0E-7) + 1,
                        Math.floor(searchBox.maxY + 1.0E-7) + 1,
                        Math.floor(searchBox.maxZ + 1.0E-7) + 1);
                Collisions.hasState(player, cells, scan.start(player, searchBox, cells));
                return scan.best;
            } finally {
                scan.clear();
            }
        }

        private static final class SupportScan implements Collisions.StateAtPredicate {
            private boolean inUse;
            private CultPlayer player;
            private SimpleCollisionBox searchBox;
            private SimpleCollisionBox cells;
            private double playerX, playerY, playerZ;
            private BlockPos best;
            private double bestDistance;

            private SupportScan start(CultPlayer player, SimpleCollisionBox searchBox, SimpleCollisionBox cells) {
                inUse = true;
                this.player = player;
                this.searchBox = searchBox;
                this.cells = cells;
                playerX = player.x;
                playerY = player.y;
                playerZ = player.z;
                best = null;
                bestDistance = Double.MAX_VALUE;
                return this;
            }

            private void clear() {
                player = null;
                searchBox = null;
                cells = null;
                best = null;
                inUse = false;
            }

            @Override
            public boolean test(int block, int x, int y, int z) {
                int outer = (x == cells.minX || x == cells.maxX ? 1 : 0)
                        + (y == cells.minY || y == cells.maxY ? 1 : 0)
                        + (z == cells.minZ || z == cells.maxZ ? 1 : 0);
                if (outer == 3
                        || outer == 1 && !BLOCKS.facts(block).has(StateFacts.LARGE_COLLISION)
                        || outer == 2 && !BlockIds.is(block, BlockIds.MOVING_PISTON)) return false;

                CollisionBox collision = ClientBlockShapes.movement(player, block, x, y, z);
                if (!collision.isIntersected(searchBox)) return false;

                double distanceX = x + 0.5 - playerX;
                double distanceY = y + 0.5 - playerY;
                double distanceZ = z + 0.5 - playerZ;
                double distance = distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ;

                if (distance < bestDistance) {
                    best = new BlockPos(x, y, z);
                    bestDistance = distance;
                } else if (distance == bestDistance) {
                    BlockPos blockPos = new BlockPos(x, y, z);
                    if (best == null || best.compareTo(blockPos) < 0) {
                        best = blockPos;
                        bestDistance = distance;
                    }
                }

                return false;
            }
        }
    }
}
